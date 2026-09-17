<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, reactive, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useAnalysisStore } from '@/stores/analysis'
import type { AnalysisRecord } from '@/stores/analysis'
import { chatFeedback, gapSlotKey, myFeedbackSlots, DISEASE_UNSPECIFIED } from '@/api/gap'
import { extractSlots } from '@/utils/medicalVocab'
import { understand, intentLabel } from '@/api/qa'
import type { Understanding, GenerateResult, GenerateAnswer, IntentCode, KnowledgeDoc, TurnContext, CandidateEvidence, StoredQuestion, EvidencePoolResult } from '@/api/qa'
import {
  appendMessage,
  createConversation,
  deleteConversation as deleteConversationApi,
  getConversation,
  getLastConversation,
  listConversations,
  renameConversation as renameConversationApi,
} from '@/api/chat'
import type { Conversation, ConversationDetail, ChatMessage } from '@/api/chat'
import { createDynamic, listMyDynamics } from '@/api/dynamic'
import { addConversationFavorite, listConversationFavoriteIds, removeConversationFavorite } from '@/api/favorite'
import AppTopbar from '@/components/AppTopbar.vue'
/** 等待回答时的三步进度。与高级检索共用同一个组件，保证两个入口样式一致 */
import AnswerProgress from '@/components/AnswerProgress.vue'
// 溯源弹窗按分区决定展示 PDF 原页还是正文片段，问答页与后台共用同一个组件
import EvidenceSourceViewer from '@/components/EvidenceSourceViewer.vue'
import type { KbPartition } from '@/api/kb'
import SourceBadge from '@/components/SourceBadge.vue'
import { highlightTerms } from '@/utils/highlight'
import { esc } from '@/utils/markdown'
import { groupByTime } from '@/utils/timeGroup'
import { structuredAnswerHtml } from '@/utils/answerHtml'
import { normalizeCitations, packDynamic } from '@/utils/shareConversation'
import type { ShareTurn } from '@/utils/shareConversation'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const analysisStore = useAnalysisStore()

// keep-alive 缓存用：组件名需与 App.vue 中 <keep-alive include="QaView"> 匹配
defineOptions({ name: 'QaView' })

// ============================================================
// 我的对话逻辑：验证（/api/understand）→ 直接回答（知识库渲染）
// ============================================================
const QKEY = 'mmx_question_v2'
const LIT_KEY = 'mmx_kb_lit_v2'

const ETHNICS = ['白族', '傣族', '哈尼族', '汉族', '苗族', '彝族', '维吾尔族', '藏族', '回族', '蒙古族', '朝鲜族', '景颇族', '傈僳族', '佤族', '普米族', '布朗族', '纳西族', '土家族', '满族', '布依族', '壮族', '畲族', '哈萨克族']
const DISEASES = ['糖尿病', '高血压', '代谢综合征', 'CKM', '心脏瓣膜病', '非酒精性脂肪性肝病', 'NAFLD', '肥胖', '乙型肝炎', '乙肝', '慢性肾病']
// 关键词 → 英文意图码（API 不可用时的降级方案）
const INTENT_RULES: { code: IntentCode; keywords: string[] }[] = [
  { code: 'prevalence', keywords: ['患病率', '发病率', '检出率', '患病', '流行', '现状', '患病情况'] },
  { code: 'risk', keywords: ['影响因素', '危险因素', '保护因素', '风险', '病因', '相关因素'] },
  { code: 'diet', keywords: ['饮食', '膳食', '吃什么', '乳扇', '营养', '食物', '脂肪酸'] },
  { code: 'genetics', keywords: ['基因', '遗传', '多态性', '易感', '突变', '位点'] },
  { code: 'overview', keywords: ['研究', '概况', '知晓率', '控制率', '自我管理'] },
]

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
  ethnic?: string
  disease?: string
  table?: LitTable
}

function readQuestion(): StoredQuestion | null {
  try {
    const raw = localStorage.getItem(QKEY)
    return raw ? (JSON.parse(raw) as StoredQuestion) : null
  } catch {
    return null
  }
}

function writeQuestion(q: StoredQuestion) {
  try {
    localStorage.setItem(QKEY, JSON.stringify(q))
  } catch {
    /* ignore */
  }
}

