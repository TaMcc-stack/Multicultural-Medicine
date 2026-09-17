<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppTopbar from '@/components/AppTopbar.vue'
import DocEvidenceViewer from '@/components/DocEvidenceViewer.vue'
// 知识库文档的溯源按分区展示（PDF 原页 / 正文片段），与问答页共用同一个组件
import EvidenceSourceViewer from '@/components/EvidenceSourceViewer.vue'
import { useAnalysisStore } from '@/stores/analysis'
import type { AnalysisSource } from '@/stores/analysis'
import { intentLabel, clarifyTier, CLARIFY_TIER_LABEL } from '@/api/qa'
import type { EvidencePoolResult, GenerateResult, GenerateAnswer, KnowledgeDoc, PoolCandidate, StoredQuestion } from '@/api/qa'
import { getConversation, listConversations } from '@/api/chat'
import type { ChatMessage } from '@/api/chat'
import { highlightTerms } from '@/utils/highlight'

const router = useRouter()
const route = useRoute()

// ============================================================
// 后台 · 分析工作台
// 读取智能对话页「验证后的当前问题」+ 共享分析状态，
// 完整还原 问题理解 → 知识检索 → 加工生成 的可溯源流水线
// ============================================================
const QKEY = 'mmx_question_v2'
const LIT_KEY = 'mmx_kb_lit_v2'

interface LitTable {
  page?: number
  caption?: string
  header: string[]
  rows: string[][]
  note?: string
}

interface LiteratureItem {
  id: string
  title: string
  category?: string
  content?: string
  excerpt?: string
  table?: LitTable
  ethnic?: string
  disease?: string
}

function readQuestion(): StoredQuestion | null {
  try {
    const raw = localStorage.getItem(QKEY)
    if (raw) {
      const p = JSON.parse(raw)
      if (p && p.text) return p as StoredQuestion
    }
  } catch {
    /* ignore */
  }
  return null
}

function readLiterature(): LiteratureItem[] {
  try {
    const raw = localStorage.getItem(LIT_KEY)
    const arr = raw ? JSON.parse(raw) : []
    return Array.isArray(arr) ? (arr as LiteratureItem[]) : []
  } catch {
    return []
  }
}

// ---------- 追溯模式 ----------
// 从对话页点某条历史问答的「查看后台分析」进来时，用该消息**落库时存下的 detail**
// 还原当时的「问题理解 → 知识检索 → 加工生成」。
// 刻意不重跑：重跑会因大模型随机性而与用户当时看到的回答不一致，那就不是「追溯」了。
const traceMsgId = ref<number | null>(null)
const traceConvTitle = ref('')
const tracedPool = ref<EvidencePoolResult | null>(null)
const tracedGenerate = ref<GenerateResult | null>(null)
const isTracing = computed(() => traceMsgId.value != null)

const analysisStore = useAnalysisStore()
/**
 * 活模式下「当前分析的问题」有两个可能的来源，取共享状态那份优先。
 *
 * 共享状态才是权威：对话页的 `load()` 与高级检索页的 `adopt()` 都会写它的 `question`。
 * localStorage 那份只用于**刷新后的兜底**——刷新会把 store 清空，而 localStorage 还在。
 *
 * 此前这里只读 localStorage，于是高级检索登记进共享状态的那次分析，问题栏还显示着
 * 上一句对话的内容，「问题理解」与「知识检索」两块对不上。
 */
const storedQ = ref<StoredQuestion | null>(readQuestion())
/** 追溯模式的问题（取自被追溯的那条历史消息），与上面是两个独立来源 */
const traceQ = ref<StoredQuestion | null>(null)

/** 「来源」开关的两个选项。声明顺序 = 界面上的排列顺序。 */
const SOURCE_OPTIONS: { key: AnalysisSource; label: string }[] = [
  { key: 'dialogue', label: '智能对话' },
  { key: 'search', label: '高级检索' },
]

/**
 * 实际展示哪一条来源的记录。
 *
 * 共享状态里两条记录各自独立存着（见 `stores/analysis.ts`），这里只决定展示哪一条。
 * 选中的那一侧还没有记录时**退到另一侧**：刚登录、或某一侧被重置掉时，
 * 让工作台显示一片空白不如显示手里确实有的那条。两侧都空就维持在选中侧（正常的空态）。
 */
const shownSource = computed<AnalysisSource>(() => {
  const active = analysisStore.activeSource
  if (analysisStore.records[active].question) return active
  const other: AnalysisSource = active === 'dialogue' ? 'search' : 'dialogue'
  return analysisStore.records[other].question ? other : active
})

/**
 * 工作台当前展示的那条分析记录（追溯模式下另走一套快照，与它无关）。
 *
 * 读的是 `records[shownSource]` 而不是 `activeSource` 上的字段：两者只在「选中侧是空的、
 * 已退到另一侧」时分叉，那时要展示的是退过去的那条。
 */
const liveRecord = computed(() => analysisStore.records[shownSource.value])

const q = computed<StoredQuestion | null>(() =>
  isTracing.value ? traceQ.value : (liveRecord.value.question ?? storedQ.value),
)
const literature = ref<LiteratureItem[]>(readLiterature())

// —— 共享分析状态（知识证据池 + 加工生成）：前台发送后已算好并缓存，
//    后台打开时命中缓存直接复用，不再每次进入都独立重跑一遍 ——
//    追溯模式下改读该条历史消息存下的快照。
const retrieveResult = computed(() => (isTracing.value ? tracedPool.value : liveRecord.value.pool))
const generateResult = computed(() => (isTracing.value ? tracedGenerate.value : liveRecord.value.generate))
const ragLoading = computed(() => (isTracing.value ? false : liveRecord.value.loading))

async function runRag() {
  // 高级检索那条是 `adopt` 登记进来的、本来就是算好的，不需要这里补跑。
  // 补跑还会写进 dialogue 槽并把 activeSource 抢回 dialogue —— 用户刚切到「高级检索」就跳回去了。
  if (shownSource.value === 'search') return
  await analysisStore.load(q.value, literature.value as unknown as KnowledgeDoc[])
}

/** 按 messageId 载入该条历史问答的三段快照 */
async function loadTrace(messageId: number, convId?: number) {
  try {
    let cid = convId
    if (cid == null) {
      // 没带会话 id（例如直接粘贴链接进来）：从会话列表里找包含这条消息的那个
      const list = await listConversations()
      for (const c of list) {
        const d = await getConversation(c.id)
        if ((d.messages || []).some((m: ChatMessage) => m.id === messageId)) {
          cid = c.id
          break
        }
      }
    }
    if (cid == null) throw new Error('找不到这条问答所属的会话')

    const detail = await getConversation(cid)
    const msgs: ChatMessage[] = detail.messages || []
    const idx = msgs.findIndex((m) => m.id === messageId)
    if (idx < 0) throw new Error('这条问答已不存在')
    // 显式收窄：`noUncheckedIndexedAccess` 下 msgs[idx] 视为可能 undefined，
    // 只判 idx < 0 不足以让 TS 把元素也当成存在（索引和元素类型之间没有关联）
    const msg = msgs[idx]
    if (!msg) throw new Error('这条问答已不存在')
    const prev = idx > 0 ? msgs[idx - 1] : undefined
    const prevUser = prev && prev.role === 'user' ? prev : null
    const d = (msg.detail || {}) as Record<string, any>
    const u = d.understanding || {}

    traceMsgId.value = messageId
    traceConvTitle.value = detail.conversation.title || '历史问答'
    traceQ.value = {
      text: prevUser?.content || '(历史问答)',
      ethnic: u.ethnicity || '',
      disease: u.disease || '',
      intent: u.intent || '',
      status: 'clear',
      confidence: null,
      // 能追溯的都是落进会话消息的分析，来源必然是对话页
      source: 'dialogue',
    }
    // 早于本次改动落库的消息没有 retrieval 快照，此时检索模块会显示为空 —— 属正常
    tracedPool.value = d.retrieval ? ({ ...d.retrieval } as EvidencePoolResult) : null
    tracedGenerate.value = d.answer
      ? ({ answer: d.answer as GenerateAnswer, evidence_found: true } as GenerateResult)
      : null
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载追溯失败')
    exitTrace()
  }
}