function clearQuestion() {
  try {
    localStorage.removeItem(QKEY)
  } catch {
    /* ignore */
  }
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

function firstMatch(text: string, list: string[]): string {
  for (const item of list) if (text.indexOf(item) >= 0) return item
  return ''
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

function findValue(l: LiteratureItem, ethnic: string): string {
  if (l.table && l.table.rows) {
    for (const row of l.table.rows) if (row[0] === ethnic) return row[1] || ''
  }
  return ''
}

// ============================================================
// 状态
// ============================================================
const input = ref('')
const btnState = ref<'verify' | 'ok' | 'busy'>('verify')
const toast = reactive({ visible: false, text: '', warn: false })

/**
 * 单飞锁：同一时刻只允许一个问题在跑。
 *
 * **这是一道正确性防线，不只是防重复提交**。`analysisStore` 是单例，
 * 两条请求会互相覆盖它的 `pool` / `generate`，随后 `renderAnswerFromRag`
 * 从那个共享 store 读数据——结果是 A 问题的卡片里渲染出 B 问题的答案。
 * 所以锁必须加在**入口**上（verify 是推荐问题、追问胶囊、发送键、回车键
 * 四条路径的唯一汇聚点），光禁用按钮挡不住回车键。
 *
 * 不复用 btnState 是因为它会被 onInput 改成 'verify'（用户边等边打字时），
 * 那样锁就自己开了。
 */
const isGenerating = ref(false)

/**
 * 本轮提问的文字——**乐观渲染**用。
 *
 * 点下发送就把它挂到对话流末尾，不等后端返回。用户立刻看到「问题已经发出去了」，
 * 而不是盯着输入框里的原文怀疑到底发没发。回答到达后它被清空，
 * 由真正的 turn 接管显示，位置不变。
 */
const pendingQuestion = ref('')

/** 底部输入框：生成结束后把光标送回去，用户可以直接接着问下一句 */
const inputEl = ref<HTMLInputElement | null>(null)

/**
 * 分步进度：0=理解 1=检索 2=组织。
 *
 * **由真实 await 边界驱动，不是定时器**。此前这里是个 2 秒一跳的 setInterval，
 * 它和真实进度脱钩：实测「检索」那步有时要 8 秒，定时器第 3 秒就跳到「组织回答」了。
 */
const aiStep = ref(0)

/**
 * 本轮是否生成失败——失败时进度块整块换成「生成失败，请重试」。
 *
 * 判定放在 send() 里：store 的 load() 内部自己吞掉了异常，失败表现为
 * `pool` 与 `generate` 双双为 null（详见 send 里的注释）。
 */
const genFailed = ref(false)

/**
 * 检索 / 生成两个阶段来自 store。
 *
 * `analysisStore.load()` 内部连续 await 了 evidence-pool 与 generate，
 * 但从 send() 的视角只是「一个 await」——不订阅这个字段就分不出检索完成没完成。
 */
watch(
  () => analysisStore.records.dialogue.stage,
  (stage) => {
    if (stage === 'retrieve') aiStep.value = 1
    else if (stage === 'generate') aiStep.value = 2
  },
)

/**
 * 生成期间拒绝切换会话。
 *
 * 选「禁止切换」而不是「取消在飞的请求」：取消会让用户切回来时看到一个
 * 残缺的回答，还得解释为什么；而且取消要改 API 层与 store 的签名。
 * 但也不能切了不管——在飞的请求完成后会走 ensureConversation，
 * 把这一问一答写进**切换后**的那个会话里，造成串号。
 */
function warnBusySwitch() {
  ElMessage.warning('正在生成回答，请稍候再切换会话')
}

/** 回答卡片里的一条证据片段（含来源论文 id，供点击跳原文） */
interface EvidenceRef {
  id: string
  title: string
  fragment: string
  doi?: string | null
  source?: string
  /** 片段在文档全文中的起止字符区间；-1 或缺省表示定位不到（如论文证据没有本地全文） */
  startOffset?: number
  endOffset?: number
  page?: string | number | null
  /** 命中的关键词（长词在前），用于在片段里标出「为什么这条被检索到」 */
  matchedTerms?: string[]
  /** 来源等级（一级分类）：official / web_crawl / user_upload；缺省视为官方 */
  sourceLevel?: string
  /** 来源机构：国家卫健委 / 人民日报 / 用户上传 … */
  sourceOrg?: string | null
  /**
   * 知识库分区：`raw` 的溯源展示 PDF 原页（按上面的 page 定位），
   * `integrated` 展示正文片段并高亮。缺省按整合资料处理。
   */
  partition?: KbPartition
}

const answer = reactive({
  visible: false,
  q: '',
  evidenceItems: [] as EvidenceRef[],
  evidenceNote: '',
  conclusionHtml: '',
  followUps: [] as string[],
  /** 这次没检索到任何证据（决定要不要给「反馈此问题」入口） */
  missed: false,
  /** 找到了背景资料，但没有一条对应所问的方面（也是「没答到点子上」） */
  intentMissing: false,
  /** 检索层信号都没触发，但回答正文自己说了「未覆盖」（对比类问题的典型） */
  notCovered: false,
  /** 命中医疗安全边界（用药/诊疗咨询）：这种「未命中」不该变成知识缺口 */
  blocked: false,
})

/** 一次问答回合：把本会话的多轮问答累积成对话线程展示 */
interface Turn {
  question: string
  ethnic: string
  disease: string
  /** 本轮提问的意图码。Turn 原本不带它，而登记知识缺口要用到（意图在 StoredQuestion 上） */
  intent?: string
  evidenceItems: EvidenceRef[]
  evidenceNote: string
  conclusionHtml: string
  followUps: string[]
  /** 本轮未命中（未检索到任何证据） */
  missed?: boolean
  /** 本轮找到了背景资料但不对应所问的方面 */
  intentMissing?: boolean
  /** 检索层信号都没触发，但回答正文自己说了「未覆盖」 */
  notCovered?: boolean
  /** 本轮命中医疗安全边界（用药/诊疗咨询），不提供反馈入口 */
  blocked?: boolean
  /** 落库后的服务端消息 ID 与完整响应，供「分享至动态」按来源消息溯源 */
  messageId?: number
  plain?: string
  detail?: Record<string, any> | null
}

/** 本会话实时对话线程：每回答一问追加一轮，保留前面所有问答 */
const turns = ref<Turn[]>([])

/** 哪些回答卡片的「依据文献」被展开了（key：实时回合用序号，历史回放用 "h"+序号） */
const expandedEvidence = reactive(new Set<string | number>())

function toggleEvidence(key: string | number) {
  if (expandedEvidence.has(key)) expandedEvidence.delete(key)
  else expandedEvidence.add(key)
}

// ---------- 证据原文弹窗（点证据片段 → 打开原文并定位高亮） ----------
const evidenceDocVisible = ref(false)
const evidenceDocTitle = ref('')
/** 证据所属的知识库文档 id；空串 = 这条证据没有可定位的原文（如内置文献条目） */
const evidenceDocId = ref('')
/** 文档分区：决定弹窗里展示 PDF 原页还是正文片段（判断在 EvidenceSourceViewer 里） */
const evidenceDocPartition = ref<KbPartition | undefined>(undefined)
/** 证据所在页码，PDF 直接翻到那一页 */
const evidenceDocPage = ref<number | null>(null)
const evidenceDocItems = ref<CandidateEvidence[]>([])
const evidenceFocusIndex = ref<number | null>(null)

/** 该片段能否定位到原文（有文档 id 且后端算出了偏移量） */
function canLocate(e: EvidenceRef): boolean {
  return !!e.id && typeof e.startOffset === 'number' && e.startOffset >= 0
}

/**
 * 证据片段渲染：**先转义，再把命中词标成 `<mark>`**。
 * 顺序不能反——先插标签再转义会把自己的标签也转掉，不转义直接插就是 XSS。
 */
function evidenceHtml(e: { fragment: string; matchedTerms?: string[] }): string {
  return highlightTerms(esc(e.fragment), e.matchedTerms)
}

/**
 * 打开证据原文：拉取该文献全文，并把同一份文献的片段一并传进去，
 * 由 DocEvidenceViewer 定位到被点击的那一条并高亮。
 */
function openEvidenceDoc(items: EvidenceRef[], index: number) {
  const target = items[index]
  if (!target || !canLocate(target)) return
  // 同一条回答里属于同一份文献的片段一起传，方便在原文里逐条跳转
  const sameDoc = items.filter((x) => x.id === target.id)
  const focus = sameDoc.indexOf(target)
  const at = focus >= 0 ? focus : 0
  evidenceDocItems.value = sameDoc.map((x) => ({
    fragment: x.fragment,
    startOffset: x.startOffset,
    endOffset: x.endOffset,
    page: (x.page ?? null) as number | null,
  }))
  evidenceFocusIndex.value = at
  evidenceDocTitle.value = `《${target.title}》`
  evidenceDocId.value = String(target.id)
  evidenceDocPartition.value = target.partition
  evidenceDocPage.value = (sameDoc[at]?.page ?? null) as number | null
  evidenceDocVisible.value = true
}

/** 折叠状态下展示的文献名：最多列两个，其余用「等」收尾 */
function evidenceNames(items: { title: string }[]): string {
  const names = items.map((e) => `《${e.title}》`)
  if (names.length <= 2) return names.join('、')
  return `${names.slice(0, 2).join('、')} 等 ${names.length} 篇`
}

// ---------- 整段对话的分享 / 收藏 ----------
// 单位是**整个会话**：把当前对话里的所有问答打包成一条动态。
// 早先是「单条回答」粒度，但会话续接做出来之后，一个会话天然就是一段连续对话，
// 逐条分享会让别人只看到半截上下文（还得靠标题里拼「源自：…」补救）。
// 原实现见 [[社交模块合并完成]] 那一轮的 turnTitle / ensureDynamic。

/** 一条可分享的问答 */
/** 当前会话已转存的那条动态（null = 还没分享过） */
const sharedDynamicId = ref<number | null>(null)
/** 该动态是否已被我收藏 */
const convFavorited = ref(false)
const sharing = ref(false)
const favoring = ref(false)

/**
 * 把当前展示的问答整理成分享载荷。
 *
 * 直接取自合并后的 thread —— 它会同时包含历史回放和本会话新增的回合，
 * 所以「打开旧会话追问几句再分享」得到的是完整对话，而不是只有新问的那半截。
 */
function buildShareTurns(): ShareTurn[] {
  // html 为空表示这一问没有回答记录，没什么可分享的
  return thread.value.filter((t) => t.html).map((t) => {
    const evidence = t.evidence || []
    return {
      question: t.question || '',
      answer: t.detail?.answer || null,
      plain: t.plain || '',
      // 优先用落库时存的 citations（含 doc id 与偏移量，详情页能溯源到原文）；
      // 实时回合在落库完成前 detail 还是空的，退回用界面上已有的证据片段。
      citations: t.detail?.citations?.length
        ? normalizeCitations(t.detail.citations)
        : normalizeCitations(
            evidence.map((e) => ({
              title: e.title,
              quote: e.fragment,
              id: e.id,
              startOffset: e.startOffset,
              endOffset: e.endOffset,
              matchedTerms: e.matchedTerms,
              sourceLevel: e.sourceLevel,
              sourceOrg: e.sourceOrg,
              // 分区与页码一并落库：分享出去的卡片点依据时，也要能按分区展示 PDF 原页
              partition: e.partition,
              page: e.page,
            })),
          ),
    }
  })
}

/**
 * 回显两个按钮的状态——**彼此独立**。
 *
 * 收藏（私有书签）与发布（公开动态）是两回事，状态也来自两个不同的接口：
 *   已收藏  ← GET /favorites/conversations/ids
 *   已发布  ← GET /dynamics/mine（按 sourceConversationId 匹配）
 * 早先两者共用「先转存动态再收藏」的路径，于是点收藏会连带把按钮改成「更新动态」。
 */
async function loadConvShareState() {
  if (activeId.value == null) {
    sharedDynamicId.value = null
    convFavorited.value = false
    return
  }
  const cid = activeId.value
  const [favRes, mineRes] = await Promise.allSettled([
    listConversationFavoriteIds(),
    listMyDynamics(100),
  ])
  // 两个请求各自独立处理失败，不让一个的异常把另一个的状态也清掉
  convFavorited.value = favRes.status === 'fulfilled' && favRes.value.includes(cid)
  const mine = mineRes.status === 'fulfilled' ? mineRes.value : []
  sharedDynamicId.value = mine.find((d) => d.sourceConversationId === cid)?.id ?? null
}

/**
 * 打包当前对话成动态载荷。
 * 后端按会话幂等，且重复发布**刷新**已有动态——用户分享后常会继续追问，
 * 不刷新的话公开出去的内容会永远停在第一次分享那一刻。
 * 打包规则与个人中心的「分享」按钮共用（utils/shareConversation.ts）。
 */
async function publishConversation(title: string) {
  const list = buildShareTurns()
  if (!list.length) {
    ElMessage.warning('这段对话还没有可分享的内容')
    return null
  }
  if (activeId.value == null) {
    ElMessage.warning('对话尚未保存到服务器，请稍候再试')
    return null
  }
  return createDynamic({
    conversationId: activeId.value,
    // 整段对话没有单条消息来源，后端改用会话作为幂等键
    messageId: null,
    ...packDynamic(list, title),
  })
}

/**
 * 分享至动态。两种情境分开处理：
 *   首次发布 → 弹输入框让用户确认标题（默认取第一个问题），提示「发布成功」
 *   已发布过 → 先问「是否更新？」，确认后把最新对话同步过去
 *
 * **锁必须在函数入口就上**，不能等弹窗关闭后才设——否则弹窗打开期间按钮仍可点，
 * 连点会弹出多个确认框，每个都确认后并发发出请求，撞上后端「先查后插」的竞态。
 */
async function addConversationToDynamic() {
  if (sharing.value) return
  sharing.value = true
  try {
    if (sharedDynamicId.value != null) {
      try {
        await ElMessageBox.confirm(
          '您已发布过此对话，是否用当前最新内容更新它？',
          '更新动态',
          { type: 'info', confirmButtonText: '更新', cancelButtonText: '取消' },
        )
      } catch {
        return
      }
      const d = await publishConversation('')
      if (!d) return
      ElMessage.success('动态已更新为最新对话')
      return
    }

    // 首次发布：让用户确认标题
    const firstQuestion = buildShareTurns()[0]?.question || '对话分享'
    let title: string
    try {
      const res = await ElMessageBox.prompt('为这条动态起个标题', '分享至动态', {
        inputValue: firstQuestion,
        confirmButtonText: '发布',
        cancelButtonText: '取消',
        inputValidator: (v) => (v && v.trim() ? true : '标题不能为空'),
      })
      title = res.value
    } catch {
      return
    }
    const d = await publishConversation(title)
    if (!d) return
    sharedDynamicId.value = d.id
    ElMessage.success('发布成功，可在「动态」中查看')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    sharing.value = false
  }
}

/**
 * 收藏 / 取消收藏整段对话。
 *
 * **只写私有书签，不会创建动态**——早先后端没有「收藏对话」这个概念，
 * 只能先转存成动态再收藏，于是点收藏会把内容公开出去，两个按钮的状态也绑在了一起。
 */
async function toggleConversationFavorite() {
  if (activeId.value == null) {
    ElMessage.warning('对话尚未保存到服务器，请稍候再试')
    return
  }
  favoring.value = true
  try {
    if (convFavorited.value) {
      await removeConversationFavorite(activeId.value)
      convFavorited.value = false
      ElMessage.success('已取消收藏')
    } else {
      await addConversationFavorite(activeId.value)
      convFavorited.value = true
      ElMessage.success('已收藏，可在「个人中心 - 我的收藏」查看')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    favoring.value = false
  }
}

function showToast(msg: string, warn = false) {
  toast.text = msg
  toast.warn = warn
  toast.visible = true
}

function store(
  text: string,
  ethnic: string,
  disease: string,
  intent: string,
  status: string,
  conf: number | null,
  questionType?: string,
  standardized?: string,
  intentText?: string,
  medicalAdvice?: boolean,
) {
  const o: StoredQuestion = { text, ethnic, disease, intent, status, ts: Date.now() }
  if (conf != null) o.confidence = conf
  if (questionType) o.questionType = questionType
  if (standardized) o.standardized = standardized
  if (intentText) o.intentText = intentText
  if (medicalAdvice) o.medicalAdvice = true
  writeQuestion(o)
}

function hideAnswer() {
  answer.visible = false
}

/** 滚动容器：贴底判断和「回到最新」都挂在它身上 */
const scrollerEl = ref<HTMLElement | null>(null)
/** 用户此刻是否停在底部附近 */
const atBottom = ref(true)
/** 距底部多少像素以内算「贴底」 */
const STICK_THRESHOLD = 80

function onScrollerScroll() {
  const el = scrollerEl.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_THRESHOLD
}

function scrollToBottom() {
  const el = scrollerEl.value
  if (!el) return
  el.scrollTop = el.scrollHeight
  atBottom.value = true
}

/**
 * 贴底才自动跟随。
 *
 * 量位置必须在 DOM 更新**之前**：此刻 scrollHeight 还是旧值，量到的才是用户
 * 真实的阅读位置。用户正往上翻看前面的回答时，新回答不把他拽回底部
 * （改由「回到最新」按钮让他自己决定），否则读到一半就被打断。
 */
function scrollToAnswer() {
  const el = scrollerEl.value
  if (!el) return
  const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  atBottom.value = distanceFromBottom < STICK_THRESHOLD
  if (!atBottom.value) return
  nextTick(() => {
    el.scrollTop = el.scrollHeight
    atBottom.value = true
  })
}

// ============================================================
// 关键词规则匹配（API 不可用时的降级方案）
// ============================================================
function matchIntent(text: string): string {
  for (const r of INTENT_RULES) for (const k of r.keywords) if (text.indexOf(k) >= 0) return r.code
  return ''
}

function keywordDecompose(text: string) {
  return {
    ethnic: firstMatch(text, ETHNICS),
    disease: firstMatch(text, DISEASES),
    intent: matchIntent(text),
  }
}

function pass(
  text: string,
  ethnic: string,
  disease: string,
  intent: string,
  conf: number | null,
  questionType?: string,
  standardized?: string,
  intentText?: string,
  medicalAdvice?: boolean,
) {
  btnState.value = 'ok'
  store(text, ethnic, disease, intent, 'clear', conf, questionType, standardized, intentText, medicalAdvice)
}

function fail(text: string, ethnic: string, disease: string, intent: string, missing?: string[]) {
  btnState.value = 'verify'
  // 被拒绝的提问从来没进过对话流，输入框又已经在 verify() 里乐观清空了——
  // 把原文还回去，用户只需补一句缺的，不用整段重打。
  input.value = text
  // 按实际缺什么给提示：说清楚缺哪一项，比笼统的「民族 + 疾病」有用
  const lacked = missing && missing.length ? missing : ethnic ? [] : ['ethnicity']
  let msg = '无法理解：请补充「民族 + 疾病」，例如：白族糖尿病患病率如何'
  if (lacked.includes('ethnicity')) {
    msg = '还没听出是哪个民族，请补充民族，例如：白族糖尿病患病率如何'
  } else if (lacked.includes('disease')) {
    msg = '还没听出您想了解哪种疾病，请补充，例如：白族糖尿病患病率如何'
  }
  showToast(msg, true)
  store(text, ethnic, disease, intent, 'incomplete', null)
}

function keywordVerify(text: string) {
  const r = keywordDecompose(text)
  if (r.ethnic && r.disease) {
    pass(text, r.ethnic, r.disease, r.intent || 'overview', null)
    send()
  } else {
    fail(text, r.ethnic, r.disease, r.intent)
  }
}

/**
 * 收集最近几轮的「问题 + 已识别实体」，随后发给 /api/understand。
 *
 * 衍生追问常常省略疾病名（「有没有适合白族人的日常血糖监测方法」），后端只有拿到
 * 上下文才能把「糖尿病」继承下来，否则会被判成「缺疾病」而拒绝回答。
 */
function buildContext(): TurnContext[] {
  const ctx: TurnContext[] = []
  // 已回放的历史会话（user 消息带问题，紧随其后的 assistant 消息带当轮理解结果）
  for (const m of historyMessages.value as any[]) {
    if (m.role === 'user') {
      ctx.push({ question: m.content })
    } else {
      const u = m.detail && m.detail.understanding
      const last = ctx[ctx.length - 1]
      if (last && u) {
        last.ethnicity = u.ethnicity
        last.disease = u.disease
      }
    }
  }
  // 本会话已完成的回合
  for (const t of turns.value) {
    ctx.push({ question: t.question, ethnicity: t.ethnic, disease: t.disease })
  }
  return ctx.slice(-4)
}

/**
 * 所有提问路径的唯一入口（推荐问题 / 追问胶囊 / 发送键 / 回车键）。
 * 只负责上锁、分步提示与开锁，真正的流程在 runVerify 里。
 */
async function verify() {
  if (isGenerating.value) {
    // 用 ElMessage 而不是自绘的 toast：那个 toast 只被置 true、从不置 false
    // （见 showToast），挂上去就不会自己走，一条警告会永远留在输入框上方。
    ElMessage.warning('正在生成回答，请稍候')
    return
  }
  const text = input.value.trim()
  if (!text) {
    showToast('请先输入问题', true)
    return
  }

  // 乐观更新：先清空输入框、把提问气泡挂到对话流末尾，再去等后端。
  // 被校验拒绝时会由 fail() 把文字回填到输入框，用户不用重打。
  input.value = ''
  pendingQuestion.value = text

  isGenerating.value = true
  // 进度从「理解」开始；上一轮的失败态要在这里清掉，否则新一轮一上来就显示失败
  aiStep.value = 0
  genFailed.value = false
  try {
    await runVerify(text)
  } finally {
    // 放在 finally：understand / load 抛错时锁也必须释放，否则页面永久卡死
    isGenerating.value = false
    // 失败时**保留**提问气泡：进度块就挂在它下面，清空的话失败提示会跟着一起消失。
    // 回答区那边还会出一张「这次没能生成回答…」的兜底卡片，两处都提示。
    if (!genFailed.value) pendingQuestion.value = ''
    // 生成期间输入框是禁用的，光标会掉到 body 上；这里送回输入框，方便接着追问
    nextTick(() => inputEl.value?.focus())
  }
}

async function runVerify(text: string) {
  // 上下文要在追加本轮之前抓下来：此时 historyMessages 与 turns 里都是「之前几轮」，
  // 正是后端补全追问所需。historyMessages **不再清空**——
  // 它和 turns 现在由 thread 合并成一条列表渲染，清掉会让用户正在读的历史当场消失。
  const ctx = buildContext()
  btnState.value = 'busy'

  let r: Understanding | null = null
  try {
    r = await understand(text, ctx)
  } catch {
    r = null
  }
  // 理解阶段到此结束（失败降级成关键词匹配也算结束），进度推进到「检索知识库」
  aiStep.value = 1

  if (r && r.status) {
    const ethnic = r.ethnicity || ''
    const disease = r.disease || ''
    const intent = r.intent || r.query_intent || ''
    const conf = typeof r.confidence === 'number' ? r.confidence : null
    const questionType = r.question_type || ''
    const standardized = r.standardized_question || ''
    // 放行条件：有民族，且（识别出疾病 或 后端明确标记为泛化放行）。
    // 只写「白族」这种缺疾病又缺意图的提问仍然要被挡住并提示补充，
    // 否则会被静默当成泛化检索，答非所问。
    // 医疗安全拦截：用药/诊疗咨询不要求民族或疾病，直接放行进「拒绝」分支
    // （后端在 /api/evidence-pool 与 /api/generate 里返回固定话术、不做检索）
    if (r.status !== 'fail' && r.medical_advice) {
      pass(text, ethnic, disease, intent, conf, questionType, standardized, undefined, true)
      await send()
    } else if (r.status !== 'fail' && ethnic && (disease || r.generalized)) {
      pass(text, ethnic, disease, intent || 'overview', conf, questionType, standardized, r.intent_label || undefined)
      if (r.disease_from_context || r.ethnicity_from_context) {
        showToast('已接着上文理解')
      }
      await send()
    } else {
      fail(text, ethnic, disease, intent, r.missing)
    }
  } else {
    // API 不可用，降级为关键词匹配
    keywordVerify(text)
  }
}

// ============================================================
// RAG 真实链路渲染：依据后端 retrieve+generate 的结果展示回答
// ============================================================
// structuredAnswerHtml 已抽到 @/utils/answerHtml —— 高级检索页也要用同一套渲染，见该文件注释

function renderAnswerFromRag(
  q: StoredQuestion,
  literature: LiteratureItem[],
  retrieveResult: EvidencePoolResult | null,
  generateResult: GenerateResult | null,
) {
  answer.q = q.text
  answer.visible = true
  answer.followUps = generateResult?.answer?.followUps || []

  // —— 证据材料展示 ——
  const found = !!retrieveResult?.evidence_found && (retrieveResult.evidence?.length ?? 0) > 0
  answer.missed = !found
  answer.intentMissing = !!retrieveResult?.intent_missing
  // 医疗安全边界（用药/诊疗咨询）走的是同一条「未命中」分支，但那是**刻意不检索**，
  // 不该被当成知识缺口引导用户去反馈——那等于把「该不该补文献」和「该不该回答用药问题」
  // 混成了一件事。
  answer.blocked = !!retrieveResult?.blocked
  // 检索层三个信号都没触发时，再看回答正文有没有自己承认「未覆盖」——
  // 对比类问题（「傣族高血压和汉族比谁更严重」）就是这样：证据对得上，但库里没有对比数据。
  answer.notCovered = answerSaysNotCovered(generateResult?.answer?.conclusion)
  if (found) {
    answer.evidenceItems = retrieveResult!.evidence.map((e) => ({
      id: e.id || '',
      title: e.title || '来源资料',
      fragment: e.fragment || '',
      doi: e.doi,
      source: e.source,
      startOffset: e.startOffset,
      endOffset: e.endOffset,
      page: e.page,
      matchedTerms: e.matchedTerms,
      sourceLevel: e.sourceLevel,
      sourceOrg: e.sourceOrg,
      partition: e.partition,
    }))
    answer.evidenceNote = ''
  } else {
    answer.evidenceItems = []
    answer.evidenceNote = retrieveResult?.reason || '未检索到相关证据。'
  }

  // —— 结论展示 ——
  if (generateResult && generateResult.answer) {
    answer.conclusionHtml = structuredAnswerHtml(generateResult.answer)
  } else if (retrieveResult && !found) {
    // 追问兜底：知识库没收录该问题的直接证据时，不给用户一个死胡同，
    // 而是给出可执行的下一步（咨询医生 + 参考上文已有数据）
    answer.conclusionHtml =
      '<div class="lit-note">目前我们的知识库暂未收录该问题的直接证据，但基于现有文献，我们建议您咨询专业医生，您可以先参考上面的数据。</div>'
      + (q.ethnic || q.disease
        ? `<div class="lit-note">（未检索到关于「${esc(q.ethnic)} ${esc(q.disease)}」的${esc(q.intentText || intentLabel(q.intent))}证据，换一种问法或改用上面提到的其他问题试试）</div>`
        : '')
  } else {
    answer.conclusionHtml = '<div class="lit-note">这次没能生成回答，您可以换个问法再试一次，或点击上方的追问继续。</div>'
  }

  // 降级匹配提示：问题里没提到具体疾病（后端用「民族 + 意图」泛化检索），
  // 与其让用户以为答非所问，不如直接告诉他该怎么问得更准。
  if (!q.disease && q.ethnic) {
    answer.conclusionHtml =
      `<div class="lit-note">您的问题里没有提到具体疾病，下面是「${esc(q.ethnic)}」相关的通用资料。`
      + `如果想看某个疾病的详细情况，可以换个问法，例如「${esc(q.ethnic)}糖尿病患病情况如何」。`
      + '</div>'
      + answer.conclusionHtml
  }
}

async function send() {
  btnState.value = 'busy'
  // 处理中的反馈交给对话流末尾的内联指示器（三点 + 微光文案），不再占用 toast：
  // toast 是「一次性消息」的位置，而处理中是一个持续状态，且 toast 一出现就会
  // 连带把「请继续追问」的引导语带出来——那句话是回答完成之后才该说的。

  const q = readQuestion()
  if (!q || q.status !== 'clear') {
    btnState.value = 'verify'
    showToast('请先输入问题', true)
    return
  }

  const literature = readLiterature()
  // 上下文在追加本轮之前取：此时 turns 里是「之前几轮」，正是后端补全追问所需
  const ctx = buildContext()

  let rec: AnalysisRecord | null = null
  try {
    rec = await analysisStore.load(q, literature as KnowledgeDoc[], ctx)
  } catch {
    // 检索/生成失败也要保住对话记录，避免「发出去却没保存」
  }

  btnState.value = 'verify'
  // 失败判定：load 内部把两个结果都清成 null（store 的 catch），或 load 本身抛错（rec 仍为 null）。
  // **「未命中」不算失败**——那时 pool 有值（evidence_found=false），回答区会走「未命中」那条分支。
  genFailed.value = !rec || (!rec.pool && !rec.generate)
  // 读 load 的**返回值**而不是 store 上的字段：这期间高级检索可能刚好跑完并把工作台
  // 切到它那一侧，从 store 上读就会读到别人的分析。
  renderAnswerFromRag(q, literature, rec?.pool ?? null, rec?.generate ?? null)
  // 把本轮问答追加进对话线程，保留前面所有问答，避免新问题顶掉旧回答
  const turn: Turn = {
    question: answer.q,
    ethnic: q.ethnic || '',
    disease: q.disease || '',
    intent: q.intent || '',
    evidenceItems: answer.evidenceItems,
    evidenceNote: answer.evidenceNote,
    conclusionHtml: answer.conclusionHtml,
    followUps: answer.followUps,
    missed: answer.missed,
    intentMissing: answer.intentMissing,
    notCovered: answer.notCovered,
    blocked: answer.blocked,
  }
  turns.value.push(turn)
  scrollToAnswer()
  showToast('回答完毕')
  persistCurrent(turn)
  // 输入框的清空已上移到 verify()（乐观更新）——那时就清了，这里不再重复
}

function onPrimary() {
  verify()
}

function onInput() {
  btnState.value = 'verify'
}

function goToDoc(id?: string) {
  if (id) router.push({ name: 'kb', hash: `#${id}` })
}

// 推荐问题池：用老百姓的口语和场景提问，而不是「患病情况如何」这类学术腔。
// 这些问题会作为完整 query 原样发给后端，由理解层（Qwen + 规则）从中提取「民族 + 疾病 + 意图」。
// 覆盖不同民族（白族 / 傣族 / 哈尼族 / 傈僳族 / 苗族 / 彝族 / 纳西族）与不同疾病
// （糖尿病 / 高血压 / CKM / 心脏瓣膜病 / 脂肪肝 / 肥胖），以及不同意图
// （患病率 / 饮食 / 遗传 / 危险因素 / 用药）。
interface SuggestedQuestion {
  text: string
  /** 所属民族：抽取时据此去重，保证三条推荐覆盖不同民族 */
  ethnic: string
}

const QUESTION_POOL: SuggestedQuestion[] = [
  // —— 白族 ——
  { text: '我家是白族的，长辈有糖尿病，我是不是也得小心？', ethnic: '白族' },
  { text: '云南白族得糖尿病的人多吗？比别的民族严重吗？', ethnic: '白族' },
  { text: '白族饮食习惯里，哪些容易吃出糖尿病？', ethnic: '白族' },
  { text: '白族的高血压患病情况怎么样？', ethnic: '白族' },
  { text: '白族同胞的心脏瓣膜病严重吗？', ethnic: '白族' },
  { text: '我是白族，最近查出高血压，用药有什么要注意的吗？', ethnic: '白族' },
  // —— 傣族 ——
  { text: '傣族的高血压和汉族比，谁更严重？', ethnic: '傣族' },
  { text: '傣族的心血管-肾脏-代谢综合征（CKM）情况如何？', ethnic: '傣族' },
  { text: '傣族得糖尿病的人多吗？', ethnic: '傣族' },
  { text: '傣族爱吃腌制食品，这跟他们血压高有关系吗？', ethnic: '傣族' },
  // —— 哈尼族 ——
  { text: '哈尼族的心脏瓣膜病患病率高吗？', ethnic: '哈尼族' },
  { text: '哈尼族糖尿病的人多吗？', ethnic: '哈尼族' },
  { text: '哈尼族高血压主要有哪些危险因素？', ethnic: '哈尼族' },
  // —— 傈僳族 ——
  { text: '傈僳族的肥胖率是多少？跟其他民族比怎么样？', ethnic: '傈僳族' },
  { text: '傈僳族的心血管-肾脏-代谢综合征严重吗？', ethnic: '傈僳族' },
  { text: '傈僳族儿童青少年的高血压检出率高吗？', ethnic: '傈僳族' },
  // —— 其他民族 / 跨民族 ——
  { text: '苗族和彝族，哪个民族的脂肪肝患病率更高？', ethnic: '苗族' },
  { text: '纳西族的肥胖情况严重吗？', ethnic: '纳西族' },
  { text: '为什么少数民族之间，糖尿病的患病率会不一样？', ethnic: '多民族' },
  { text: '云南各民族的遗传研究有哪些发现？', ethnic: '多民族' },
]

/**
 * 从问题池随机抽取 n 条，覆盖不同民族。
 *
 * 先随机挑选 n 个民族，再从各民族的问题里各取一条。不按题目数加权，
 * 是为了让每个民族被抽中的机会均等——否则白族题库有 6 条、纳西族只有 1 条，
 * 白族会反复刷屏，与「覆盖不同民族」的初衷相反。
 */
function pickSuggestedQuestions(n = 3): string[] {
  const byEthnic = new Map<string, string[]>()
  for (const q of QUESTION_POOL) {
    const list = byEthnic.get(q.ethnic)
    if (list) list.push(q.text)
    else byEthnic.set(q.ethnic, [q.text])
  }
  const ethnics = [...byEthnic.keys()]
  for (let i = ethnics.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    // 显式三步交换，不用解构赋值：`noUncheckedIndexedAccess` 下 ethnics[i] 是
    // string | undefined，解构赋值的左右两侧都会被判成可能 undefined。
    // i、j 都落在 [0, length) 内，所以断言非空是安全的。
    const tmp = ethnics[i]!
    ethnics[i] = ethnics[j]!
    ethnics[j] = tmp
  }
  const picked: string[] = []
  for (const e of ethnics) {
    if (picked.length >= n) break
    const list = byEthnic.get(e) as string[]
    picked.push(list[Math.floor(Math.random() * list.length)] as string)
  }
  return picked
}

/** 当前展示的推荐问题：每次进入页面重新随机抽取 */
const suggestedQuestions = ref<string[]>(pickSuggestedQuestions())

/**
 * 把一段文本作为完整 query 直接发起新一轮对话。
 * 首页预设问题、历史追问、回答下方的追问胶囊都走这里，保证「点击即提问」。
 */
function sendMessage(q: string) {
  input.value = q
  verify()
}

// ============================================================
// 会话（服务端存储 /api/conversations）
// ============================================================
const conversations = ref<Conversation[]>([])
const activeId = ref<number | null>(null)
const loadingList = ref(false)
/** 打开历史会话后加载的多轮消息（用于回放） */
const historyMessages = ref<ChatMessage[]>([])

/**
 * 对话线程里的**一轮**：用户提问 + 该问的回答。
 *
 * 历史回放（服务端消息）和本会话新增的回合（turns）都映射成这个形状，
 * 由 thread 合并成一条列表**统一渲染**。
 *
 * 此前两者是两套模板分支，于是「在历史会话里追问」时，为了把显示权交给 turns
 * 必须先清空 historyMessages——用户正读着的那几轮会在点下发送的瞬间全部消失。
 * 合并之后不再需要清空，历史与新回合天然拼在一起。
 */
interface ThreadTurn {
  key: string
  question: string
  /** 已渲染的回答正文 HTML；为空表示这一问没有回答记录 */
  html: string
  evidence: EvidenceRef[]
  /** 没检索到证据时的说明 */
  evidenceNote: string
  followUps: string[]
  messageId?: number
  /** 未识别出具体疾病 = 泛化检索，证据是该民族的整体资料而非该病的专属数据 */
  generalized: boolean
  /** 来自历史回放：老记录可能没存原文定位信息，提示语要不同 */
  fromHistory: boolean
  /** 落库时的原始响应——分享打包要用里面的 citations（含 doc id 与偏移量） */
  detail?: Record<string, any> | null
  plain?: string
  /**
   * 「反馈此问题」入口。**为 null 表示这一轮不该出现它**。
   *
   * 打包成一个字段而不是散着加四五个布尔：判据有四项（未命中 / 非医疗边界 /
   * 民族与疾病都识别出来了 / 未被泛化），散着放模板里迟早有一项被漏掉或写反。
   */
  feedback: FeedbackSlot | null
}

/** 一轮可反馈的提问：登记知识缺口要用的东西都在这儿 */
interface FeedbackSlot {
  ethnicity: string
  disease: string
  intent: string
  question: string
  /** 已经反馈过（按槽位比对，见 feedbackSentKeys） */
  sent: boolean
}

/**
 * 我在对话里反馈过的槽位（键是 `民族|疾病|方面`）。
 *
 * 回显按**槽位**而不是按回合：项目里没有「更新已存在消息」的接口（appendMessage 只能新增），
 * 把标记写进消息 detail 做不到刷新后还在。按槽位比对天然解决了刷新丢失，还顺带覆盖
 * 「换个会话问同一组合」的情形——语义上也自洽，服务端本来就按 (缺口, 用户) 去重，
 * 同一组合再点一次是空操作。
 */
const feedbackSentKeys = ref<Set<string>>(new Set())

async function loadMyFeedback() {
  try {
    const list = await myFeedbackSlots()
    feedbackSentKeys.value = new Set(list.map(gapSlotKey))
  } catch {
    // 拉不到就当没反馈过：按钮显示回「反馈此问题」，点下去服务端仍会去重，
    // 不会真的重复计数。不值得为它打断对话页。
    feedbackSentKeys.value = new Set()
  }
}

/**
 * 回答正文里出现这些词，说明模型自己讲了「这块文献没覆盖 / 答不了」。
 *
 * 只列「未/无法/尚无」系的完整词，不列单字——单字会大面积误命中。
 * 注意「尚未××」不必单列：`尚未检索到` 本身含子串 `未检索到`，`尚未找到` 含 `未找到`，以此类推。
 */
const NOT_COVERED_PATTERNS = [
  '未覆盖', '未收录', '未检索到', '未找到', '未提供', '未给出',
  '无法提供', '无法回答', '无法判断', '尚无', '暂无',
]

/**
 * 回答正文（**只看 `conclusion`，不看适用范围 / 使用边界**）是否说了「没覆盖」。
 *
 * 为什么不能扫全文：适用范围与使用边界那两节**本来就该**写这类话——
 * 「该文献未提供傣族与汉族高血压的直接对比数据」「该研究未覆盖农村地区」——
 * 拿它们当判据，会把**答得好好的**问题也误判成未命中，凭空多出一个反馈入口。
 * 而 `conclusion` 是回答主体，只有真的没答上来时才会在那儿写「未覆盖」。
 *
 * 为什么需要这个判据（原有三个结构化判据为什么不夠）：
 * `missed` / `generalized` / `intentMissing` 全是**检索层**信号，覆盖不了
 * 「检索到了部分相关证据、但答不了用户真正问的那个问题」——典型是**对比类问题**：
 * 问「傣族高血压和汉族比，谁更严重？」，检索命中了傣族代谢综合征的资料，
 * `evidence_found=true`，三个判据全不成立，可回答正文第一句就是
 * 「现有研究证据未覆盖傣族高血压与汉族…的直接对比数据」。
 */
function answerSaysNotCovered(text: unknown): boolean {
  if (typeof text !== 'string' || !text) return false
  return NOT_COVERED_PATTERNS.some((k) => text.includes(k))
}

/**
 * 一轮提问的「答到点子上没有」信号，来自 retrieval 快照或实时证据池。
 */
interface FeedbackProbe {
  ethnicity: string
  disease: string
  intent: string
  question: string
  /** 一条证据都没检索到 */
  missed: boolean
  /** 疾病没被识别出来，回答的是该民族的通用资料（泛化检索） */
  generalized: boolean
  /** 找到了背景资料，但没有一条对应所问的方面 */
  intentMissing: boolean
  /** 检索层三个信号都不成立，但回答正文自己说了「未覆盖」 */
  notCovered: boolean
  /** 命中医疗安全边界 */
  blocked: boolean
}

/**
 * 这一轮该不该给「反馈此问题」入口。
 *
 * 判据是「**用户要的东西没给到**」，四种情形满足其一即可：
 *   ① `missed`        —— 一条证据都没检索到；
 *   ② `generalized`   —— 问题里的疾病没被识别出来，回答的是该民族的通用资料。
 *                        用户问「傣族CKM患病率」，拿到的是傣族糖尿病的数字——**要的东西没给到**，
 *                        而且这恰恰是最该反馈的：NLU 词表里没有 CKM，知识库里也未必有。
 *                        （只按「一条证据都没有」判的话，这种情形会被漏掉，因为通用资料也算证据。）
 *   ③ `intentMissing` —— 找到了背景资料，但没有一条对应所问的方面。
 *   ④ `notCovered`    —— 检索层三个信号都没触发，但回答正文自己承认了没覆盖。
 *                        检索是「按民族 × 疾病 × 方面」匹配的，匹配上了不等于答得了——
 *                        对比类问题（「傣族高血压和汉族比谁更严重」）就是典型：
 *                        证据对得上，可库里没有对比数据。这种只有读回答正文才发现得了。
 *
 * 排除医疗安全边界：用药/诊疗咨询是**刻意不检索**，不是知识缺口。
 * 民族必须有：知识库按「民族 × 疾病」组织，没有民族就无从补录（实际也到不了这一步——
 * 民族缺失时问题不会被判定为 clear，压根不会出回答）。
 */
function feedbackSlotOf(p: FeedbackProbe): FeedbackSlot | null {
  if (p.blocked) return null
  if (!p.missed && !p.generalized && !p.intentMissing && !p.notCovered) return null
  const ethnicity = p.ethnicity.trim()
  if (!ethnicity) return null

  // 疾病槽位：优先用 NLU 认出来的；没认出来时**退回前端自己的词表**从原话里再认一次。
  // 这一步很值：像「CKM」前端词表里有、而服务端 NLU 的 7 个疾病里没有，退回之后能登记成
  // 「傣族 × CKM × 患病率」——管理员一眼知道要补什么；不退的话只能记成「未识别疾病」，
  // 得去读原话才知道用户要的是什么。
  const disease = p.disease.trim() || extractSlots(p.question).disease || DISEASE_UNSPECIFIED
  // 意图也没认出来时退到兜底档（不指向某个方面）——与留言板转缺口的做法一致。
  // 不能因为缺意图就不给反馈入口，那正是「没答到点子上」的一种。
  const intent = p.intent.trim() || 'all'

  return {
    ethnicity,
    disease,
    intent,
    question: p.question,
    sent: feedbackSentKeys.value.has(gapSlotKey({ ethnicity, disease, intent })),
  }
}

/** 正在反馈的那一轮（按回合 key 去重，避免连点） */
const feedbackBusyKey = ref<string | null>(null)

/**
 * 点【反馈此问题】：把这一轮的三个槽位 + 原话登记成知识缺口。
 *
 * 服务端一次调用完成「登记 + 反馈」两件事（高级检索那条链路要分两步，因为用户可能不反馈；
 * 这里的按钮本身就是反馈动作）。重复点由服务端按 (缺口, 用户) 去重，刷不上去。
 */
async function feedbackTurn(t: ThreadTurn) {
  const f = t.feedback
  if (!f || f.sent || feedbackBusyKey.value != null) return
  feedbackBusyKey.value = t.key
  try {
    await chatFeedback({
      ethnicity: f.ethnicity,
      disease: f.disease,
      intent: f.intent,
      question: f.question,
    })
    // 本地记下这个槽位，**不重拉列表**：重拉会让按钮先闪回未反馈再变，像是点失败了
    feedbackSentKeys.value = new Set([...feedbackSentKeys.value, gapSlotKey(f)])
    ElMessage.success('已记录您的需求，感谢反馈！')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '反馈失败，请稍后重试')
  } finally {
    feedbackBusyKey.value = null
  }
}

/** 历史消息 + 本会话新增回合，合并成一条对话线程 */
const thread = computed<ThreadTurn[]>(() => {
  const out: ThreadTurn[] = []

  // ① 历史回放：先记下 user 消息的问题，紧随其后的 assistant 消息与它配成一轮
  let lastUser = ''
  for (const m of historyMessages.value) {
    if (m.role === 'user') {
      lastUser = m.content
      continue
    }
    const ans = m.detail?.answer
    const citations = (m.detail?.citations as Record<string, any>[]) || []
    // 历史回合的「未命中」与「医疗边界」只有落库的那份快照里有（见 buildAnswerDetail 的 retrieval）
    const u = (m.detail?.understanding || {}) as Record<string, string>
    const r = (m.detail?.retrieval || {}) as Record<string, any>
    out.push({
      key: `h${m.id ?? out.length}`,
      question: lastUser,
      html: ans ? structuredAnswerHtml(ans as GenerateAnswer) : m.content || '',
      evidence: citations.map((c) => ({
        id: c.id || '',
        title: c.title || (c.paper && c.paper.title) || '来源资料',
        fragment: c.quote || '',
        startOffset: c.startOffset,
        endOffset: c.endOffset,
        page: c.page ?? null,
        matchedTerms: c.matchedTerms || [],
        sourceLevel: c.sourceLevel,
        sourceOrg: c.sourceOrg,
        partition: c.partition,
      })),
      evidenceNote: '',
      followUps: (ans?.followUps as string[]) || [],
      messageId: m.id,
      generalized: !m.detail?.understanding?.disease,
      fromHistory: true,
      detail: m.detail || null,
      plain: m.content,
      // 判据与实时回合的 renderAnswerFromRag 对齐（那边是 evidence_found && 证据非空）
      feedback: feedbackSlotOf({
        ethnicity: u.ethnicity || '',
        disease: u.disease || '',
        intent: u.intent || '',
        question: lastUser,
        missed: r.evidence_found !== true || citations.length === 0,
        generalized: !u.disease,
        intentMissing: !!r.intent_missing,
        // 落库时存的是完整回答对象（buildAnswerDetail 的 `answer: ans`），
        // 所以历史回放同样能读到未渲染的 conclusion 原文
        notCovered: answerSaysNotCovered((m.detail?.answer as { conclusion?: string } | undefined)?.conclusion),
        blocked: !!r.blocked,
      }),
    })
    lastUser = ''
  }
  // 落单的 user 消息（assistant 那半没落库成功）：问题也要露出来，不能凭空消失
  if (lastUser) {
    out.push({
      key: 'h-lone',
      question: lastUser,
      html: '',
      evidence: [],
      evidenceNote: '这一问没有留下回答记录',
      followUps: [],
      generalized: false,
      fromHistory: true,
      // 这不算「未命中」——只是回答没落库，没有可登记的知识缺口
      feedback: null,
    })
  }

  // ② 本会话新增的回合
  for (let i = 0; i < turns.value.length; i++) {
    const t = turns.value[i]!
    out.push({
      key: `t${i}`,
      question: t.question,
      html: t.conclusionHtml,
      evidence: t.evidenceItems,
      evidenceNote: t.evidenceNote,
      followUps: t.followUps,
      messageId: t.messageId,
      generalized: !t.disease,
      fromHistory: false,
      detail: t.detail || null,
      plain: t.plain,
      feedback: feedbackSlotOf({
        ethnicity: t.ethnic,
        disease: t.disease,
        intent: t.intent || '',
        question: t.question,
        missed: !!t.missed,
        generalized: !t.disease,
        intentMissing: !!t.intentMissing,
        notCovered: !!t.notCovered,
        blocked: !!t.blocked,
      }),
    })
  }
  return out
})

let lastPersisted: string | null = null

/** 当前会话是否有可分享的问答（决定右上角操作栏是否出现） */
const hasShareableContent = computed(() => thread.value.some((t) => t.html))

/** 当前会话标题 */
const currentTitle = computed(() => {
  if (activeId.value) {
    const c = conversations.value.find((x) => x.id === activeId.value)
    return c?.title || '对话'
  }
  return '新对话'
})

function fmtMeta(c: Conversation): string {
  const n = c.messageCount ?? 0
  return `${n} 条消息`
}

interface ConversationGroup {
  label: string
  items: Conversation[]
}

/**
 * 把会话列表切成 [分组标题, 该组会话] 的形式。
 * conversations 本身已按 updatedAt 倒序，所以同一分组必然是连续的，顺序扫一遍即可。
 */
const groupedConversations = computed<ConversationGroup[]>(() =>
  groupByTime(conversations.value, (c) => c.updatedAt || c.createdAt || Date.now()),
)

async function refreshList() {
  loadingList.value = true
  try {
    conversations.value = await listConversations()
  } catch {
    /* 拦截器已统一提示 */
  } finally {
    loadingList.value = false
  }
}

async function openConversation(c: Conversation) {
  // 兜底守卫：遮罩层已经挡住了点击，但键盘/程序化触发仍可能走到这里
  if (isGenerating.value) {
    warnBusySwitch()
    return
  }
  try {
    const detail = await getConversation(c.id)
    activeId.value = detail.conversation.id
    persistActiveId(detail.conversation.id)   // 记下当前会话，刷新后回到这里
    historyMessages.value = detail.messages || []
    input.value = ''
    btnState.value = 'verify'
    hideAnswer()
    turns.value = []
    lastPersisted = null
    loadConvShareState()
    refreshList()
    // 长会话刚打开时可能停在顶部，同步一次贴底状态，「回到最新」才会正确出现
    nextTick(onScrollerScroll)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载会话失败')
  }
}

async function renameConversation(c: Conversation) {
  let value: string
  try {
    const res = await ElMessageBox.prompt('请输入新的会话标题', '重命名会话', {
      inputValue: c.title,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim() ? true : '标题不能为空'),
    })
    value = res.value
  } catch {
    return
  }
  try {
    await renameConversationApi(c.id, value.trim())
    ElMessage.success('已重命名')
    refreshList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重命名失败')
  }
}

async function removeConversation(c: Conversation) {
  try {
    await ElMessageBox.confirm(`确定删除会话「${c.title}」吗？其中的历史问答将一并删除，且不可恢复。`, '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteConversationApi(c.id)
    ElMessage.success('会话已删除')
    if (activeId.value === c.id) newConversation()
    refreshList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

/**
 * 把追溯目标写进共享 store —— 后台挂载时静默读取并加载（见 BackendView.loadTrace）。
 *
 * 之所以不重跑分析：重跑会因大模型随机性而与当时展示的回答不一致，那就不叫「追溯」了。
 */
function setTraceTarget(messageId: number) {
  analysisStore.setTraceTarget(messageId, activeId.value)
}

/**
 * 「查看后台分析」按钮：**直接跳到后台工作台**。
 *
 * 不做额外提示——页面都跳过去了，再弹一句「请前往后台查看」是自相矛盾的。
 */
function openAdminTrace(messageId: number) {
  setTraceTarget(messageId)
  router.push({ name: 'backend' })
}

/**
 * 点回答卡片的**其他区域**：只提示、不跳转，由用户决定什么时候去后台看。
 *
 * 这条路径刻意与上面那个按钮不同：卡片面积大，误触概率高，
 * 直接跳走会让人莫名其妙地离开对话页。
 */
function viewAdminTrace(messageId: number) {
  setTraceTarget(messageId)
  // 用 ElMessage.success 而不是带 ✅ 的纯文本弹窗——图标由组件自己给，文案里不用再画一遍
  ElMessage.success('后台分析工作台已切换至该问题的分析记录，请前往后台查看')
}

// ---------- 两个点击区域的分工 ----------
// 回答卡片外层（.hist-shell）绑「回溯后台」，卡片内部的「依据文献」片段绑「查看原文」。
// 片段是 <div>，不是 button/a，所以外层的 closest('button, a, input, …') 守卫拦不住它，
// 点击会先触发内层、再冒泡触发外层 —— 两个动作一起发生。这里显式切断冒泡。

/**
 * 点击「依据文献」片段。
 *
 * 只有**真的打开了原文**时才阻止冒泡：可定位的片段带「查看原文 →」提示，是一个明确的
 * 交互区；不可定位的片段看起来就是普通文字，让它继续冒泡去触发卡片的「回溯后台」，
 * 否则那块区域会变成一个点了没反应的死点。
 */
function onEvidenceClick(ev: MouseEvent, items: EvidenceRef[], index: number) {
  const item = items[index]
  if (!item || !canLocate(item)) return
  ev.stopPropagation()
  openEvidenceDoc(items, index)
}

/**
 * 历史问答外壳的点击：点在内层可交互元素（按钮 / 链接 / 折叠头）上时不触发追溯，
 * 否则「点追问胶囊」会连带跳到后台。
 */
function onHistoryShellClick(ev: MouseEvent, messageId?: number) {
  if (messageId == null) return
  const el = ev.target as HTMLElement | null
  if (el && el.closest('button, a, input, .evidence-fold-head')) return
  viewAdminTrace(messageId)
}

// ---------- 当前会话 ID 的本地持久化（刷新后恢复连续对话） ----------
const ACTIVE_KEY = 'mmx_active_conv'

/** undefined = 从未记录过；null = 用户主动新建（未落库）；number = 已有会话 */
function readStoredActiveId(): number | null | undefined {
  try {
    const raw = localStorage.getItem(ACTIVE_KEY)
    if (raw == null) return undefined
    if (raw === 'new' || raw === '') return null
    const n = Number(raw)
    return Number.isFinite(n) ? n : undefined
  } catch {
    return undefined
  }
}

function persistActiveId(id: number | null) {
  try {
    localStorage.setItem(ACTIVE_KEY, id == null ? 'new' : String(id))
  } catch {
    /* ignore */
  }
}

/**
 * 确保存在当前会话：已有就复用，没有才新建。
 *
 * 此前这里是「无条件 createConversation」，导致**每问一次就新建一个会话**——
 * 侧边栏被单条问答塞满，切走再回来只能看到最后一轮，刷新后只剩最后一条。
 */
async function ensureConversation(question: string, summary: string): Promise<number> {
  if (activeId.value != null) return activeId.value
  const q = readQuestion()
  const d = await createConversation({
    title: question,
    question,
    ethnic: q?.ethnic || '',
    disease: q?.disease || '',
    intent: q?.intent || '',
    status: q?.status || 'clear',
    answer_summary: summary,
  })
  activeId.value = d.id
  persistActiveId(d.id)
  return d.id
}

/** 组装落库用的 detail：理解结果 + 回答 + 引用 + 检索概要（后者供后台追溯） */
function buildAnswerDetail(q: StoredQuestion): Record<string, any> {
  // 一律读「对话」那条记录，不读 activeSource：高级检索跑完会把工作台切到它那一侧，
  // 那样这里就会把检索的答案当成这轮对话落库。
  const rec = analysisStore.dialogueRecord
  const gen = rec.generate
  const ans = gen?.answer || null
  const pool: any = rec.pool
  return {
    type: 'answer',
    message: null,
    clarifyQuestion: null,
    understanding: { ethnicity: q.ethnic || '', disease: q.disease || '', intent: q.intent || '' },
    options: null,
    slot: null,
    answer: ans
      ? {
          conclusion: ans.conclusion,
          detailed: ans.detailed || null,
          actions: ans.actions || null,
          sections: ans.sections,
          applicable: ans.applicable || null,
          timeRegion: ans.timeRegion || null,
          cautions: ans.cautions || null,
          followUps: ans.followUps || [],
        }
      : null,
    citations: (pool?.evidence || []).map((e: any, i: number) => ({
      index: i + 1,
      evidenceId: null,
      quote: e.fragment,
      paper: null,
      // 一并落库：①命中词 → 历史回放也能高亮；②doc id 与偏移量 → 历史回放的片段
      // 也能「点开原文并定位」，与实时回合行为一致（不存这两项就只能干看着）
      id: e.id || '',
      title: e.title || '',
      startOffset: e.startOffset ?? -1,
      endOffset: e.endOffset ?? -1,
      page: e.page ?? null,
      matchedTerms: e.matchedTerms || [],
      // 来源等级一并落库：动态详情弹窗要显示来源标签，
      // 不存的话别人点开这条分享就看不到「这条依据是不是官方审核过的」
      sourceLevel: e.sourceLevel || 'official',
      sourceOrg: e.sourceOrg ?? null,
      // 分区也落库：从历史回放点依据时，同样要按分区决定展示 PDF 原页还是正文片段
      partition: e.partition || 'integrated',
    })),
    // 检索概要：只留展示所需字段，**不带 fullText / content**（否则单条消息会膨胀到几十 KB）
    retrieval: pool
      ? {
          status: pool.status ?? null,
          reason: pool.reason ?? '',
          evidence_found: !!pool.evidence_found,
          intent_missing: !!pool.intent_missing,
          pan_ethnic: !!pool.pan_ethnic,
          blocked: !!pool.blocked,
          // 候选资料只留展示所需字段，**不带 fullText / content**——
          // 否则单条消息会从几 KB 膨胀到几十 KB
          candidates: (pool.candidates || []).map((c: any) => ({
            title: c.title,
            source: c.source,
            id: c.id,
            status: c.status,
            ethnicity: c.ethnicity ?? null,
            disease: c.disease ?? null,
            evidence: (c.evidence || []).map((ev: any) => ({
              fragment: ev.fragment,
              topic: ev.topic ?? null,
              page: ev.page ?? null,
            })),
          })),
          evidence: (pool.evidence || []).map((e: any) => ({
            title: e.title, fragment: e.fragment, page: e.page ?? null,
          })),
        }
      : null,
    engine: ans?.engine || '',
    conversationId: null,
  }
}

/** 兜底正文：结论 + 分节（detail 反序列化失败时用于列表展示） */
function buildPlainText(q: StoredQuestion): string {
  // 同上：读对话那条记录，免得把高级检索的答案写进这轮对话
  const ans = analysisStore.dialogueRecord.generate?.answer
  if (!ans) return ''
  return (
    ans.conclusion
    + (ans.detailed ? '\n\n文献数据说明：' + ans.detailed : '')
    + (ans.actions ? '\n\n专家行动建议：' + ans.actions : '')
    + (ans.sections && ans.sections.length
      ? '\n' + (ans.sections as { title: string; content: string }[]).map((s) => `${s.title}：${s.content}`).join('\n')
      : '')
  )
}

function persistCurrent(turn?: Turn) {
  const q = readQuestion()
  if (!q || q.status !== 'clear') return
  if (q.text === lastPersisted) return
  lastPersisted = q.text
  // 落库是异步的，这里先把本轮回合对象抓住，落库完成后回填消息 ID
  const currentTurn = turn || turns.value[turns.value.length - 1]
  const summary = analysisStore.dialogueRecord.generate?.answer?.conclusion || ''
  ensureConversation(q.text, summary)
    .then(async (cid) => {
      // 把本轮问答**追加**到当前会话（不再新建会话）
      try {
        const detail = buildAnswerDetail(q)
        const plain = buildPlainText(q)
        await appendMessage(cid, { role: 'user', kind: 'text', content: q.text, detail: null })
        const saved = await appendMessage(cid, { role: 'assistant', kind: 'answer', content: plain, detail })
        // 回填服务端消息 ID 与完整响应，供回答卡片上的「分享至动态」「查看后台分析」溯源
        if (currentTurn && saved && saved.id != null) {
          currentTurn.messageId = saved.id
          currentTurn.plain = plain
          currentTurn.detail = detail
        }
      } catch {
        /* 落库失败不影响已展示的回答 */
      }
      refreshList()
    })
    .catch(() => {})
}

function newConversation() {
  input.value = ''
  btnState.value = 'verify'
  hideAnswer()
  turns.value = []
  activeId.value = null
  persistActiveId(null)   // 记成「用户主动新建」，刷新后不被自动拉回旧会话
  lastPersisted = null
  historyMessages.value = []
  sharedDynamicId.value = null
  convFavorited.value = false
  atBottom.value = true
  clearQuestion()
  refreshList()
}

/** 把服务端返回的会话详情套用到当前视图 */
function applyConversationDetail(detail: ConversationDetail) {
  activeId.value = detail.conversation.id
  persistActiveId(detail.conversation.id)
  historyMessages.value = detail.messages || []
  turns.value = []
  lastPersisted = null
  loadConvShareState()
}

/**
 * 刷新 / 重新进入后恢复上一次的连续对话。
 *
 * 本地记录分三态：数字=回到那个会话；'new'=用户主动新建过，保持空白；
 * 从未记录（首次访问或老数据）才回退去问服务端「最近活跃的会话」。
 * 少了 'new' 这一态，用户点过「新建对话」再刷新就会被自动拉回旧会话。
 */
async function restoreActiveConversation() {
  const stored = readStoredActiveId()
  if (stored === null) return
  try {
    if (typeof stored === 'number') {
      applyConversationDetail(await getConversation(stored))
      return
    }
    const last = await getLastConversation()
    if (last) applyConversationDetail(last)
  } catch {
    // 会话已删除 / 不属于当前用户 → 退回空白，不打扰用户
    activeId.value = null
    persistActiveId(null)
    historyMessages.value = []
  }
}

/**
 * 处理 URL 上的 ?new / ?conv 参数。
 *
 * `?conv=<id>`：打开指定会话——个人中心的「查看」、首页链接都会带它。
 * 之前只处理了 ?new，导致从个人中心点「查看」跳过来时会被忽略，
 * 页面按 localStorage 恢复成**另一个**会话，用户看到的不是他想看的那条。
 */
let skipNextDefaultRestore = false

async function applyRouteQuery() {
  if (route.query.new) {
    router.replace({ name: 'chat' })
    newConversation()
    return
  }
  // ?q=<问题>：首页的热门问题带过来的。填进输入框并**自动发起提问**，
  // 用户点一下就直达回答，不用自己再敲一遍。
  // 按一次「全新的提问」处理：先开一段新对话——从首页点进来是个冷启动动作，
  // 把问题接进上一次的上下文里会让回答受无关历史影响。
  const hot = route.query.q
  if (typeof hot === 'string' && hot.trim()) {
    router.replace({ name: 'chat' })
    // 未登录时提问必然 401，直接留在空态，让用户先登录（登录后 redirect 会带回这个问题）
    if (userStore.isLoggedIn) {
      newConversation()
      sendMessage(hot.trim())
    }
    return
  }
  const raw = route.query.conv
  const convId = Number(raw)
  if (raw != null && raw !== '' && Number.isFinite(convId) && convId > 0) {
    // 消费掉参数，避免刷新/重新进入时反复触发
    skipNextDefaultRestore = true
    router.replace({ name: 'chat' })
    try {
      applyConversationDetail(await getConversation(convId))
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '会话不存在或无权访问')
      newConversation()
    }
    return
  }
  // replace 触发的那次调用：会话已经在上面加载好了，别再走默认恢复白跑一趟请求
  if (skipNextDefaultRestore) {
    skipNextDefaultRestore = false
    refreshList()
    return
  }
  refreshList()
  restoreActiveConversation()
}

onMounted(() => {
  userStore.fetchUser()
  applyRouteQuery()
  void loadMyFeedback()
})

// 本页被 keep-alive 缓存：重新进入时 onMounted 不会再触发，
// 从个人中心点「查看」跳过来时组件没重建、只有 URL 变了，所以要在这里再处理一次。
onActivated(() => {
  suggestedQuestions.value = pickSuggestedQuestions()
  applyRouteQuery()
  // 每次回到本页都重拉一次「我反馈过的槽位」：换账号登录后，上一份数据属于前一个用户，
  // 不重拉会让新账号看到别人的「已反馈」。这一次请求很小（一个人反馈过的组合天然有限）。
  void loadMyFeedback()
})

// 分步进度的定时器由 AnswerProgress 组件自己在 onUnmounted 里清（本页被 keep-alive 缓存，
// 组件失活时也会走卸载），这里不再需要清理钩子。

// 只在「当前就在对话页」时响应 URL 变化。
// 不加这层判断的话，跳到知识库/后台时这个 watch 也会触发，
// 白白跑一次会话恢复请求（组件被 keep-alive 缓存，watch 不会随失活停止）。
watch(() => route.fullPath, () => {
  if (route.name !== 'chat') return
  applyRouteQuery()
})
</script>

<template>
  <div class="qa">
    <AppTopbar />

    <div class="chat-wrap">
      <!-- 左侧边栏 -->
      <aside class="sidebar">
        <button class="btn btn-soft new-btn" type="button" :disabled="isGenerating" @click="newConversation">＋ 新建对话</button>

        <div class="chat-list-wrap">
          <div v-loading="loadingList" class="chat-list" :class="{ locked: isGenerating }">
            <div v-if="!conversations.length && !loadingList" class="empty-state empty-state-sm">
              <p class="empty-state-title">暂无历史对话</p>
              <p class="empty-state-desc">问一个问题，系统会把每段问答自动存成一次对话</p>
            </div>
            <!-- 按时间线分组：分组标题只在切换分组时出现一次 -->
            <template v-for="g in groupedConversations" :key="g.label">
              <div class="conv-group">{{ g.label }}</div>
              <div
                v-for="c in g.items"
                :key="c.id"
                class="chat-item"
                :class="{ on: activeId === c.id }"
                @click="openConversation(c)"
              >
                <div class="conv-main">
                  <div class="conv-title">{{ c.title }}</div>
                  <div class="conv-meta">{{ fmtMeta(c) }}</div>
                </div>
                <div class="conv-actions" @click.stop>
                  <button class="btn btn-sm btn-icon btn-ghost" type="button" title="重命名" @click.stop="renameConversation(c)">✎</button>
                  <button class="btn btn-sm btn-icon btn-ghost btn-danger" type="button" title="删除" @click.stop="removeConversation(c)">🗑</button>
                </div>
              </div>
            </template>
          </div>
          <!-- 生成期间盖住列表：列表已 pointer-events:none，点击会穿透到这一层，
               由它接住并说明为什么点不动——否则用户只会觉得界面卡死了 -->
          <div v-if="isGenerating" class="chat-lock" @click="warnBusySwitch"></div>
        </div>
      </aside>

      <!-- 右侧主区 -->
      <main class="main">
        <!-- 会话标题 + 整段对话的分享/收藏（作用于当前这一整个对话框，而不是某一条回答） -->
        <div class="main-header">
          <h2 class="main-title">{{ currentTitle }}</h2>
          <div v-if="hasShareableContent" class="conv-bar">
            <button
              class="btn btn-sm btn-outline"
              :class="{ 'is-on': convFavorited }"
              type="button"
              :disabled="favoring"
              :title="convFavorited ? '取消收藏这段对话' : '收藏这段对话'"
              @click="toggleConversationFavorite"
            >{{ convFavorited ? '已收藏' : '收藏' }}</button>
            <button
              class="btn btn-sm btn-outline btn-sage"
              :class="{ 'is-on': sharedDynamicId != null }"
              type="button"
              :disabled="sharing"
              :title="sharedDynamicId != null ? '把最新的对话内容同步到已发布的动态' : '把整段对话分享到动态'"
              @click="addConversationToDynamic"
            >{{ sharing ? '分享中…' : (sharedDynamicId != null ? '更新动态' : '分享至动态') }}</button>
          </div>
        </div>

        <!-- 滚动内容区 -->
        <div ref="scrollerEl" class="scroller" @scroll="onScrollerScroll">
          <!-- 历史回放（点开历史会话后显示） -->
          <!-- 对话线程：历史回放与本会话新增的回合合并成一条列表，同一套渲染。
               合并的意义：在历史会话里追问时，前面几轮不会再消失。此前两者是两套分支，
               必须清空 historyMessages 才能把显示权交给 turns，代价就是用户正读的内容当场消失。 -->
          <div v-if="thread.length" class="answer-area">
            <div v-for="t in thread" :key="t.key" class="hist-block">
              <div class="hist-user">{{ t.question }}</div>
              <!-- 外壳：整块可点，点了去后台追溯这一条的处理过程 -->
              <div
                class="hist-shell"
                :class="{ clickable: t.messageId != null }"
                :title="t.messageId != null ? '点击让后台工作台切换到这条问答' : ''"
                @click="onHistoryShellClick($event, t.messageId)"
              >
              <div class="answer-card">
                <div class="answer-head">
                  <span class="answer-title">回答</span>
                  <span class="answer-badge">有依据 · 可溯源</span>
                  <!-- 反馈过的这一轮留个记号：往回翻记录时一眼能看出哪一问已经反馈过 -->
                  <span v-if="t.feedback?.sent" class="answer-badge fb-badge">📝 已反馈</span>
                </div>

                <!-- 依据文献（可折叠，默认收起只露文献名，避免大段原文喧宾夺主） -->
                <div class="evidence-fold" v-if="t.evidence.length">
                  <button class="evidence-fold-head" type="button" @click="toggleEvidence(t.key)">
                    <span class="evidence-fold-caret" :class="{ open: expandedEvidence.has(t.key) }">▸</span>
                    <span class="evidence-fold-label">依据文献：</span>
                    <span class="evidence-fold-names">{{ evidenceNames(t.evidence) }}</span>
                    <span v-if="t.generalized" class="evidence-fold-tag">泛化检索</span>
                  </button>
                  <div v-show="expandedEvidence.has(t.key)" class="evidence-fold-body">
                    <p v-if="t.generalized" class="evidence-degrade">
                      ⚠ 未识别出具体疾病，以下为该民族的整体健康资料，不是该民族该疾病的专属数据。
                    </p>
                    <p v-if="t.evidence.some((e) => canLocate(e))" class="evidence-hint">点击下方任一片段，可查看它在文献原文中的位置</p>
                    <!-- 改造前落库的老记录没保存 doc id 与偏移量，永远定位不了——数据从来就没存过，
                         不是没生效。这里明说，免得用户以为点了没反应是 bug。 -->
                    <p v-else-if="t.fromHistory" class="evidence-hint stale">这是较早的记录，未保存原文定位信息，暂不支持跳转原文</p>
                    <div v-for="(e, ei) in t.evidence" :key="ei" class="evidence-item">
                      <div class="evidence-src-row">
                        <button v-if="e.id" class="evidence-src" type="button" @click="goToDoc(e.id)">《{{ e.title }}》</button>
                        <span v-else class="evidence-src-text">《{{ e.title }}》</span>
                        <!-- 来源等级标签：让用户一眼看出这条依据是不是官方审核过的 -->
                        <SourceBadge :level="e.sourceLevel" :org="e.sourceOrg" show-org />
                      </div>
                      <div
                        class="evidence-text"
                        :class="{ locatable: canLocate(e) }"
                        :title="canLocate(e) ? '点击查看原文此处' : ''"
                        @click="onEvidenceClick($event, t.evidence, ei)"
                        v-html="evidenceHtml(e)"
                      ></div>
                    </div>
                  </div>
                </div>
                <div v-else-if="t.evidenceNote" class="lit-note">{{ t.evidenceNote }}</div>

                <div class="answer-conclusion" v-html="t.html"></div>

                <!-- 未命中时的反馈入口。t.feedback 为 null 表示这一轮不该出现它
                     （已命中 / 医疗安全边界 / 没识别出民族或疾病 / 缺意图码，判据见 feedbackSlotOf） -->
                <div v-if="t.feedback" class="fb-prompt">
                  <span class="fb-prompt-text">
                    如果这个问题对您很重要，点击【反馈此问题】，我们会优先补充。
                  </span>
                  <button
                    class="btn btn-sm btn-soft"
                    type="button"
                    :disabled="t.feedback.sent || feedbackBusyKey === t.key"
                    :title="t.feedback.sent ? '已经反馈过了，我们会优先补充' : '登记这个需求，我们会优先补充相关资料'"
                    @click.stop="feedbackTurn(t)"
                  >{{ t.feedback.sent ? '已反馈' : (feedbackBusyKey === t.key ? '提交中…' : '反馈此问题') }}</button>
                </div>

                <div v-if="t.followUps.length" class="ans-followups">
                  <div class="ans-followups-title">您可能还想问</div>
                  <div class="followup-chips">
                    <button v-for="(f, fi) in t.followUps" :key="fi" class="btn btn-outline" type="button" :disabled="isGenerating" @click="sendMessage(f)">{{ f }}</button>
                  </div>
                </div>

                <!-- 单条问答的「查看后台分析」：点击直接跳转后台（与卡片其他区域的「只提示不跳转」刻意区分） -->
                <div v-if="t.messageId != null" class="add-row">
                  <button
                    class="btn btn-sm btn-outline btn-sage"
                    type="button"
                    @click.stop="openAdminTrace(t.messageId!)"
                  >查看后台分析</button>
                </div>
              </div>
              </div>
            </div>
          </div>

          <!-- 空状态 / 欢迎区：一旦有提问在途就收起（pendingQuestion 非空），
               让位给下面的「提问气泡 + 处理中」 -->
          <div v-else-if="!pendingQuestion" class="welcome">
            <h1 class="welcome-title">想了解哪个民族的健康研究？</h1>
            <p class="welcome-desc">用一句话提问，系统会识别民族、疾病与查询意图，并给出可追溯来源的回答。每次问答都会自动保存为一段对话。</p>

            <!-- 建议问题 -->
            <div class="suggestions">
              <button
                v-for="(sq, i) in suggestedQuestions"
                :key="i"
                class="btn btn-outline"
                type="button"
                :disabled="isGenerating"
                @click="sendMessage(sq)"
              >{{ sq }}</button>
            </div>
          </div>

          <!-- 实时对话线程：累积展示本会话的每一轮问答。
               结构与历史回放**刻意保持一致**（.hist-block > 用户气泡 + .hist-shell > 回答卡片）：
               问题只以气泡形式出现一次，卡片里不再重复写一遍「问：…」。 -->
          <!-- 本轮提问（乐观渲染）：用户气泡 + 分步进度，贴在对话流**最末尾**。
               进度就在提问气泡正下方——不跑到页面顶部或中间，因为答案会出现在这里。
               回答到达后这一块被真正的 turn 接管，位置不变，视觉上无缝衔接。
               进度由真实的 await 边界驱动（见 AnswerProgress 组件），不是定时器。
               失败时这块会停住显示「生成失败，请重试」，所以 verify() 的 finally
               只在成功时清空 pendingQuestion。 -->
          <div v-if="pendingQuestion" class="hist-block">
            <div class="hist-user">{{ pendingQuestion }}</div>
            <AnswerProgress v-if="isGenerating || genFailed" :step="aiStep" :failed="genFailed" />
          </div>
        </div>

        <!-- 底部输入区（固定在底部） -->
        <div class="composer">
          <!-- 用户往上翻看历史时新回答不会打断他，给一个回到底部的入口 -->
          <button v-if="!atBottom" class="to-bottom" type="button" @click="scrollToBottom">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 5v14M6 13l6 6 6-6" />
            </svg>
            回到最新
          </button>

          <!-- 引导文案：回答完成后提示用户还能继续追问，避免「答完即死胡同」 -->
          <p class="composer-guide" v-show="toast.visible && !toast.warn">
            若上述回答未解决您的疑问，请继续追问，或点击下方按钮。
          </p>
          <!-- Toast 提示 -->
          <div class="toast" :class="{ warn: toast.warn }" v-show="toast.visible">{{ toast.text }}</div>

          <!-- 输入框：生成期间禁用，避免用户以为还能改「已经发出去的那句话」——
               问题一发出，它就不属于输入框了，改它也不会影响在跑的那一轮 -->
          <div class="input-row" :class="{ busy: isGenerating }">
            <input
              ref="inputEl"
              v-model="input"
              class="chat-input"
              type="text"
              :disabled="isGenerating"
              placeholder="输入民族健康问题，例如：白族人群糖尿病患病情况如何？"
              @input="onInput"
              @keydown.enter.prevent="onPrimary"
            />
            <button class="btn btn-solid btn-rect send-btn" :class="{ ok: btnState === 'ok' }" type="button" @click="onPrimary" :disabled="isGenerating">
              {{ isGenerating ? '处理中…' : '发送' }}
            </button>
          </div>

          <p class="composer-hint">本产品仅提供健康知识参考，不作为诊疗建议。医疗决策请咨询专业医师。回答基于已纳入的研究资料并标注数据来源。</p>
        </div>
      </main>
    </div>

    <!-- 证据原文：点证据片段后打开整份文献并定位。
         展示什么由知识库分区决定（原始文献给 PDF 原页、整合资料给正文高亮），
         那套判断在 EvidenceSourceViewer 里，问答页与后台共用同一份实现 -->
    <el-dialog
      v-model="evidenceDocVisible"
      :title="evidenceDocTitle || '文献原文'"
      width="72%"
      top="6vh"
      destroy-on-close
    >
      <EvidenceSourceViewer
        v-if="evidenceDocId"
        :doc-id="evidenceDocId"
        :title="evidenceDocTitle"
        :partition="evidenceDocPartition"
        :page="evidenceDocPage"
        :evidence="evidenceDocItems"
        :focus-index="evidenceFocusIndex"
      />
      <div v-else class="lit-note">这条证据没有可定位的原文。</div>
    </el-dialog>
  </div>
</template>

<style scoped>
.qa {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ========== 整体布局 ========== */
.chat-wrap {
  display: flex;
  flex: 1;
  overflow: hidden;
}

/* ========== 左侧边栏 ========== */
.sidebar {
  width: 228px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--line);
  background: var(--bg);
  overflow-y: auto;
}

/* 新建对话：形制来自 .btn .btn-soft，这里只管它在侧栏里的尺寸与间距 */
.new-btn {
  width: calc(100% - 20px);
  margin: 16px 10px 12px;
  border-radius: var(--r-sm);
  padding: 10px 0;
}

/* 列表外层：只为承载「生成期间」的遮罩，本身不滚动。
   min-height:0 是必须的——flex 子项默认 min-height:auto，不加的话
   列表内容再长也不会收缩，内层滚动就失效了 */
.chat-list-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.chat-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0 8px 16px;
  flex: 1;
  overflow-y: auto;
}
/* 生成期间列表变灰。透明度与 .btn:disabled 取同一档，
   让「不可用」在全站看起来是一致的 */
.chat-list.locked { opacity: 0.55; pointer-events: none; }
/* 遮罩：接住穿透过来的点击，说明为什么点不动 */
.chat-lock {
  position: absolute;
  inset: 0;
  z-index: 2;
  cursor: not-allowed;
}
.chat-item {
  display: flex;
  align-items: center;
  gap: 6px;
  text-align: left;
  border: none;
  background: transparent;
  border-radius: 8px;
  padding: 10px 12px;
  cursor: pointer;
  transition: background 0.12s;
  width: 100%;
  box-sizing: border-box;
}
.conv-main {
  flex: 1;
  min-width: 0;
}
.chat-item:hover {
  background: rgba(0, 0, 0, 0.04);
}
.chat-item.on {
  background: rgba(160, 82, 45, 0.08);
}
.conv-title {
  font-size: 14px;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.4;
}
/* 时间线分组标题：小字、浅色，靠上下留白做视觉分隔，不抢会话条目的注意力 */
.conv-group {
  font-size: 11.5px;
  font-weight: 500;
  letter-spacing: 0.06em;
  color: var(--ink-3);
  padding: 16px 12px 6px;
  user-select: none;
  position: sticky;
  top: 0;
  background: var(--bg);
  z-index: 1;
}
.conv-group:first-child {
  padding-top: 6px;
}

.conv-meta {
  font-size: 11.5px;
  color: var(--ink-3);
  margin-top: 2px;
}

.conv-actions {
  display: none;
  flex-shrink: 0;
  align-items: center;
  gap: 2px;
}
.chat-item:hover .conv-actions {
  display: flex;
}

/* 对话线程：每一轮 = 用户气泡 + 回答卡片。
   历史回放和本会话新增的回合共用这一套样式（数据层已由 thread 合并） */
.hist-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.hist-block + .hist-block {
  margin-top: 26px;
}
/* 历史问答外壳：浅浅一层，把每一组问答圈成一个可点击的整体 */
.hist-shell {
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 6px;
  background: var(--bg-deep);
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}
.hist-shell.clickable {
  cursor: pointer;
}
.hist-shell.clickable:hover {
  border-color: var(--clay);
  background: var(--clay-soft);
  box-shadow: var(--shadow-sm);
}
/* 卡片自己不再描边：边框交给外壳，避免出现双层框 */
.hist-shell .answer-card {
  border-color: transparent;
  box-shadow: none;
}
/* 「查看后台分析」的样式来自 .btn .btn-sage，不再单独定义 */
.hist-user {
  align-self: flex-end;
  max-width: 78%;
  background: var(--clay-deep);
  color: #fff;
  padding: 11px 16px;
  border-radius: 16px 16px 4px 16px;
  font-size: 15px;
  line-height: 1.7;
  margin-bottom: 14px;
}

/* ========== 右侧主区 ========== */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
  background: var(--bg);
}

.main-header {
  flex-shrink: 0;
  padding: 18px 32px 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}
.main-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--ink);
  margin: 0;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 会话级操作栏：作用于整个对话框，不是某一条回答。
   按钮形制来自 .btn 分级，这里只管排布 */
.conv-bar { display: flex; align-items: center; gap: 8px; flex: none; }

/* 滚动内容区 */
.scroller {
  flex: 1;
  overflow-y: auto;
  padding: 8px 32px 16px;
}

/* ========== 欢迎区 ========== */
.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  padding: 40px 20px;
}
.welcome-title {
  font-family: var(--serif);
  font-size: 26px;
  font-weight: 700;
  color: var(--ink);
  text-align: center;
  margin: 0 0 14px;
  letter-spacing: 0.5px;
}
.welcome-desc {
  font-size: 14.5px;
  color: var(--ink-2);
  text-align: center;
  max-width: 560px;
  line-height: 1.7;
  margin: 0 0 28px;
}

/* 建议问题芯片：形制来自 .btn .btn-outline，这里只管排布 */
.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
  max-width: 680px;
}

/* ========== 底部输入区 ========== */
.composer {
  position: relative;
  flex-shrink: 0;
  padding: 12px 32px 18px;
  background: var(--bg);
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

/* 「回到最新」：浮在输入区上沿，用户往上翻看时才出现 */
.to-bottom {
  position: absolute;
  top: -38px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 5;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink-2);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 6px 14px;
  cursor: pointer;
  box-shadow: var(--shadow-md);
  transition: border-color 0.15s, color 0.15s;
}
.to-bottom:hover {
  border-color: var(--clay);
  color: var(--clay-deep);
}