/** 退出追溯，回到「跟随前台当前问题」的常规模式 */
function exitTrace() {
  traceMsgId.value = null
  traceConvTitle.value = ''
  tracedPool.value = null
  tracedGenerate.value = null
  analysisStore.clearTraceTarget()
  if (route.query.message) router.replace({ name: 'backend' })
  traceQ.value = null
  storedQ.value = readQuestion()
  literature.value = readLiterature()
  runRag()
}

function reload() {
  // 追溯目标：优先路由参数（直接粘链接进来），否则读对话页点击时写入的共享状态。
  // 对话页点击时**不跳转**，所以正常路径走的是后者。
  const mid = Number(route.query.message) || analysisStore.traceTarget?.messageId || 0
  if (Number.isFinite(mid) && mid > 0) {
    const cid = Number(route.query.conv) || analysisStore.traceTarget?.conversationId || undefined
    loadTrace(mid, cid)
    return
  }
  traceMsgId.value = null
  storedQ.value = readQuestion()
  literature.value = readLiterature()
  runRag()
}
onMounted(reload)
onActivated(reload)

/** 泛民族查询（后端用哨兵值 "GENERAL" 表示「不指向某一具体民族」） */
const isPanEthnic = computed(() => !!(q.value && q.value.ethnic === 'GENERAL'))
/** 药物 / 诊疗咨询：命中医疗安全边界 */
const isMedicalAdvice = computed(() => !!(q.value && q.value.medicalAdvice))
const ethnic = computed(() => {
  if (!q.value) return '无'
  // 用药咨询走的是拦截分支，民族字段对它没有意义，别显示成「泛民族」误导人
  if (isMedicalAdvice.value) return '—（不适用）'
  if (isPanEthnic.value) return '泛民族（全库检索）'
  return q.value.ethnic || '未识别'
})
const disease = computed(() => (q.value ? q.value.disease || '未识别' : '无'))
const intent = computed(() => {
  if (!q.value) return '无'
  // 命中具体指标（如「患病率」）时优先显示指标名，而非泛化的「患病情况」
  if (q.value.intentText) return q.value.intentText
  return q.value.intent ? intentLabel(q.value.intent) : '不清楚'
})
const questionType = computed(() => (q.value ? q.value.questionType || '未标注' : '无'))
const standardized = computed(() => (q.value ? q.value.standardized || '' : ''))
const confTxt = computed(() => (q.value && q.value.confidence != null ? `（置信度 ${q.value.confidence}）` : ''))

/**
 * 这次分析来自哪个入口。
 *
 * 缺省按「智能对话」显示而不是显示成未知：`source` 是这次才加的字段，
 * 在此之前只有对话页会写分析，存量记录全部来自对话。
 */
const isFromSearch = computed(() => q.value?.source === 'search')
const sourceLabel = computed(() => (isFromSearch.value ? '高级检索' : '智能对话'))

const hasEthnic = computed(() => !!(q.value && q.value.ethnic))
const hasDisease = computed(() => !!(q.value && q.value.disease))
const hasIntent = computed(() => !!(q.value && q.value.intent))
/**
 * 是否放行进入检索 / 加工。
 *
 * 必须与前台（对话页 `analysisStore.load` 的准入条件）保持同一判据：民族 + 意图齐全即可，
 * 疾病可以缺——缺了就走「民族 + 意图」的泛化检索。此前这里仍要求疾病必须有，
 * 于是同一份数据前台出了回答、后台却判「无法理解」、各模块全空。
 * 医疗安全边界例外：用药咨询没有民族也要放行，由后端返回拒绝话术。
 */
const isClear = computed(() => {
  if (q.value && q.value.medicalAdvice) return true
  return !!(q.value && hasEthnic.value && hasIntent.value)
})
/** 泛化检索：民族 + 意图有，疾病没识别出来 */
const generalized = computed(() => !!(q.value && hasEthnic.value && !hasDisease.value && hasIntent.value))