/* 回答完成后的引导文案（位于绿条上方） */
.composer-guide {
  text-align: center;
  font-size: 12.5px;
  color: var(--ink-3);
  margin-bottom: 8px;
}

.toast {
  text-align: center;
  color: var(--sage-deep);
  background: var(--sage-soft);
  border: 1px solid var(--sage-line);
  border-radius: 999px;
  padding: 8px 16px;
  font-size: 13.5px;
  margin-bottom: 10px;
}
.toast.warn {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: var(--danger-line);
}

/* 输入行 */
.input-row {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 6px 6px 6px 18px;
  box-shadow: var(--shadow-sm);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.input-row:focus-within {
  border-color: var(--clay-deep);
  box-shadow: 0 0 0 3px rgba(160, 82, 45, 0.1);
}
/* 生成期间：整行退回纸底色、去掉阴影，明确表达「现在不能输入」。
   此时输入框已被清空、按钮显示「处理中…」，三处一起构成禁用信号。 */
.input-row.busy {
  background: var(--bg);
  box-shadow: none;
}
.chat-input:disabled {
  cursor: not-allowed;
  color: var(--ink-3);
}
.chat-input {
  flex: 1;
  border: 0;
  background: transparent;
  font-size: 15px;
  font-family: var(--sans);
  color: var(--ink);
  outline: none;
  line-height: 1.5;
  min-width: 0;
}
.chat-input::placeholder {
  color: var(--ink-3);
}
/* 发送键：形制沿用 .btn .btn-solid .btn-rect，但「问题还没通过校验」时压成暖灰，
   校验通过（.ok）才亮成陶土红——颜色本身承担了「现在能不能发」的提示 */
.send-btn {
  flex-shrink: 0;
  padding: 9px 22px;
  background: var(--muted-btn);
  border-color: var(--muted-btn);
}
.send-btn:hover:not(:disabled) {
  background: var(--muted-btn-hover);
  border-color: var(--muted-btn-hover);
}
.send-btn.ok {
  background: var(--clay-deep);
  border-color: var(--clay-deep);
}
.send-btn.ok:hover:not(:disabled) {
  background: var(--clay-deep);
  border-color: var(--clay-deep);
}

.composer-hint {
  text-align: center;
  color: var(--ink-3);
  font-size: 11.5px;
  margin-top: 8px;
  line-height: 1.5;
}

/* ========== 回答区 ========== */
/* 历史回放与本会话新增回合共用这一个容器，轮次间距自然一致 */
.answer-area {
  margin-bottom: 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 处理中指示器（.thinking / .thinking-dots / 换步动画）已抽成共享组件
   AnswerProgress.vue —— 高级检索页要用同一套，抄一份必然会漂移。 */
.answer-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: var(--shadow-sm);
  padding: 22px 26px;
}
.answer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.answer-title {
  font-family: var(--serif);
  font-weight: 700;
  font-size: 17px;
  color: var(--ink);
}
.answer-badge {
  font-size: 11.5px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--sage-soft);
  color: var(--sage-deep);
  border: 1px solid var(--sage-line);
}
/* 「已反馈」用陶土色而不是鼠尾草绿：它与「有依据·可溯源」是两回事
   （那个说的是答案本身，这个说的是用户做过什么），同色会看成一枚标签的两段 */