function esc(s: unknown): string {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** Markdown 粗体 → <b>。必须在 esc 之后调用，只处理已转义的文本。 */
function mdBold(s: string): string {
  return s.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>')
}

/**
 * 证据引用渲染：**先转义，再把命中词标成 `<mark>`**。
 * 顺序不能反——先插标签再转义会把自己的标签也转掉，不转义直接插就是 XSS。
 */
function quoteHtml(e: { fragment: string; matchedTerms?: string[] }): string {
  return highlightTerms(esc(e.fragment), e.matchedTerms)
}

function splitMdRow(line: string): string[] {
  const cells = line.split('|')
  const first = cells[0] ?? ''
  const last = cells[cells.length - 1] ?? ''
  const start = first.trim() === '' ? 1 : 0
  const end = last.trim() === '' ? cells.length - 1 : cells.length
  return cells.slice(start, end).map((c) => c.trim())
}

/** 把 LLM 返回的 Markdown 正文渲染为 HTML：普通文本转义，Markdown 表格转 <table>。 */
function mdToHtml(text: string): string {
  if (!text) return ''
  const lines = text.split('\n')
  let out = ''
  let i = 0
  while (i < lines.length) {
    const line = lines[i] ?? ''
    const next = i + 1 < lines.length ? (lines[i + 1] ?? '') : ''
    if (line.includes('|') && next.includes('-') && /^[\s|:\-]+$/.test(next)) {
      const firstBar = line.indexOf('|')
      const pre = line.slice(0, firstBar).trim()
      if (pre) out += `<div>${esc(pre)}</div>`
      const header = splitMdRow(line.slice(firstBar))
      const rows: string[][] = []
      i += 2
      while (i < lines.length && (lines[i] ?? '').includes('|')) {
        rows.push(splitMdRow(lines[i] ?? ''))
        i++
      }
      const colCount = Math.max(header.length, ...rows.map((r) => r.length))
      out += '<table class="ans-table"><thead><tr>'
        + header.map((c) => `<th>${esc(c)}</th>`).join('')
        + '</tr></thead><tbody>'
        + rows.map((r) => '<tr>' + Array.from({ length: colCount }, (_, k) => `<td>${esc(r[k] ?? '')}</td>`).join('') + '</tr>').join('')
        + '</tbody></table>'
      continue
    }
    if (line.trim()) out += `<div>${mdBold(esc(line))}</div>`
    i++
  }
  return out
}

function renderTable(t: LitTable | undefined, hlEthnic: string): string {
  if (!t || !t.rows) return ''
  let h = '<table class="lit-table"><thead><tr>' + t.header.map((c) => `<th>${esc(c)}</th>`).join('') + '</tr></thead><tbody>'
  h += t.rows
    .map((r) => {
      const isHl = hlEthnic && r[0] === hlEthnic
      return '<tr>' + r.map((c) => `<td class="${isHl ? 'hl' : ''}">${esc(c)}</td>`).join('') + '</tr>'
    })
    .join('')
  h += '</tbody></table>'
  if (t.caption) h = `<div class="lit-note" style="font-weight:500;color:var(--ink-2)">${esc(t.caption)}（第 ${esc(t.page)} 页）</div>` + h
  if (t.note) h += `<div class="lit-note">${esc(t.note)}</div>`
  return h
}

function findValue(l: LiteratureItem, eth: string): string {
  if (l.table && l.table.rows) {
    for (const row of l.table.rows) if (row[0] === eth) return row[1] || ''
  }
  return ''
}

function structuredAnswerHtml(a: GenerateAnswer): string {
  const isNew = !!a.detailed || !!a.timeRegion || !!a.followUps
  let h = ''
  // 结论走 Markdown：这几个方面（危险因素/症状/预防/饮食/遗传）提示词要求用列表作答，
  // 列表就落在 conclusion 里。只做 esc 的话「- 」会原样显示出来。
  if (a.conclusion) h += `<div class="answer-text">${mdToHtml(a.conclusion)}</div>`
  if (a.detailed) {
    h += `<div class="ans-sec"><div class="ans-sec-title">文献数据说明</div><div class="ans-sec-body">${mdToHtml(a.detailed)}</div></div>`
  }
  if (a.actions) {
    h += `<div class="ans-sec"><div class="ans-sec-title">👨⚕️ 专家行动建议</div><div class="ans-sec-body">${mdToHtml(a.actions)}</div></div>`
  }
  for (const s of a.sections || []) {
    const title = s.title ? `<div class="ans-sec-title">${esc(s.title)}</div>` : ''
    h += `<div class="ans-sec">${title}<div class="ans-sec-body">${mdToHtml(s.content)}</div></div>`
  }
  if (a.format === 'table' && a.table) {
    const cols = a.table.columns || []
    const rows = a.table.rows || []
    const th = cols.map((c) => `<th>${esc(c)}</th>`).join('')
    const trs = rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')
    h += `<table class="ans-table"><thead><tr>${th}</tr></thead><tbody>${trs}</tbody></table>`
  }
  if (a.format === 'chart' && a.chart && a.chart.data && a.chart.data.length) {
    const data = a.chart.data
    const max = Math.max(...data.map((d) => d.value || 0), 1)
    h += `<div class="ans-chart"><div class="ans-chart-title">${esc(a.chart.title || '')}</div>`
    for (const d of data) {
      const pct = Math.round((d.value / max) * 100)
      h += `<div class="ans-bar-row"><span class="ans-bar-label">${esc(d.label)}</span>`
      h += `<div class="ans-bar-track"><div class="ans-bar-fill" style="width:${pct}%"></div></div>`
      h += `<span class="ans-bar-val">${esc(String(d.value))}${esc(a.chart.unit || '')}</span></div>`
    }
    h += `</div>`
  }
  if (a.sources || a.scope) {
    // 新「三件套」：证据来源 / 适用范围 / 使用边界 三张独立小卡片。
    const cards: string[] = []
    if (a.sources && a.sources !== '—') cards.push(`<div class="ans-card"><div class="ans-card-title">📚 证据来源</div><div class="ans-card-body">${mdToHtml(a.sources)}</div></div>`)
    const scopeBody = a.scope && a.scope !== '—' ? a.scope : '⚠️ 该文献未明确说明适用范围，请谨慎参考'
    cards.push(`<div class="ans-card"><div class="ans-card-title">🎯 适用范围</div><div class="ans-card-body">${mdToHtml(scopeBody)}</div></div>`)
    const boundaryBody = a.cautions && a.cautions !== '—' ? a.cautions : '该文献未明确说明使用边界，建议结合个人实际情况咨询专业医生。'
    cards.push(`<div class="ans-card"><div class="ans-card-title">⚠️ 使用边界</div><div class="ans-card-body">${mdToHtml(boundaryBody)}</div></div>`)
    h += `<div class="ans-trio">${cards.join('')}</div>`
  } else if (isNew) {
    // 旧结构把适用边界拆成三个字段条目；新结构是一段大白话的专家提醒，整段落在 cautions。
    if (a.applicable && a.applicable !== '—') h += `<div class="ans-note">适用人群：${esc(a.applicable)}</div>`
    if (a.timeRegion && !a.timeRegion.includes('未明确')) h += `<div class="ans-note">研究时间/地区：${esc(a.timeRegion)}</div>`
    if (a.cautions && a.cautions !== '—') h += `<div class="ans-note warn">特别提醒：${mdToHtml(a.cautions)}</div>`
  } else {
    if (a.applicable) h += `<div class="ans-note">适用人群：${esc(a.applicable)}</div>`
    if (a.cautions) h += `<div class="ans-note warn">注意：${esc(a.cautions)}</div>`
  }
  if (a.followUps && a.followUps.length) {
    h += `<div class="ans-sec"><div class="ans-sec-title">💬 您可能还想问</div><div class="ans-sec-body">${a.followUps.map((f) => '· ' + esc(f)).join('<br>')}</div></div>`
  }
  return h
}

// ---------- 验证结论 ----------
const verdict = computed(() => {
  if (!q.value)
    return { text: '还没收到问题 · 请先到「智能对话」页提问并验证', cls: 'idle', badge: '未收到问题', badgeCls: 'demo' }
  if (isClear.value) {
    if (isMedicalAdvice.value) {
      return { text: '已触发医疗安全边界 · 该问题为用药 / 诊疗咨询，系统不做知识检索，直接返回就医引导话术',
               cls: 'warn', badge: '安全拦截', badgeCls: 'warn' }
    }
    if (isPanEthnic.value) {
      return { text: `验证通过 · 已识别为泛民族查询，将检索所有民族相关的资料${confTxt.value}`,
               cls: 'warn', badge: '泛民族', badgeCls: 'warn' }
    }
    if (generalized.value) {
      return { text: `验证通过 · 未识别出具体疾病，已启用泛化检索（基于民族 + 意图）${confTxt.value}`,
               cls: 'warn', badge: '泛化放行', badgeCls: 'warn' }
    }
    return { text: `验证通过 · 问题清楚，已经过知识检索与回答生成${confTxt.value}`, cls: 'ok', badge: '已验证', badgeCls: '' }
  }
  if (hasEthnic.value && hasDisease.value) {
    const conf = q.value!.confidence
    const tier = clarifyTier(conf)
    const label = CLARIFY_TIER_LABEL[tier]
    const reason = tier === 'must' ? '问题理解不够确定（置信度偏低）' : '问题不够具体（查询意图不明确）'
    return { text: `${label} · ${reason}，请补充想了解什么${confTxt.value}`, cls: 'fail', badge: label, badgeCls: 'fail' }
  }
  return { text: `无法理解 · 信息不足（缺少「民族」或「查询意图」）${confTxt.value}`, cls: 'fail', badge: '无法理解', badgeCls: 'fail' }
})

// ---------- 知识证据池 ----------
const STATUS_META: Record<string, { emoji: string; label: string }> = {
  sufficient: { emoji: '🟢', label: '证据充分' },
  partial: { emoji: '🟡', label: '证据部分相关' },
  none: { emoji: '🔴', label: '无直接证据' },
}
function statusMeta(s: string) {
  return STATUS_META[s] || { emoji: '⚪', label: '未知' }
}
const poolStatus = computed(() => {
  const s = retrieveResult.value ? retrieveResult.value.status : 'none'
  return { ...statusMeta(s), cls: s }
})

/** 候选资料默认只展示前几条，其余折叠——一次列十几份没人看，还盖住了真正相关的那几份 */
const CANDIDATE_TOP = 5
const candidatesExpanded = ref(false)
const visibleCandidates = computed(() => {
  const all = retrieveResult.value?.candidates || []
  return candidatesExpanded.value ? all : all.slice(0, CANDIDATE_TOP)
})
const hiddenCandidateCount = computed(() =>
  Math.max(0, (retrieveResult.value?.candidates || []).length - CANDIDATE_TOP),
)

/**
 * 相关度标签：**按排名给**，不假装是服务端算出来的分数。
 *
 * 候选列表本来就是按相关度排好序的，但服务端没把分数回传给前端。与其在这里编一个
 * 「0.87」那样的数字，不如直接说排在第几——高：前 2 条；中：3~5 条；低：其余。
 * 三条的分界与「默认展示前 5 条」是同一条线，折叠区里的一律是「低」。
 */
function relevanceOf(index: number): { label: string; cls: string } {
  if (index < 2) return { label: '高', cls: 'high' }
  if (index < CANDIDATE_TOP) return { label: '中', cls: 'mid' }
  return { label: '低', cls: 'low' }
}

const packedMeta = computed(() => {
  const ev = retrieveResult.value?.evidence
  if (!ev || !ev.length) return '—'
  const titles = [...new Set(ev.map((e) => e.title).filter(Boolean))]
  return `《${titles.join('》《')}》 · 共提取 ${ev.length} 段证据片段`
})

const packedTableHtml = computed(() => {
  const ev = retrieveResult.value?.evidence
  if (!ev || !ev.length) return ''
  return ev.map((e) => `<div class="text-evidence">${esc(e.fragment)}</div>`).join('')
})

// ---------- 加工 / 生成 ----------
const matQuestion = computed(() => (q.value ? q.value.text : '无'))
// 命中文献：优先取证据池实际打包的证据来源，回退到「提取到证据的候选资料」
const hitSources = computed(() => {
  const ev = retrieveResult.value?.evidence
  if (ev && ev.length) return [...new Set(ev.map((e) => e.title).filter(Boolean))]
  const cands = retrieveResult.value?.candidates || []
  return [...new Set(cands.filter((c) => (c.evidence?.length ?? 0) > 0).map((c) => c.title))]
})
const matDoc = computed(() => (hitSources.value.length ? `《${hitSources.value.join('》《')}》` : '无'))
const procDocTitle = computed(() => {
  const ev = retrieveResult.value?.evidence
  return ev && ev.length ? ev[0]!.title : '无'
})
const procTriple = computed(() =>
  isClear.value && q.value ? `${q.value.ethnic} · ${q.value.disease} · ${intentLabel(q.value.intent || '')}` : '无'
)
const evidenceCount = computed(() => retrieveResult.value?.evidence?.length || 0)
const evidenceKind = computed(() => {
  if (ragLoading.value) return '检索中…'
  const ev = retrieveResult.value?.evidence
  if (!ev || !ev.length) return '无可引用证据'
  if (ev.length === 1) return `一段证据片段${ev[0]!.page ? `（第 ${ev[0]!.page} 页）` : ''}`
  return `${ev.length} 段证据片段`
})

const shotItems = computed(() => {
  if (!isClear.value || ragLoading.value) return []
  const ev = retrieveResult.value?.evidence
  return ev ? ev.map((e) => ({ id: e.id || '', title: e.title, fragment: e.fragment, page: e.page })) : []
})
const shotNote = computed(() => {
  if (!isClear.value) return '无'
  if (ragLoading.value) return '正在检索知识库…'
  return retrieveResult.value?.reason || '未找到相关证据'
})

// 查看原文：完整文献收进弹窗，检索结果区只留证据卡片，避免左右分栏挤压
const fullTextVisible = ref(false)
const fullTextDoc = ref<PoolCandidate | null>(null)
const focusEvidenceIndex = ref<number | null>(null)

/**
 * 查看原文：完整文献收进弹窗，检索结果区只留证据卡片，避免左右分栏挤压。
 *
 * 不再在这里预取全文——展示 PDF 原页还是正文片段由分区决定，那套判断连同取数
 * 都在 EvidenceSourceViewer 里，这里只负责把「点了哪一份、哪一条」传过去。
 */
function openFullText(c: PoolCandidate, index: number | null = null) {
  fullTextDoc.value = c
  focusEvidenceIndex.value = index
  fullTextVisible.value = true
}

/** 点开 PDF 原文时要定位到第几页：按点击的那条证据的页码；没有页码则交给组件从第 1 页开始 */
function pageOfEvidence(c: PoolCandidate, index: number | null): number | null {
  const ev = (c.evidence || [])[index ?? 0]
  const p = Number(ev?.page)
  return Number.isFinite(p) && p > 0 ? p : null
}

function goToDoc(id?: string) {
  if (id) router.push({ name: 'kb', hash: `#${id}` })
}

const answerLineHtml = computed(() => {
  if (!isClear.value) return '无（问题未验证通过，暂不加工）'
  if (ragLoading.value) return '正在生成回答…'
  const g = generateResult.value
  if (g && g.answer) return structuredAnswerHtml(g.answer)
  const r = retrieveResult.value
  if (r && !r.evidence_found) return `<div class="lit-note">${esc(r.reason || '知识库证据不足，无法提供可靠回答。')}</div>`
  return '生成回答失败，请稍后重试。'
})

// ---------- 溯源链路（顶部步骤导航） ----------
type TraceState = 'done' | 'running' | 'fail' | 'idle'
const traceNodes = computed<{ id: string; label: string; state: TraceState }[]>(() => {
  const qq = q.value
  const hasQ = !!(qq && qq.text)
  const understood = !!(qq && qq.ethnic && qq.disease && qq.intent)
  const pool = retrieveResult.value
  const retrieved = !!pool && (pool.candidates?.length ?? 0) > 0
  const answered = !!(generateResult.value && generateResult.value.answer)
  return [
    { id: 'sec-understand', label: '问题理解', state: hasQ ? (understood ? 'done' : qq ? 'fail' : 'idle') : 'idle' },
    { id: 'sec-extract', label: '知识检索', state: retrieved ? 'done' : 'idle' },
    { id: 'sec-process', label: '加工生成', state: answered ? 'done' : 'idle' },
  ]
})
function goStage(id: string) {
  nextTick(() => {
    const el = document.getElementById(id)
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}
function backToChat() {
  router.push({ name: 'chat' })
}

// ---------- 方框展开 / 收起 ----------
const open = reactive({
  understand: true,
  extract: isClear.value,
  process: isClear.value,
})

function toggle(name: 'understand' | 'extract' | 'process') {
  open[name] = !open[name]
}

// ---------- 重新开始 ----------
function reset() {
  try {
    localStorage.removeItem(QKEY)
  } catch {
    /* ignore */
  }
  analysisStore.reset()
  // 追溯模式也要一并退出，否则页面会继续显示那条历史问答的快照
  traceMsgId.value = null
  traceConvTitle.value = ''
  tracedPool.value = null
  tracedGenerate.value = null
  analysisStore.clearTraceTarget()
  if (route.query.message) router.replace({ name: 'backend' })
  traceQ.value = null
  storedQ.value = null
  literature.value = readLiterature()
  open.extract = false
  open.process = false
}
</script>

<template>
  <div class="page">
    <AppTopbar />

    <main class="main">
      <div class="work">
        <div class="page-head">
          <div>
            <h1 class="page-title">后台 · 分析工作台</h1>
            <p class="page-sub">问题理解 → 知识检索 → 加工生成，整个过程都可溯源</p>
          </div>
          <button class="btn btn-sm btn-outline back-btn" type="button" @click="backToChat">← 返回对话</button>
        </div>

        <!-- 追溯模式提示：正在看的是某条历史问答，而不是前台当前问题 -->
        <div v-if="isTracing" class="trace-banner">
          <span class="tb-icon">🔎</span>
          <span class="tb-body">
            当前正在追溯的问答：<b>{{ q ? q.text : '' }}</b>
            <span class="tb-sub">（来自会话《{{ traceConvTitle }}》，展示的是这条问答当时保存下来的分析快照）</span>
          </span>
          <button class="btn btn-sm btn-outline btn-sage tb-back" type="button" @click="exitTrace">← 返回当前问题</button>
        </div>

        <div class="question-bar">
          <!-- 来源**开关**：同一套 RAG 链路线上有两个入口，两条分析记录各自独立存着
               （见 stores/analysis.ts 的 records）。这既是标记也是切换器——切过去看另一条，
               不会互相顶掉。有记录的一侧带个小圆点，空的那侧一眼看得出。
               追溯模式下没有可切的（追溯目标只可能来自对话页），退回成静态标记。 -->
          <div v-if="!isTracing" class="source-switch" role="tablist">
            <button
              v-for="opt in SOURCE_OPTIONS"
              :key="opt.key"
              class="source-opt"
              :class="{ on: shownSource === opt.key }"
              type="button"
              role="tab"
              :aria-selected="shownSource === opt.key"
              :disabled="!analysisStore.records[opt.key].question"
              :title="analysisStore.records[opt.key].question
                ? `查看${opt.label}那条分析记录`
                : `目前还没有${opt.label}的分析记录`"
              @click="analysisStore.setActiveSource(opt.key)"
            >
              <span class="source-dot" :class="{ off: !analysisStore.records[opt.key].question }"></span>{{ opt.label }}
            </button>
          </div>
          <span v-else class="q-source">来源：{{ sourceLabel }}</span>
          <span class="q-label">{{ isTracing ? '追溯的问题' : '本次分析的问题' }}</span>
          <span class="q-text">{{ q ? q.text : '无' }}</span>
          <span class="q-badge" :class="verdict.badgeCls">{{ verdict.badge }}</span>
          <button class="btn btn-sm btn-ghost btn-danger reset-btn" type="button" @click="reset">重新开始</button>
        </div>

        <!-- 溯源链路：点击可跳转对应阶段 -->
        <div class="trace">
          <button
            v-for="(n, i) in traceNodes"
            :key="n.id"
            class="btn btn-sm btn-outline trace-node"
            :class="n.state"
            type="button"
            @click="goStage(n.id)"
          >
            <span class="trace-dot" />
            <span>{{ n.label }}</span>
            <span v-if="i < traceNodes.length - 1" class="trace-arrow">→</span>
          </button>
        </div>

        <!-- ① 问题理解 -->
        <section id="sec-understand" class="box" :class="{ open: open.understand }">
          <button class="box-head" type="button" @click="toggle('understand')">
            <span>
              <span class="box-name">问题理解</span><span class="box-tag">先把问题读明白</span>
            </span>
            <svg class="chev" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>
          </button>
          <div class="box-body">
            <div class="note">
              用户在「智能对话」页输入的问题，先经过验证拆解：「民族 + 查询意图」齐全即可放行。
              若能同时识别出「疾病」，走精准检索；只缺疾病时降级为「民族 + 意图」的泛化检索，
              不会把问题判成无法理解。
            </div>
            <div class="verdict" :class="verdict.cls">{{ verdict.text }}</div>
            <div v-if="isMedicalAdvice" class="degrade-tip">
              🛡️ 医疗安全边界：用户问的是<b>用药 / 诊疗</b>（问药、治疗、剂量等）。系统不检索知识库、不提供任何药物名称或治疗方案，直接返回就医引导话术。
            </div>
            <div v-else-if="isPanEthnic" class="degrade-tip">
              ⚠️ 已识别为泛民族查询，将检索所有民族相关的资料。返回的是覆盖多个民族的**整体研究**，具体到某个民族的数据需进一步查询。
            </div>
            <div v-else-if="generalized" class="degrade-tip">
              ⚠️ 未识别出具体疾病，已启用泛化检索（基于民族 + 意图）。检索结果是该民族的整体健康资料，不是该民族该疾病的专属数据。
            </div>
            <div class="decomp">
              <div class="decomp-row"><span class="decomp-key">民族</span><span class="decomp-val">{{ ethnic }}</span></div>
              <div class="decomp-row">
                <span class="decomp-key">疾病</span>
                <span class="decomp-val" :class="{ 'degraded': generalized }">{{ generalized ? '未识别（已降级）' : disease }}</span>
              </div>
              <div class="decomp-row"><span class="decomp-key">查询意图</span><span class="decomp-val">{{ intent }}</span></div>
              <div class="decomp-row">
                <span class="decomp-key">置信度</span>
                <span class="decomp-val">{{ q && q.confidence != null ? q.confidence : '无' }}</span>
              </div>
              <div class="decomp-row">
                <span class="decomp-key">问题类型</span>
                <span class="decomp-val">{{ questionType }}</span>
              </div>
              <div class="decomp-row">
                <span class="decomp-key">标准化问题</span>
                <span class="decomp-val" style="color: var(--ink); font-weight: 500;">{{ standardized || '无' }}</span>
              </div>
            </div>
          </div>
        </section>

        <!-- ② 知识检索 -->
        <section id="sec-extract" class="box" :class="{ open: open.extract }">
          <button class="box-head" type="button" @click="toggle('extract')">
            <span>
              <span class="box-name">知识检索</span><span class="box-tag blue">两阶段 · 候选资料检索 + 证据提取</span>
            </span>
            <svg class="chev" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>
          </button>
          <div class="box-body">
            <div class="flow">
              <div class="flow-step">
                <span class="flow-num">1</span>
                <span class="flow-txt">
                  带着结构化结果去检索：
                  <span class="struct-chip">民族 = {{ ethnic }}</span>
                  <span class="struct-chip">疾病 = {{ disease }}</span>
                  <span class="struct-chip">查询意图 = {{ intent }}</span>
                </span>
              </div>
              <div class="flow-step">
                <span class="flow-num">2</span>
                <span class="flow-txt">候选资料检索：从【<b>papers.json 文献</b> + 知识库《数据资料》】里，找出与「民族 + 疾病」相关的候选资料。</span>
              </div>
              <div class="flow-step">
                <span class="flow-num">3</span>
                <span class="flow-txt">证据提取：逐份判断能不能回答当前「查询意图」，把能用的片段摘出来，并标注充分度（🟢 充分 / 🟡 部分相关 / 🔴 无直接证据）。</span>
              </div>
            </div>

            <div v-if="isClear" class="pool-status-bar" :class="ragLoading ? 'loading' : poolStatus.cls">
              <template v-if="ragLoading">
                <span class="pool-status-emoji">⏳</span>
                <span class="pool-status-label">正在检索候选资料并提取证据…</span>
              </template>
              <template v-else-if="retrieveResult">
                <span class="pool-status-emoji">{{ poolStatus.emoji }}</span>
                <span class="pool-status-label">整体结论：{{ poolStatus.label }}</span>
                <span class="pool-status-note">{{ retrieveResult.reason }}</span>
                <!-- 走的是哪一档知识库：整合资料库优先，命中不了才降级翻原始文献。
                     这一条必须显眼——「为什么这次引用的是 PDF 段落」全靠它解释。 -->
                <span
                  v-if="retrieveResult.tierNote"
                  class="tier-badge"
                  :class="retrieveResult.retrievalTier"
                >{{ retrieveResult.tierNote }}</span>
              </template>
              <template v-else>
                <span class="pool-status-emoji">⚪</span>
                <span class="pool-status-label">知识检索未执行或调用失败。</span>
              </template>
            </div>

            <!-- 意图缺失：找到了背景资料，但没有一条对应用户所问的方面。
                 必须明示，否则管理员会误以为「已检索到证据」，而回答其实是 LLM 凭空组织的。 -->
            <div
              v-if="isClear && !ragLoading && retrieveResult && retrieveResult.intent_missing"
              class="intent-missing"
            >
              ❌ {{ retrieveResult.reason }}
            </div>

            <div v-if="isClear && retrieveResult && retrieveResult.candidates.length" class="pool">
              <div class="pool-head">
                <span class="pool-head-txt">检索结果<span class="pool-head-sub">每行一份资料 · 完整文献收进「查看原文」</span></span>
                <span class="pool-count">共 {{ retrieveResult.candidates.length }} 份</span>
              </div>

              <div class="evi-cards">
                <div v-for="(c, ci) in visibleCandidates" :key="c.source + c.id" class="evi-card">
                  <div class="evi-card-head">
                    <span class="evi-doc">《{{ c.title }}》</span>
                    <span class="evi-doc-meta">
                      <template v-if="c.source === 'papers'">{{ c.journal }} {{ c.year }}</template>
                      <template v-else>知识库 · {{ c.ethnicity || '—' }} {{ c.disease || '' }}</template>
                    </span>
                    <span class="pool-flag" :class="c.status">{{ statusMeta(c.status).emoji }} {{ statusMeta(c.status).label }}</span>
                    <!-- 相关度按排名给（服务端没回传分数），与「默认展示前 5 条」是同一条分界线 -->
                    <span
                      class="rel-chip"
                      :class="relevanceOf(ci).cls"
                      :title="`按相关度排序，这是第 ${ci + 1} 份`"
                    >相关度 {{ relevanceOf(ci).label }}</span>
                    <button class="btn btn-sm btn-soft evi-open-btn" type="button" @click="openFullText(c)">查看原文</button>
                  </div>

                  <div v-if="c.evidence && c.evidence.length" class="evi-quotes">
                    <blockquote
                      v-for="(e, i) in c.evidence"
                      :key="i"
                      class="evi-quote"
                      :title="'点击定位到原文此处'"
                      @click="openFullText(c, i)"
                    >
                      <span v-if="e.topic" class="evi-topic">{{ e.topic }}</span>
                      “<span v-html="quoteHtml(e)"></span>”
                    </blockquote>
                  </div>
                  <div v-else class="pool-evi-empty">这份资料没有能直接回答当前问题的证据</div>
                </div>
              </div>

              <!-- 其余候选折起来：「展开更多」是个显式动作，不看的人不会被十几份资料淹掉 -->
              <button
                v-if="hiddenCandidateCount || candidatesExpanded"
                class="btn btn-sm btn-outline evi-more"
                type="button"
                @click="candidatesExpanded = !candidatesExpanded"
              >{{ candidatesExpanded ? '收起' : `展开更多（另有 ${hiddenCandidateCount} 份）` }}</button>
            </div>
            <div v-else-if="isClear && retrieveResult" class="pool-empty">没有检索到候选资料。</div>

            <div v-show="isClear && retrieveResult && retrieveResult.evidence.length" class="packed">
              <div class="packed-title">下面这些是真正送进回答生成的证据</div>
              <div class="packed-sub">{{ packedMeta }}</div>
              <div v-html="packedTableHtml"></div>
            </div>

            <!-- 查看原文：完整文献在这里展开，并按点击的证据定位高亮 -->
            <el-dialog
              v-model="fullTextVisible"
              :title="fullTextDoc ? `《${fullTextDoc.title}》` : '完整文献'"
              width="72%"
              top="6vh"
              destroy-on-close
            >
              <template v-if="fullTextDoc">
                <!-- 知识库文档：按分区决定展示 PDF 原页还是正文片段。
                     拉正文 / 页数由组件自己负责，所以这里不再预取 -->
                <EvidenceSourceViewer
                  v-if="fullTextDoc.source === 'kb' && fullTextDoc.id"
                  :doc-id="fullTextDoc.id"
                  :title="fullTextDoc.title"
                  :partition="fullTextDoc.partition"
                  :page="pageOfEvidence(fullTextDoc, focusEvidenceIndex)"
                  :evidence="fullTextDoc.evidence || []"
                  :focus-index="focusEvidenceIndex"
                  :preloaded-text="fullTextDoc.fullText || ''"
                />
                <!-- 内置文献（papers.json）：没有 PDF 原页，仍走正文文本 -->
                <DocEvidenceViewer
                  v-else-if="fullTextDoc.fullText || fullTextDoc.content"
                  :full-text="fullTextDoc.fullText || fullTextDoc.content || ''"
                  :evidence="fullTextDoc.evidence || []"
                  :status="fullTextDoc.status"
                  :focus-index="focusEvidenceIndex"
                />
                <div v-else class="full-meta">
                  <div v-if="fullTextDoc.authors" class="pfull-row"><b>作者</b> {{ fullTextDoc.authors }}</div>
                  <div v-if="fullTextDoc.population" class="pfull-row"><b>研究人群</b> {{ fullTextDoc.population }}</div>
                  <div v-if="fullTextDoc.study_type || fullTextDoc.study_year" class="pfull-row"><b>研究设计</b> {{ [fullTextDoc.study_type, fullTextDoc.study_year].filter(Boolean).join(' · ') }}</div>
                  <div v-if="fullTextDoc.findings" class="pfull-row"><b>核心结论</b> {{ fullTextDoc.findings }}</div>
                  <div v-if="fullTextDoc.limitation" class="pfull-row"><b>局限性</b> {{ fullTextDoc.limitation }}</div>
                  <div v-if="fullTextDoc.evidences && fullTextDoc.evidences.length" class="pfull-row">
                    <b>证据片段</b>
                    <div v-for="(ev, i) in fullTextDoc.evidences" :key="i" class="pfull-evi">
                      <span v-if="ev.topic" class="pfull-topic">[{{ ev.topic }}]</span> {{ ev.content }}
                    </div>
                  </div>
                  <div v-if="fullTextDoc.url" class="pfull-row"><b>原文链接</b> <a :href="fullTextDoc.url" target="_blank" rel="noopener">{{ fullTextDoc.url }}</a></div>
                </div>
              </template>
            </el-dialog>
          </div>
        </section>

        <!-- ③ 加工生成 -->
        <section id="sec-process" class="box" :class="{ open: open.process }">
          <button class="box-head" type="button" @click="toggle('process')">
            <span>
              <span class="box-name">加工生成</span><span class="box-tag">组织成可溯源的回答</span>
            </span>
            <svg class="chev" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>
          </button>
          <div class="box-body">
            <div class="note">
              这一步把前面准备好的「问题 + 证据」整理成最终回答：先回到问题本身，再在与查询意图相关的证据里挑出要用的段落，最后整合成有结论、有依据、可点击核对原文的内容。
            </div>

            <div class="materials">
              <div class="material">
                <div class="material-label">材料① · 本次的问题</div>
                <div class="material-body">{{ matQuestion }}</div>
              </div>
              <div class="material">
                <div class="material-label">材料② · 命中的文献</div>
                <div class="material-body">{{ matDoc }}</div>
              </div>
            </div>

            <div class="flow" style="margin-top: 14px">
              <div class="flow-step">
                <span class="flow-num">1</span>
                <span class="flow-txt">
                  明确这次要回答的是：
                  <span class="struct-chip">民族 = {{ ethnic }}</span>
                  <span class="struct-chip">疾病 = {{ disease }}</span>
                  <span class="struct-chip">查询意图 = {{ intent }}</span>
                </span>
              </div>
              <div class="flow-step">
                <span class="flow-num">2</span>
                <span class="flow-txt">在《{{ procDocTitle }}》<template v-if="evidenceCount > 1">等 {{ evidenceCount }} 份资料</template> 中，对照「{{ procTriple }}」定位相关段落，实际可用的是{{ evidenceKind }}。</span>
              </div>
              <div class="flow-step">
                <span class="flow-num">3</span>
                <span class="flow-txt">把问题与证据对照整合，组织成一段「先给结论、再列依据」的回答；下面一并列出用到的证据与原文出处。</span>
              </div>
            </div>

            <div class="answer-box">
              <div class="ab-label">最终回答</div>
              <div class="answer-q">用户问题：<b>{{ q ? q.text : '—' }}</b></div>
              <div class="shot">
                <div class="shot-label">证据材料 · 来自以下资料（点标题可回到知识库原文）</div>
                <div v-if="shotItems.length" class="shot-list">
                  <div v-for="(e, i) in shotItems" :key="i" class="shot-item">
                    <div class="shot-src-row">
                      <button v-if="e.id" class="btn btn-sm btn-link shot-src" type="button" @click="goToDoc(e.id)">《{{ e.title }}》</button>
                      <span v-else class="shot-src-text">《{{ e.title }}》</span>
                      <span v-if="e.page" class="shot-page">第 {{ e.page }} 页</span>
                    </div>
                    <div class="text-evidence">{{ e.fragment }}</div>
                  </div>
                </div>
                <div v-else class="shot-box"><div class="lit-note">{{ shotNote }}</div></div>
              </div>
              <div class="answer-line" v-html="answerLineHtml"></div>
            </div>
          </div>
        </section>
      </div>
    </main>

    <footer class="footer">
      <div class="wrap">
        <span class="foot-brand">多民族特色医学智能体</span>
        <span class="foot-note">产品概念原型 · 演示数据</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; display: flex; flex-direction: column; }

.main { flex: 1; padding: 32px 24px 64px; }
.work { max-width: 880px; margin: 0 auto; }

.page-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
.page-title { font-family: var(--serif); font-weight: 700; font-size: 24px; letter-spacing: 0.5px; }
.page-sub { color: var(--ink-2); font-size: 14.5px; margin-top: 6px; }
/* 按钮形制来自 main.css 的 .btn 分级，这里只留布局补充 */
.back-btn { flex: none; }

.question-bar {
  margin-top: 18px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 12px 16px;
}
/* 追溯模式提示条：明确告诉使用者「现在看的不是前台当前问题」 */
.trace-banner {
  margin-top: 18px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  background: var(--sage-soft); border: 1px solid var(--sage-line); border-radius: var(--r-sm);
  padding: 12px 16px; font-size: 14px; color: var(--ink-2);
}
.trace-banner + .question-bar { margin-top: 10px; }
.tb-icon { flex: none; }
.tb-body { flex: 1 1 320px; min-width: 0; line-height: 1.6; }
.tb-body b { color: var(--ink); }
.tb-sub { display: block; font-size: 12px; color: var(--ink-3); margin-top: 2px; }
.tb-back { flex: none; }
.q-label { font-size: 13px; color: var(--ink-3); flex: none; }
/* 来源标记：与右侧的判定徽章用不同色系，免得两个圆角标签看起来是一回事 */
.q-source {
  font-size: 12px; padding: 3px 11px; border-radius: 999px; flex: none;
  background: var(--bg); color: var(--ink-2); border: 1px solid var(--line-strong);
}
.q-source.search { background: var(--clay-soft); color: var(--clay-deep); border-color: var(--accent-line); }
/* 来源开关：形制沿用个人中心的子 Tab（.subtabs），项目里已有同一套视觉语言 */
.source-switch {
  display: inline-flex; gap: 2px; padding: 2px; flex: none;
  background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-md);
}
.source-opt {
  display: inline-flex; align-items: center; gap: 6px;
  border: 0; background: transparent; cursor: pointer;
  font-family: var(--sans); font-size: 12px; color: var(--ink-3);
  padding: 4px 11px; border-radius: calc(var(--r-md) - 3px); transition: 0.15s;
}
.source-opt:hover { color: var(--ink-2); }
/* 那一侧还没有记录时禁掉：点过去也只会停在原地（展示端会退到有记录的那侧），
   不如明说「这儿现在没东西可看」。圆点同时变灰，两处信号一致。 */
.source-opt:disabled { cursor: default; opacity: 0.55; }
.source-opt.on { background: var(--surface); color: var(--clay-deep); font-weight: 600; box-shadow: var(--shadow-sm); }
/* 有记录的一侧点亮。空的那侧留个灰点而不是把按钮藏掉——
   藏掉会让人以为「高级检索」这个入口不存在，而它只是这次还没跑过。 */
.source-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--sage); flex: none; }
.source-dot.off { background: var(--line-strong); }
.q-text { font-size: 15px; font-weight: 500; }
.q-badge { font-size: 12px; padding: 3px 11px; border-radius: 999px; background: var(--sage-soft); color: var(--sage-deep); border: 1px solid var(--sage-line); }
.q-badge.demo { background: var(--amber-soft); color: var(--amber); border-color: var(--amber-line); }
.q-badge.fail { background: var(--danger-soft); color: var(--danger); border-color: var(--danger-line); }
.q-badge.warn { background: var(--amber-soft); color: var(--amber); border-color: var(--amber-line); }
.reset-btn { margin-left: auto; }