.fb-badge {
  background: var(--clay-soft);
  color: var(--clay-deep);
  border-color: var(--accent-line);
}

/* 未命中时的反馈入口：结论之后、追问之前 —— 用户刚读完「没查到」，正是该问他要不要补的位置 */
.fb-prompt {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
  padding: 11px 14px;
  border-radius: var(--r-sm);
  background: var(--bg);
  border: 1px dashed var(--line-strong);
}
.fb-prompt-text {
  flex: 1;
  min-width: 200px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-2);
}

/* 依据文献展开后的条目 */
.evidence-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.evidence-src-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.evidence-src {
  color: var(--clay-deep);
  font-weight: 600;
  border: 0;
  background: none;
  padding: 0;
  font-family: var(--sans);
  font-size: 13px;
  cursor: pointer;
}
.evidence-src:hover {
  text-decoration: underline;
}
.evidence-src-text {
  font-weight: 600;
  color: var(--ink-2);
  font-size: 13px;
}
.evidence-text {
  font-size: 13.5px;
  color: var(--ink-2);
  line-height: 1.85;
  background: #fff;
  border-left: 3px solid var(--clay-deep);
  padding: 10px 14px;
  border-radius: 0 8px 8px 0;
}
/* 可定位到原文的片段：可点击，悬停给出反馈 */
.evidence-text.locatable {
  cursor: pointer;
  transition: background 0.15s, box-shadow 0.15s;
}
/* 命中词高亮（v-html 注入，需 :deep 才能穿透 scoped） */
.evidence-text :deep(mark) {
  background: rgba(255, 214, 102, 0.45);
  color: inherit;
  padding: 0 1px;
  border-radius: 3px;
}
.evidence-text.locatable::after {
  content: '查看原文 →';
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: var(--clay-deep);
}
.evidence-text.locatable:hover {
  /* 用鼠尾草绿，与卡片外壳 hover 的陶土色区分开：
     同一块区域里两个点击目标，靠冷/暖两色让人一眼分清点哪儿会发生什么 */
  background: var(--sage-soft);
  box-shadow: inset 0 0 0 1px var(--sage);
}
.evidence-hint {
  font-size: 12px;
  color: var(--ink-3);
  margin-bottom: 10px;
}
.evidence-hint.stale {
  color: var(--amber);
  background: var(--amber-soft);
  border: 1px solid var(--amber-line);
  border-radius: 8px;
  padding: 7px 11px;
}
/* 泛化检索提示：民族对、疾病没识别出来，证据是民族整体资料而非专属数据 */
.evidence-fold-tag {
  flex: none;
  margin-left: auto;
  font-size: 11.5px;
  color: var(--amber);
  background: var(--amber-soft);
  border: 1px solid var(--amber-line);
  border-radius: 999px;
  padding: 1px 9px;
}
.evidence-degrade {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--amber);
  background: var(--amber-soft);
  border: 1px solid var(--amber-line);
  border-radius: 8px;
  padding: 9px 12px;
  margin-bottom: 10px;
}

/* 依据文献：可折叠，默认收起只露文献名，点开才展开原文 */
.evidence-fold {
  margin-bottom: 16px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
  overflow: hidden;
}
.evidence-fold-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  text-align: left;
  border: 0;
  background: none;
  padding: 10px 14px;
  font-family: var(--sans);
  font-size: 13px;
  color: var(--ink-2);
  cursor: pointer;
}
.evidence-fold-head:hover { color: var(--clay-deep); }
.evidence-fold-caret {
  flex: none;
  font-size: 11px;
  color: var(--ink-3);
  transition: transform 0.15s;
}
.evidence-fold-caret.open { transform: rotate(90deg); }
.evidence-fold-label { flex: none; font-weight: 600; color: var(--ink); }
.evidence-fold-names {
  color: var(--clay-deep);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.evidence-fold-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 14px 14px;
}

/* 结论 */
.answer-conclusion {
  font-size: 15px;
  line-height: 1.85;
  color: var(--ink);
}
.answer-conclusion :deep(.key) {
  color: var(--clay-deep);
  font-weight: 700;
}
.answer-conclusion :deep(.answer-text) {
  font-size: 15px;
  line-height: 1.85;
  color: var(--ink);
}
/* 列表：结论与正文都可能出现（危险因素/症状/预防/饮食/遗传这几个方面，
   提示词要求用无序列表作答）。这一份 scoped 拷贝与全局 answer.css 里的同一条对应，
   值保持一致——不把 .answer-body 加到这个容器上，是为了不动对话页现有的排版。 */