/* 溯源链路 */
.trace { display: flex; align-items: center; gap: 6px; margin-top: 18px; flex-wrap: wrap; }
.trace-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--ink-3); flex: none; }
.trace-node.done .trace-dot { background: var(--sage); }
.trace-node.done { color: var(--sage-deep); border-color: var(--sage-line); }
.trace-node.fail .trace-dot { background: var(--danger); }
.trace-node.fail { color: var(--danger); border-color: var(--danger-line); }
.trace-arrow { color: var(--ink-3); margin-left: 2px; }

/* 方框卡片 */
.box { margin-top: 20px; border: 1px solid var(--line); background: var(--surface); border-radius: var(--r-lg); box-shadow: var(--shadow-sm); overflow: hidden; }
.box-head {
  width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 18px 22px; background: transparent; border: 0; text-align: left; font-family: inherit; cursor: pointer;
}
.box-head:hover { background: var(--line-2); }
.box-name { font-family: var(--serif); font-weight: 700; font-size: 17px; }
.box-tag { font-size: 12px; color: var(--clay-deep); background: var(--clay-soft); padding: 2px 10px; border-radius: 999px; margin-left: 10px; }
.box-tag.blue { color: var(--sage-deep); background: var(--sage-soft); }
.chev { color: var(--ink-3); transition: transform 0.2s; flex: none; }
.box.open .chev { transform: rotate(180deg); }
.box-body { display: none; padding: 4px 22px 22px; border-top: 1px solid var(--line-2); }
.box.open .box-body { display: block; }

/* 问题理解 */
.decomp { display: flex; flex-direction: column; gap: 12px; margin-top: 16px; }
.decomp-row { display: flex; align-items: center; gap: 14px; }
.decomp-key { flex: none; width: 88px; font-size: 14px; color: var(--ink-2); font-weight: 600; }
.decomp-val { flex: 1; background: var(--clay-soft); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 10px 14px; font-size: 15px; font-weight: 600; color: var(--clay-deep); }
.note { margin-top: 16px; font-size: 13px; color: var(--ink-3); background: var(--bg); border: 1px dashed var(--line); border-radius: var(--r-sm); padding: 10px 14px; }
.verdict { margin-top: 14px; font-size: 14.5px; font-weight: 600; padding: 12px 16px; border-radius: var(--r-sm); }
.verdict.idle { color: var(--ink-3); background: var(--bg); border: 1px dashed var(--line); }
.verdict.ok { color: var(--sage-deep); background: var(--sage-soft); border: 1px solid var(--sage-line); }
.verdict.fail { color: var(--danger); background: var(--danger-soft); border: 1px solid var(--danger-line); }
/* 泛化放行：不是失败，但也不是精准命中，用琥珀色单独区分 */
.verdict.warn { color: var(--amber); background: var(--amber-soft); border: 1px solid var(--amber-line); }
.degrade-tip {
  margin-top: 10px; padding: 10px 14px; border-radius: var(--r-sm);
  font-size: 13px; line-height: 1.7;
  color: var(--amber); background: var(--amber-soft); border: 1px solid var(--amber-line);
}
.decomp-val.degraded { color: var(--amber); font-weight: 600; }