.answer-conclusion :deep(.ans-list) {
  margin: 10px 0 4px;
  padding-left: 22px;
}
.answer-conclusion :deep(.ans-list li) {
  margin-bottom: 6px;
  line-height: 1.9;
}
.answer-conclusion :deep(.ans-list li::marker) {
  color: var(--clay-deep);
}
/* 区块之间留够空气：标题变轻之后，靠间距而不是靠字号来分节 */
.answer-conclusion :deep(.ans-block),
.answer-conclusion :deep(.ans-sec) {
  margin-top: 22px;
}
/* 区块标题：它只是分隔，不该和正文抢注意力。
   此前三个区块标题都是 14.5px 加粗纯黑、与正文同字号，读起来像表单字段、
   而不是一篇文章的分节。改成「小字号 + 主色 + 左侧短竖线」的标记式写法：
   层级靠颜色和那道竖线建立，不靠字号和加粗堆叠。
   色值用 --clay-deep 而非 --clay：13px 小字要保证对比度（6.4:1，过 AA）。 */
.answer-conclusion :deep(.ans-sec-title) {
  font-size: 13px;
  font-weight: 600;
  color: var(--clay-deep);
  letter-spacing: 0.02em;
  line-height: 1.5;
  padding-left: 9px;
  border-left: 2px solid var(--clay);
  margin-bottom: 7px;
}
.answer-conclusion :deep(.ans-sec-body) {
  color: var(--ink-2);
  line-height: 1.85;
  font-size: 14.5px;
}
.answer-conclusion :deep(.ans-table) {
  margin-top: 14px;
  border-collapse: collapse;
  width: 100%;
  max-width: 520px;
  font-size: 13px;
}
.answer-conclusion :deep(.ans-table th),
.answer-conclusion :deep(.ans-table td) {
  border: 1px solid var(--sage-line);
  padding: 7px 12px;
  text-align: center;
  background: #fff;
}
.answer-conclusion :deep(.ans-table th) {
  background: rgba(160, 82, 45, 0.07);
  color: var(--ink-2);
  font-weight: 600;
}
.answer-conclusion :deep(.ans-chart) {
  margin-top: 16px;
}
.answer-conclusion :deep(.ans-chart-title) {
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 10px;
  font-size: 14px;
}
.answer-conclusion :deep(.ans-bar-row) {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 7px;
}
.answer-conclusion :deep(.ans-bar-label) {
  flex: none;
  width: 120px;
  font-size: 12.5px;
  color: var(--ink-2);
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.answer-conclusion :deep(.ans-bar-track) {
  flex: 1;
  background: var(--bg);
  border-radius: 6px;
  height: 14px;
  overflow: hidden;
}
.answer-conclusion :deep(.ans-bar-fill) {
  height: 100%;
  background: var(--clay-deep);
  border-radius: 6px;
}
.answer-conclusion :deep(.ans-bar-val) {
  flex: none;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--clay-deep);
  min-width: 52px;
}
.answer-conclusion :deep(.ans-note) {
  margin-top: 14px;
  font-size: 12.5px;
  color: var(--ink-3);
  background: var(--bg);
  border-radius: 8px;
  padding: 9px 12px;
  line-height: 1.7;
}
.answer-conclusion :deep(.ans-note.warn) {
  color: var(--danger);
  background: var(--danger-soft);
}
.answer-conclusion :deep(.ans-scope-row) {
  font-size: 13.5px;
  color: var(--ink-2);
  line-height: 1.85;
  margin-top: 4px;
}
.answer-conclusion :deep(.ans-scope-row b) {
  color: var(--ink);
}

/* 您可能还想问 追问 */
.ans-followups {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}
.ans-followups-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 10px;
}
.followup-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* 单条问答卡片的「查看后台分析」按钮：排布而已，形制来自 .btn */
.add-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
}

.lit-note {
  font-size: 13px;
  color: var(--ink-3);
  padding: 10px 14px;
  background: var(--bg);
  border-radius: 8px;
}

/* 表格通用 */
.evidence :deep(.lit-table),
.answer-conclusion :deep(.lit-table) {
  border-collapse: collapse;
  width: 100%;
  max-width: 420px;
  font-size: 13px;
}
.evidence :deep(.lit-table th),
.evidence :deep(.lit-table td),
.answer-conclusion :deep(.lit-table th),
.answer-conclusion :deep(.lit-table td) {
  border: 1px solid var(--sage-line);
  padding: 7px 12px;
  text-align: center;
  background: #fff;
}
.evidence :deep(.lit-table th),
.answer-conclusion :deep(.lit-table th) {
  background: rgba(160, 82, 45, 0.07);
  color: var(--ink-2);
  font-weight: 600;
}
.evidence :deep(.lit-table .hl) {
  background: var(--amber-soft);
  font-weight: 600;
  color: var(--clay-deep);
}

/* ========== 窄屏适配 ========== */
@media (max-width: 768px) {
  .sidebar {
    width: 180px;
  }
  .main-header,
  .scroller,
  .composer {
    padding-left: 16px;
    padding-right: 16px;
  }
  .welcome-title {
    font-size: 21px;
  }
  .suggestions {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