/* 流程步骤（问题理解 / 知识检索 共用） */
.flow { display: flex; flex-direction: column; gap: 12px; margin-top: 16px; }
.flow-step { display: flex; gap: 12px; align-items: flex-start; }
.flow-num { flex: none; width: 26px; height: 26px; border-radius: 50%; background: var(--sage-soft); color: var(--sage-deep); font-size: 13px; font-weight: 700; display: flex; align-items: center; justify-content: center; margin-top: 1px; }
.flow-txt { flex: 1; font-size: 14px; color: var(--ink-2); line-height: 1.7; }
.flow-txt b { color: var(--ink); }
.struct-chip { display: inline-block; background: var(--clay-soft); border: 1px solid var(--line); color: var(--clay-deep); border-radius: 8px; padding: 2px 10px; font-size: 13px; font-weight: 600; margin: 0 2px; }

.packed { margin-top: 16px; border: 1px solid var(--sage); background: var(--sage-soft); border-radius: var(--r-sm); padding: 16px 18px; }
.packed-title { font-family: var(--serif); font-weight: 700; font-size: 15.5px; color: var(--sage-deep); }
.packed-sub { font-size: 13px; color: var(--ink-2); margin-top: 4px; }

.lit-table { margin-top: 12px; border-collapse: collapse; width: 100%; max-width: 420px; font-size: 13.5px; }
.lit-table th, .lit-table td { border: 1px solid var(--sage-line); padding: 7px 12px; text-align: center; background: #fff; }
.lit-table th { background: var(--clay-soft); color: var(--ink-2); font-weight: 600; }
.lit-table .hl { background: var(--amber-soft); font-weight: 600; color: var(--clay-deep); }
.lit-note { margin-top: 8px; font-size: 12.5px; color: var(--ink-3); }

/* 知识检索 */
.pool-status-bar {
  margin-top: 16px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 11px 16px; border-radius: var(--r-sm); border: 1px solid var(--line); background: var(--bg);
}
.pool-status-bar.sufficient { border-color: var(--sage-line); background: var(--sage-soft); }
.pool-status-bar.partial { border-color: var(--amber-line); background: var(--amber-soft); }
.pool-status-bar.none { border-color: var(--danger-line); background: var(--danger-soft); }
.pool-status-emoji { font-size: 16px; }
.pool-status-label { font-weight: 700; font-size: 14px; color: var(--ink); }
.pool-status-bar.sufficient .pool-status-label { color: var(--sage-deep); }
.pool-status-bar.partial .pool-status-label { color: var(--amber); }
.pool-status-bar.none .pool-status-label { color: var(--danger); }
.pool-status-note { font-size: 13px; color: var(--ink-2); }
/* 检索档次徽标：整合资料=鼠尾草绿（正常），降级到原文=琥珀（提示这是退而求其次） */
.tier-badge {
  font-size: 12px; padding: 3px 11px; border-radius: 999px; flex: none;
  border: 1px solid var(--line);
}
.tier-badge.integrated { color: var(--sage-deep); background: var(--sage-soft); border-color: var(--sage-line); }
.tier-badge.raw { color: var(--amber); background: var(--amber-soft); border-color: var(--amber-line); }

/* 意图缺失提示：找到了背景资料但不对应所问方面，必须显眼 */
.intent-missing {
  margin-top: 12px; padding: 12px 16px; border-radius: var(--r-sm);
  border: 1px solid var(--danger-line); background: var(--danger-soft);
  color: var(--danger); font-size: 13.5px; line-height: 1.7; font-weight: 500;
}

.pool { margin-top: 16px; border: 1px solid var(--line); border-radius: var(--r-sm); overflow: hidden; }
.pool-head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 12px 16px; background: var(--clay-soft); border-bottom: 1px solid var(--line);
}
.pool-head-txt { font-family: var(--serif); font-weight: 700; font-size: 14.5px; }
.pool-head-sub { font-size: 12px; color: var(--ink-3); font-weight: 400; margin-left: 8px; }
.pool-count { font-size: 12px; color: var(--clay-deep); background: #fff; border: 1px solid var(--line); padding: 2px 10px; border-radius: 999px; }
/* 证据卡片列表：一行一份资料，完整文献收进「查看原文」弹窗 */
.evi-cards { display: flex; flex-direction: column; gap: 12px; }
/* 展开更多：贴在候选列表下方、左对齐，与卡片同一列 */
.evi-more { margin-top: 12px; }
.evi-card { border: 1px solid var(--line); background: var(--surface); border-radius: var(--r-sm); padding: 14px 16px; }
.evi-card-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.evi-doc { font-family: var(--serif); font-weight: 700; font-size: 14px; color: var(--ink); }
.evi-doc-meta { font-size: 12px; color: var(--ink-3); }
.evi-card-head .pool-flag { margin-bottom: 0; }
.evi-open-btn { margin-left: auto; }
.evi-quotes { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.evi-quote {
  margin: 0; padding: 9px 13px; border-left: 3px solid var(--accent-line); background: var(--bg);
  border-radius: 0 8px 8px 0; font-size: 13.5px; line-height: 1.8; color: var(--ink-2);
  cursor: pointer; transition: background 0.15s;
}
.evi-quote:hover { background: var(--clay-soft); }
/* v-html 注入的 <mark> 带不上 scope 属性，必须用 :deep 才能命中 */
.evi-quote :deep(mark) { background: rgba(255, 214, 102, 0.42); color: inherit; padding: 0 1px; border-radius: 3px; }
.evi-topic { font-size: 11.5px; color: var(--ink-3); margin-right: 6px; }
.full-meta { font-size: 13.5px; color: var(--ink-2); line-height: 1.85; }
.pfull-row { margin-bottom: 8px; }
.pfull-row:last-child { margin-bottom: 0; }
.pfull-row b { color: var(--ink); font-weight: 600; }
.pfull-evi { margin-top: 4px; padding-left: 8px; border-left: 2px solid var(--line); }
.pfull-topic { color: var(--clay-deep); font-weight: 600; }
.pool-doc-full a { color: var(--clay); word-break: break-all; }
.pool-flag { display: inline-block; font-size: 12.5px; font-weight: 600; padding: 2px 10px; border-radius: 999px; margin-bottom: 8px; }
.pool-flag.sufficient { color: var(--sage-deep); background: var(--sage-soft); border: 1px solid var(--sage-line); }
.pool-flag.partial { color: var(--amber); background: var(--amber-soft); border: 1px solid var(--amber-line); }
.pool-flag.none { color: var(--danger); background: var(--danger-soft); border: 1px solid var(--danger-line); }
/* 相关度标签：按排名给的三档，与「默认展示前 5 条」是同一条分界线 */
.rel-chip { font-size: 11.5px; padding: 2px 9px; border-radius: 999px; border: 1px solid var(--line); white-space: nowrap; }
.rel-chip.high { color: var(--sage-deep); background: var(--sage-soft); border-color: var(--sage-line); }
.rel-chip.mid { color: var(--amber); background: var(--amber-soft); border-color: var(--amber-line); }
.rel-chip.low { color: var(--ink-3); background: var(--bg); border-color: var(--line); }
.pool-evi-list { display: flex; flex-direction: column; gap: 8px; }
.pool-evi-item { font-size: 13.5px; color: var(--ink-2); line-height: 1.8; background: #fff; border-left: 3px solid var(--clay); padding: 8px 12px; border-radius: 0 6px 6px 0; }
.pool-evi-empty { font-size: 13px; color: var(--ink-3); }
.pool-empty { margin-top: 16px; font-size: 13px; color: var(--ink-3); border: 1px dashed var(--line); border-radius: var(--r-sm); padding: 12px 14px; }

/* 加工生成 */
.materials { margin-top: 14px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.material { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--bg); padding: 12px 14px; }
.material-label { font-size: 12px; color: var(--ink-3); letter-spacing: 1px; margin-bottom: 6px; }
.material-body { font-size: 14px; font-weight: 500; color: var(--ink); word-break: break-all; }

.shot {
  margin-top: 14px; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--bg);
  padding: 12px 14px; display: block;
}
.shot-label { font-size: 12px; color: var(--ink-3); letter-spacing: 1px; margin-bottom: 8px; }
.shot-box { border: 1px solid var(--line); background: #fff; border-radius: 6px; padding: 6px; box-shadow: var(--shadow-sm); }
.shot-box .lit-table { margin: 0; }
.shot-list { display: flex; flex-direction: column; gap: 12px; }
.shot-item { display: flex; flex-direction: column; gap: 6px; }
.shot-src-row { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; }
.shot-src { font-weight: 600; font-size: 13.5px; }
.shot-src-text { font-weight: 600; color: var(--ink-2); font-size: 13.5px; }
.shot-page { font-size: 11.5px; color: var(--ink-3); }
.text-evidence { font-size: 14px; color: var(--ink-2); line-height: 1.9; background: #fff; border-left: 3px solid var(--clay); padding: 10px 14px; border-radius: 0 6px 6px 0; }

.answer-box {
  margin-top: 18px; border: 1px solid var(--clay); background: #fff; border-radius: var(--r-sm);
  padding: 18px 20px; box-shadow: var(--shadow-sm);
}
.answer-box .ab-label { font-size: 12px; color: var(--clay-deep); letter-spacing: 2px; font-weight: 600; margin-bottom: 10px; }
.answer-q { font-size: 14px; color: var(--ink-2); margin-bottom: 12px; }
.answer-q b { color: var(--ink); }
.answer-line { font-size: 15px; color: var(--ink); line-height: 1.8; margin-top: 14px; }
.answer-line .key { color: var(--clay-deep); font-weight: 700; }
.answer-line :deep(.answer-text) { color: var(--ink); }
.answer-line :deep(.ans-sec) { margin-top: 12px; }
.answer-line :deep(.ans-sec-title) { font-weight: 700; color: var(--ink); margin-bottom: 4px; font-size: 13.5px; }
.answer-line :deep(.ans-sec-body) { color: var(--ink-2); font-size: 13.5px; }
.answer-line :deep(.ans-table) { margin-top: 12px; border-collapse: collapse; width: 100%; max-width: 520px; font-size: 13px; }
.answer-line :deep(.ans-table th), .answer-line :deep(.ans-table td) { border: 1px solid var(--sage-line); padding: 6px 12px; text-align: center; background: #fff; }
.answer-line :deep(.ans-table th) { background: var(--clay-soft); color: var(--ink-2); font-weight: 600; }
.answer-line :deep(.ans-chart) { margin-top: 12px; }
.answer-line :deep(.ans-chart-title) { font-weight: 600; color: var(--ink); margin-bottom: 10px; font-size: 13.5px; }
.answer-line :deep(.ans-bar-row) { display: flex; align-items: center; gap: 10px; margin-bottom: 7px; }
.answer-line :deep(.ans-bar-label) { flex: none; width: 120px; font-size: 12.5px; color: var(--ink-2); text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.answer-line :deep(.ans-bar-track) { flex: 1; background: var(--bg); border-radius: 6px; height: 14px; overflow: hidden; }
.answer-line :deep(.ans-bar-fill) { height: 100%; background: var(--clay); border-radius: 6px; }
.answer-line :deep(.ans-bar-val) { flex: none; font-size: 12.5px; font-weight: 600; color: var(--clay-deep); min-width: 52px; }
.answer-line :deep(.ans-note) { margin-top: 12px; font-size: 12.5px; color: var(--ink-3); background: var(--bg); border-radius: var(--r-sm); padding: 8px 12px; line-height: 1.7; }
.answer-line :deep(.ans-note.warn) { color: var(--danger); background: var(--danger-soft); }
.answer-line :deep(.ans-trio) { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 12px; }
.answer-line :deep(.ans-card) { flex: 1 1 200px; min-width: 180px; background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; }
.answer-line :deep(.ans-card-title) { font-size: 12px; font-weight: 700; color: var(--clay-deep); letter-spacing: 0.02em; margin-bottom: 6px; }
.answer-line :deep(.ans-card-body) { font-size: 13px; color: var(--ink-2); line-height: 1.7; }
.answer-line :deep(.ans-list) { margin: 8px 0 4px; padding-left: 22px; }
.answer-line :deep(.ans-list li) { margin-bottom: 4px; }

/* 底栏 */
.footer { border-top: 1px solid var(--line); background: var(--surface); }
.footer .wrap { height: 76px; display: flex; align-items: center; justify-content: space-between; }
.foot-brand { font-family: var(--serif); font-weight: 700; font-size: 15px; letter-spacing: 0.5px; color: var(--ink-2); }
.foot-note { font-size: 12.5px; color: var(--ink-3); }
</style>
