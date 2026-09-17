<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppTopbar from '@/components/AppTopbar.vue'
import { getGap } from '@/api/gap'
import type { GapItem } from '@/api/gap'
import { extractSupplyRows, getKbVocab, previewSupply, submitSupply } from '@/api/kb'
import type { SupplyDataRow } from '@/api/kb'
import { intentLabel, intentSearchWord } from '@/api/qa'
import { ETHNICS, DISEASES } from '@/utils/medicalVocab'
import { LITERATURE_SITES } from '@/utils/literatureSites'
import { parseContentBlocks, type ContentBlock } from '@/utils/contentBlocks'

/**
 * 文献补录工作台
 *
 * 只处理一种场景：**找不到完整论文，只有零散片段**——把多篇文献里复制来的片段
 * 攒成一份结构化文档，提交进知识库待审核。
 * 完整论文的上传走知识库现有的「添加资料」模块，不经过这里。
 *
 * 流程：看缺口 → 找文献 → 逐段粘贴（每段独立标来源、可排序/编辑/删除）
 * → 【整理成文档】（服务端渲染出 Markdown / Word，先看后交）→ 提交入库（进待审核）。
 */
const route = useRoute()
const router = useRouter()

const gapId = computed(() => {
  const n = Number(route.query.gap)
  return Number.isFinite(n) && n > 0 ? n : null
})

const gap = ref<GapItem | null>(null)
const loading = ref(true)
const denied = ref(false)

/** 检索词：民族 + 疾病 + 方面的短名（「遗传相关研究」→「遗传」，长词会拖低召回） */
const keyword = ref('')
/** 该方面在检索时**必须出现**的锚点词（服务端下发），粘贴正文时最好照着包含 */
const anchorWords = ref<string[]>([])

// ---------- 取正文与抽取 ----------
/** 待抽取的正文（粘贴），以及这一篇的来源文献（用于回填数据行的来源列） */
const pasteText = ref('')
const sourceText = ref('')
const extracting = ref(false)
/** 最近一次抽取用的原文，折叠展示，供管理员核对「抽得对不对」 */
const fetchedText = ref('')

// ---------- 结构化表单 ----------
// 文档正文由这三样渲染而成：一张数据表 + 两份列表。不再把粘贴的原文直接排进文档——
// 那样读者看到的是一堵文字墙，分不清哪条是患病率、哪条是危险因素。

/**
 * 文档级「研究信息 / 适用范围」：回答据此说明「该数据适用于哪个地区、什么年龄段」。
 * 四个字段全部必填——补录的整合文档要作为依据进回答，缺了适用范围就等于没了边界，
 * 这正是「证据来源 / 适用范围 / 使用边界」三件套里「适用范围」一栏的元数据来源。
 */
type ScopeKey = 'region' | 'ageRange' | 'sampleSize' | 'year'
const scopeFields: { key: ScopeKey; label: string; placeholder: string }[] = [
  { key: 'region', label: '研究地区', placeholder: '如「云南大理」' },
  { key: 'ageRange', label: '年龄范围', placeholder: '如「≥18岁」' },
  { key: 'sampleSize', label: '样本量', placeholder: '如「5439人」' },
  { key: 'year', label: '研究年份', placeholder: '如「2022年」' },
]
const scope = reactive<Record<ScopeKey, string>>({ region: '', ageRange: '', sampleSize: '', year: '' })

/** 缺失的适用范围字段名；非空即「缺少适用范围信息，无法提交」 */
const scopeMissing = computed(() => scopeFields.filter((f) => !scope[f.key].trim()).map((f) => f.label))

/** 数据表的一行（界面形态：全字符串，空的就原样发，服务端会丢掉没有数值的行） */
function blankRow(): SupplyDataRow {
  return {
    ethnicity: gap.value?.ethnicity || '',
    region: '',
    ageRange: '',
    sampleSize: '',
    metric: '',
    value: '',
    // 来源默认继承「本篇来源文献」：一次抽取抽出的几行同属一篇，来源自然是同一个
    source: sourceText.value.trim(),
  }
}

const rows = ref<SupplyDataRow[]>([])
const risks = ref<string[]>([])
const advice = ref<string[]>([])

function addRow() {
  rows.value.push(blankRow())
}

function removeRow(i: number) {
  rows.value.splice(i, 1)
}

/** 数据表的列定义：顺序即表格列顺序，与文档里渲染出来的表一致 */
const DATA_COLUMNS: { key: keyof SupplyDataRow; label: string; w: string }[] = [
  { key: 'ethnicity', label: '民族', w: '72px' },
  { key: 'region', label: '研究地区', w: '96px' },
  { key: 'ageRange', label: '年龄范围', w: '88px' },
  { key: 'sampleSize', label: '样本量', w: '80px' },
  { key: 'metric', label: '指标类型', w: '96px' },
  { key: 'value', label: '数值', w: '84px' },
  { key: 'source', label: '来源文献', w: '150px' },
]

/** 该行有数值却没填来源——整合资料的价值就在可追溯，这样的行不能提交 */
function rowNeedsSource(r: SupplyDataRow): boolean {
  return r.value.trim() !== '' && r.source.trim() === ''
}

/** 会进文档的行（带数值的）；没填数值的空行只是占位，服务端也会丢掉 */
const validRows = computed(() => rows.value.filter((r) => r.value.trim() !== ''))
const readyCount = computed(() => validRows.value.length)

/** 有数值但没来源的行数：整合资料的价值就是可追溯，缺来源不能提交 */
const missingSource = computed(
  () => rows.value.filter((r) => r.value.trim() !== '' && r.source.trim() === '').length,
)

/**
 * 取正文 → LLM 抽成「数据行 + 危险因素 + 专家建议」→ 追加到下方表单。
 *
 * 抽不到不报错、只提示：那通常意味着这段文字里没有可整理的结构化内容，
 * 管理员可以直接手工加行。
 */
async function extract() {
  const text = pasteText.value.trim()
  if (!text) {
    ElMessage.warning('先粘贴一段文献正文')
    return
  }
  if (extracting.value) return
  extracting.value = true
  try {
    const res = await extractSupplyRows({
      text,
      ethnicity: gap.value?.ethnicity,
      disease: gap.value?.disease,
    })
    fetchedText.value = text
    const fallbackSource = sourceText.value.trim()
    for (const r of res.rows) {
      // 年份并进来源文献：表格里没有年份列，但它恰恰是溯源时最常要的信息
      const year = (r.year || '').trim()
      let src = (r.source || '').trim() || fallbackSource
      if (year && src && !src.includes(year)) src = `${src}（${year}）`
      rows.value.push({
        ethnicity: (r.ethnicity || '').trim() || gap.value?.ethnicity || '',
        region: (r.region || '').trim(),
        ageRange: (r.ageRange || '').trim(),
        sampleSize: (r.sampleSize || '').trim(),
        metric: (r.metric || '').trim(),
        value: (r.value || '').trim(),
        source: src,
      })
    }
    for (const s of res.risks) if (!risks.value.includes(s)) risks.value.push(s)
    for (const s of res.advice) if (!advice.value.includes(s)) advice.value.push(s)

    // 清空本次输入：不清的话，接着粘第二篇时框里还留着上一篇，再点抽取会重复抽一遍
    pasteText.value = ''
    sourceText.value = ''

    if (!res.rows.length && !res.risks.length && !res.advice.length) {
      ElMessage.warning(
        res.dropped
          ? `${res.dropped} 条数据的数值或出处对不上原文，已丢弃。可手工添加。`
          : '这段文字里没抽出可整理的内容。可直接手工添加数据行。',
      )
    } else if (res.dropped) {
      // 明说而不是静默：编出来的数值/出处会被服务端剔掉，但管理员得知道模型抽歪了
      ElMessage.warning(
        `抽出 ${res.rows.length} 条数据；另有 ${res.dropped} 条因对不上原文被丢弃，请核对。`,
      )
    } else {
      ElMessage.success(
        `抽出 ${res.rows.length} 条数据、${res.risks.length} 条危险因素、${res.advice.length} 条建议`,
      )
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '抽取失败，请稍后重试')
  } finally {
    extracting.value = false
  }
}

/** 列表项的新增/删除（危险因素与专家建议共用一套编辑动作） */
function addItem(list: 'risks' | 'advice') {
  if (list === 'risks') risks.value.push('')
  else advice.value.push('')
}
function removeItem(list: 'risks' | 'advice', i: number) {
  if (list === 'risks') risks.value.splice(i, 1)
  else advice.value.splice(i, 1)
}

// ---------- 草稿留存 ----------
/**
 * 表单按缺口 id 存进 localStorage。
 *
 * 补录是个慢活：找文献、登录知网、翻 PDF，中途刷新/误关页面很常见。
 * 不做服务端草稿是因为它是单人（管理员）单缺口的工作区，没有多人协同诉求。
 */
function draftKey(): string {
  return `supply-draft-${gapId.value ?? 'none'}`
}

function saveDraft() {
  if (gapId.value == null) return
  try {
    localStorage.setItem(draftKey(), JSON.stringify({
      scope: { ...scope },
      rows: rows.value,
      risks: risks.value,
      advice: advice.value,
    }))
  } catch {
    /* 隐私模式等存不进去就算了，不阻塞录入 */
  }
}

function loadDraft(): boolean {
  if (gapId.value == null) return false
  try {
    const raw = localStorage.getItem(draftKey())
    if (!raw) return false
    const d = JSON.parse(raw) as {
      scope?: Partial<Record<ScopeKey, string>>
      rows?: SupplyDataRow[]
      risks?: string[]
      advice?: string[]
    }
    // 与 blankRow() 合并：草稿是老版本存的、少了新增的列时，缺的字段各有默认值而不是 undefined
    rows.value = (d.rows || []).map((r) => ({ ...blankRow(), ...r }))
    risks.value = d.risks || []
    advice.value = d.advice || []
    Object.assign(scope, { region: '', ageRange: '', sampleSize: '', year: '' }, d.scope || {})
    return rows.value.length > 0 || risks.value.length > 0 || advice.value.length > 0
      || scope.region.trim() !== '' || scope.ageRange.trim() !== ''
      || scope.sampleSize.trim() !== '' || scope.year.trim() !== ''
  } catch {
    return false
  }
}

function clearDraft() {
  if (gapId.value != null) localStorage.removeItem(draftKey())
  scope.region = ''
  scope.ageRange = ''
  scope.sampleSize = ''
  scope.year = ''
  rows.value = []
  risks.value = []
  advice.value = []
  preview.value = null
}

/** 表单一变，上一次「整理成文档」的结果就过期了——不标出来，管理员会拿旧预览去提交 */
watch([rows, risks, advice, scope], () => {
  saveDraft()
  if (preview.value) stale.value = true
}, { deep: true })

// ---------- 整理成文档 ----------
/** 服务端渲染出来的整合文档（与入库那份逐字一致） */
const preview = ref<{ title: string; markdown: string; html: string; count: number } | null>(null)
const stale = ref(false)
const assembling = ref(false)
const submitting = ref(false)

/**
 * 预览视角：kb = 知识库阅读器排版（入库后读者看到的样子）；md = Markdown 源码。
 * 解析用 utils/contentBlocks 的同一份 parseContentBlocks——知识库阅读页就是它渲染的，
 * 这里不另写一份，预览效果才和「真的入库之后」逐块一致。
 */
const previewMode = ref<'kb' | 'md'>('kb')
const previewBlocks = computed<ContentBlock[]>(() => parseContentBlocks(preview.value?.markdown || ''))

async function assemble() {
  if (!gap.value || assembling.value) return
  if (readyCount.value === 0) {
    ElMessage.warning('至少要有一行填了数值的数据')
    return
  }
  if (scopeMissing.value.length) {
    ElMessage.warning(`缺少适用范围信息：${scopeMissing.value.join('、')}未填写，无法提交`)
    return
  }
  if (missingSource.value) {
    ElMessage.warning(`有 ${missingSource.value} 行数据没填来源文献，补齐后再整理（表格里已标红）`)
    return
  }
  assembling.value = true
  try {
    preview.value = await previewSupply({
      gapId: gapId.value ?? undefined,
      ethnicity: gap.value.ethnicity,
      disease: gap.value.disease,
      intent: gap.value.intent,
      region: scope.region.trim(),
      ageRange: scope.ageRange.trim(),
      sampleSize: scope.sampleSize.trim(),
      year: scope.year.trim(),
      rows: rows.value,
      risks: risks.value,
      advice: advice.value,
    })
    stale.value = false
    ElMessage.success(`已整理成文档（${preview.value.count} 行数据），核对后提交入库`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '整理失败，请稍后重试')
  } finally {
    assembling.value = false
  }
}

async function copyMarkdown() {
  if (!preview.value) return
  try {
    await navigator.clipboard.writeText(preview.value.markdown)
    ElMessage.success('Markdown 已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选中文本复制')
  }
}

function saveBlob(text: string, filename: string, mime: string) {
  // BOM：Windows 的记事本/Word 认 UTF-8 要靠它，缺了会按 ANSI 解出乱码
  const blob = new Blob(['\ufeff' + text], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

function fileNameOf(ext: string): string {
  const t = (preview.value?.title || '资料整合').replace(/[\\/:*?"<>|]/g, '')
  return `${t}.${ext}`
}

function downloadMd() {
  if (!preview.value) return
  saveBlob(preview.value.markdown, fileNameOf('md'), 'text/markdown;charset=utf-8')
}

function downloadDoc() {
  if (!preview.value) return
  // Word 能直接打开的 HTML 套壳，由服务端渲染（与 Markdown 同一份内容）
  saveBlob(preview.value.html, fileNameOf('doc'), 'application/msword;charset=utf-8')
}

async function submit() {
  if (!gap.value || submitting.value) return
  if (!preview.value) {
    ElMessage.warning('先点「整理成文档」看看合成后的样子')
    return
  }
  if (scopeMissing.value.length) {
    ElMessage.warning(`缺少适用范围信息：${scopeMissing.value.join('、')}未填写，无法提交`)
    return
  }
  if (missingSource.value) {
    ElMessage.warning(`有 ${missingSource.value} 行数据没填来源文献，补齐后再提交（表格里已标红）`)
    return
  }
  submitting.value = true
  try {
    await submitSupply({
      gapId: gapId.value ?? undefined,
      ethnicity: gap.value.ethnicity,
      disease: gap.value.disease,
      intent: gap.value.intent,
      title: preview.value.title,
      region: scope.region.trim(),
      ageRange: scope.ageRange.trim(),
      sampleSize: scope.sampleSize.trim(),
      year: scope.year.trim(),
      rows: rows.value,
      risks: risks.value,
      advice: advice.value,
    })
    ElMessage.success('已提交，请到知识库的「待审核」里核对后通过')
    clearDraft()
    router.push({ name: 'kb', query: { tab: 'pending' } })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

// ---------- 初始化 ----------
async function load() {
  loading.value = true
  if (gapId.value == null) {
    loading.value = false
    return
  }
  try {
    const g = await getGap(gapId.value)
    gap.value = g
    keyword.value = `${g.ethnicity} ${g.disease} ${intentSearchWord(g.intent)}`
  } catch {
    denied.value = true
  } finally {
    loading.value = false
  }
  // 锚点词单独拉：拉不到不影响补录，只是少一条提示
  try {
    const vocab = await getKbVocab(ETHNICS, DISEASES)
    anchorWords.value = vocab.intentKeywords?.[gap.value?.intent || ''] || []
  } catch {
    /* 词表不通就不显示提示 */
  }
  if (loadDraft()) {
    ElMessage.info(
      `已恢复上次的草稿（${rows.value.length} 行数据、${risks.value.length + advice.value.length} 条列表项）`,
    )
  }
}

async function copyKeyword() {
  try {
    await navigator.clipboard.writeText(keyword.value)
    ElMessage.success('检索词已复制，可直接粘进站内搜索框')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选中上面的检索词')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <AppTopbar />

    <main class="main">
      <div class="work">
        <div class="page-head">
          <div>
            <h1 class="page-title">文献补录工作台</h1>
            <p class="page-sub">找不到完整论文时，把多篇文献的零散片段攒成一份资料补进知识库</p>
          </div>
          <button class="btn btn-sm btn-outline" type="button" @click="router.push({ name: 'demand' })">
            ← 需求分析
          </button>
        </div>

        <div v-if="loading" class="card box"><div class="hint">加载中…</div></div>

        <div v-else-if="denied || !gap" class="card box">
          <div class="empty-state">
            <p class="empty-state-title">找不到这条缺口</p>
            <p class="empty-state-desc">
              它可能已经被补充并从榜单移除，或者你不是管理员。回到<button class="btn btn-link" type="button" @click="router.push({ name: 'demand' })">需求分析</button>看看还有哪些待补充。
            </p>
          </div>
        </div>

        <template v-else>
          <!-- ① 在补哪个缺口 -->
          <section class="card box">
            <div class="box-title">在补这个缺口</div>
            <div class="slot-row">
              <span class="slot"><em>民族</em>{{ gap.ethnicity }}</span>
              <span class="slot"><em>疾病</em>{{ gap.disease }}</span>
              <span class="slot"><em>方面</em>{{ intentLabel(gap.intent) }}</span>
              <span class="slot"><em>被问</em>{{ gap.askCount }} 次</span>
              <span class="slot"><em>反馈</em>{{ gap.feedbackCount }} 人</span>
            </div>
            <p class="box-note">
              手上有<b>完整论文</b>（PDF / Word）？请到
              <button class="btn btn-link" type="button" @click="router.push({ name: 'kb' })">知识库 · 添加资料</button>
              直接上传，那边会自动识别作者、出处、年份——本工作台只用于「只有零散片段」的情况。
            </p>
          </section>

          <!-- ② 研究信息（适用范围） -->
          <section class="card box">
            <div class="box-title">研究信息（适用范围）</div>
            <p class="box-note">
              这四个字段会写进文档，回答时据此说明「该数据适用于哪个地区、什么年龄段」。全部必填。
            </p>
            <div class="scope-grid">
              <label v-for="f in scopeFields" :key="f.key" class="field">
                <span class="field-label">{{ f.label }}<em class="req">*</em></span>
                <input
                  v-model="scope[f.key]"
                  class="input"
                  :class="{ missing: !scope[f.key].trim() }"
                  type="text"
                  :placeholder="f.placeholder"
                />
              </label>
            </div>
            <p v-if="scopeMissing.length" class="box-note warn">
              缺少适用范围信息：{{ scopeMissing.join('、') }}未填写，无法提交。
            </p>
          </section>

          <!-- ③ 去哪儿找 -->
          <section class="card box">
            <div class="box-title">去这些站点找文献</div>
            <div class="kw-row">
              <input v-model="keyword" class="input" type="text" />
              <button class="btn btn-sm btn-outline" type="button" @click="copyKeyword">复制</button>
            </div>
            <div class="site-row">
              <a
                v-for="s in LITERATURE_SITES"
                :key="s.name"
                class="btn btn-sm btn-soft"
                :href="s.build(keyword)"
                target="_blank"
                rel="noopener noreferrer"
              >{{ s.name }} 检索 ›</a>
            </div>
            <p class="box-note">
              SinoMed 的检索需要登录与验证码，服务端抓不到它的页面 —— 请在浏览器里检索后，把需要的段落<strong>粘贴</strong>到下面的片段区。
            </p>
          </section>

          <!-- ④ 取正文并抽取 -->
          <section class="card box">
            <div class="box-head">
              <div class="box-title">取正文并抽取</div>
              <div class="head-actions">
                <button
                  v-if="rows.length || risks.length || advice.length"
                  class="btn btn-sm btn-outline"
                  type="button"
                  @click="clearDraft"
                >清空</button>
              </div>
            </div>

            <label class="field">
              <span class="field-label">本篇来源文献</span>
              <input
                v-model="sourceText"
                class="input"
                type="text"
                placeholder="期刊名，或「作者+年份」。原文里没写时在这里填一次，会写进本篇抽出的每一行"
              />
            </label>
            <label class="field">
              <span class="field-label">文献正文</span>
              <textarea
                v-model="pasteText"
                class="paste-area"
                rows="6"
                placeholder="把摘要或正文粘贴到这里。系统会抽成下方表格里的数据行、危险因素与专家建议，抽不准的地方你可以逐格修改。"
              ></textarea>
            </label>
            <div class="submit-row">
              <button
                class="btn btn-solid"
                type="button"
                :disabled="extracting || !pasteText.trim()"
                @click="extract"
              >{{ extracting ? '正在抽取…' : '抽取成表格' }}</button>
              <span class="box-note inline">
                一篇里同时报了多个民族或多个指标时会自动拆成多行——那是要的效果，不是重复。
              </span>
            </div>

            <details v-if="fetchedText" class="src-fold">
              <summary>查看本次抽取用的原文（{{ fetchedText.length }} 字）</summary>
              <pre class="src-text">{{ fetchedText.slice(0, 3000) }}{{ fetchedText.length > 3000 ? '\n…（仅显示前 3000 字）' : '' }}</pre>
            </details>

            <!-- 锚点词提示：新入库的切片没有 topic 标签，检索只能靠正文命中这些词。
                 不提示的话，管理员补完、审核通过、再检索依然未命中，而且不会报错。 -->
            <p v-if="anchorWords.length" class="box-note warn">
              正文里最好出现这些词之一，否则入库后检索不到这个方面：<b>{{ anchorWords.join(' / ') }}</b>
            </p>
          </section>

          <!-- ⑤ 数据表 -->
          <section class="card box">
            <div class="box-head">
              <div class="box-title">
                患病率数据<span v-if="validRows.length" class="count">{{ validRows.length }}</span>
              </div>
              <div class="head-actions">
                <button class="btn btn-sm btn-outline" type="button" @click="addRow">+ 手动加一行</button>
              </div>
            </div>

            <div v-if="!rows.length" class="empty-state empty-state-sm">
              <p class="empty-state-title">还没有数据行</p>
              <p class="empty-state-desc">用上面的「抽取成表格」自动生成，也可以直接手动加一行自己填。</p>
            </div>

            <div v-else class="table-wrap">
              <table class="rows-table">
                <thead>
                  <tr>
                    <th class="idx">#</th>
                    <th v-for="c in DATA_COLUMNS" :key="c.key" :style="{ minWidth: c.w }">{{ c.label }}</th>
                    <th class="ops"></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(r, i) in rows" :key="i">
                    <td class="idx">{{ i + 1 }}</td>
                    <td v-for="c in DATA_COLUMNS" :key="c.key">
                      <input
                        v-model="r[c.key]"
                        class="cell"
                        :class="{ missing: c.key === 'source' && rowNeedsSource(r) }"
                        type="text"
                      />
                    </td>
                    <td class="ops">
                      <button class="del" type="button" title="删除这一行" @click="removeRow(i)">✕</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <p v-if="missingSource" class="box-note warn">
              有 {{ missingSource }} 行没填来源文献（表格里标红）。整合资料的价值就在每条数据都能
              追到具体文献，补齐才能整理与提交。
            </p>
          </section>

          <!-- ⑥ 危险因素与专家建议 -->
          <section class="card box">
            <div class="box-title">危险因素与专家建议</div>
            <p class="box-note">
              这两份列表**只摘录原文写了的**。系统不会替你生成任何医疗建议——它是整理工具，不是医生。
            </p>
            <div class="list-grid">
              <div class="list-col">
                <div class="list-head">
                  <span>危险因素</span>
                  <button class="btn btn-sm btn-link" type="button" @click="addItem('risks')">+ 加一条</button>
                </div>
                <p v-if="!risks.length" class="list-empty">原文未提及，或还没抽取</p>
                <div v-for="(s, i) in risks" :key="i" class="list-row">
                  <input v-model="risks[i]" class="input" type="text" />
                  <button class="del" type="button" title="删除" @click="removeItem('risks', i)">✕</button>
                </div>
              </div>
              <div class="list-col">
                <div class="list-head">
                  <span>专家建议</span>
                  <button class="btn btn-sm btn-link" type="button" @click="addItem('advice')">+ 加一条</button>
                </div>
                <p v-if="!advice.length" class="list-empty">原文未提及，或还没抽取</p>
                <div v-for="(s, i) in advice" :key="i" class="list-row">
                  <input v-model="advice[i]" class="input" type="text" />
                  <button class="del" type="button" title="删除" @click="removeItem('advice', i)">✕</button>
                </div>
              </div>
            </div>
            <p class="box-note">
              表单随时自动存草稿（按缺口区分），中途关掉页面不会丢。
            </p>
          </section>

          <!-- ⑦ 整理与提交 -->
          <section class="card box">
            <div class="box-head">
              <div class="box-title">整理与提交</div>
              <div class="head-actions">
                <button
                  class="btn btn-sm btn-solid"
                  type="button"
                  :disabled="assembling || readyCount === 0"
                  @click="assemble"
                >{{ assembling ? '整理中…' : '整理成文档' }}</button>
              </div>
            </div>

            <div v-if="!preview" class="empty-state empty-state-sm">
              <p class="empty-state-title">还没有生成文档</p>
              <p class="empty-state-desc">
                粘贴完所有片段后点「整理成文档」，系统会把它们合成一份结构化文档（Markdown / Word），先给你核对，再提交入库。
              </p>
            </div>

            <template v-else>
              <p v-if="stale" class="box-note warn">
                片段在生成之后有改动，上面的预览已经过期——请重新点「整理成文档」。
              </p>
              <div class="doc-head">
                <div class="doc-title">{{ preview.title }}</div>
                <div class="doc-acts">
                  <span class="mode-switch">
                    <button
                      class="mode-btn" type="button"
                      :class="{ on: previewMode === 'kb' }"
                      @click="previewMode = 'kb'"
                    >知识库排版</button>
                    <button
                      class="mode-btn" type="button"
                      :class="{ on: previewMode === 'md' }"
                      @click="previewMode = 'md'"
                    >Markdown 源码</button>
                  </span>
                  <button class="btn btn-sm btn-outline" type="button" @click="copyMarkdown">复制</button>
                  <button class="btn btn-sm btn-outline" type="button" @click="downloadMd">下载 .md</button>
                  <button class="btn btn-sm btn-outline" type="button" @click="downloadDoc">下载 .doc</button>
                  <button
                    class="btn btn-sm btn-solid"
                    type="button"
                    :disabled="submitting || stale || missingSource > 0 || scopeMissing.length > 0"
                    :title="scopeMissing.length ? '缺少适用范围信息' : (missingSource > 0 ? '有片段未标来源' : '')"
                    @click="submit"
                  >{{ submitting ? '提交中…' : '提交入库' }}</button>
                </div>
              </div>

              <!-- 知识库排版：与知识库「开始阅读」同一套分块渲染（长正文已由服务端按句读分节） -->
              <div v-if="previewMode === 'kb'" class="reader-wrap">
                <h2 class="reader-title">《{{ preview.title }}》</h2>
                <div class="viewer-text">
                  <template v-for="(b, bi) in previewBlocks" :key="bi">
                    <h2 v-if="b.type === 'h' && b.level === 1" class="reader-h1">{{ b.text }}</h2>
                    <h3 v-else-if="b.type === 'h' && b.level === 2" class="reader-h2">{{ b.text }}</h3>
                    <h4 v-else-if="b.type === 'h' && b.level === 3" class="reader-h3">{{ b.text }}</h4>
                    <p v-else-if="b.type === 'p'" class="reader-p">{{ b.text }}</p>
                    <div v-else-if="b.type === 'table'" class="reader-table-wrap">
                      <table class="reader-table">
                        <thead v-if="b.rows && b.rows.length">
                          <tr>
                            <th v-for="(c, ci) in b.rows[0]" :key="ci">{{ c }}</th>
                          </tr>
                        </thead>
                        <tbody v-if="b.rows && b.rows.length > 1">
                          <tr v-for="(row, ri) in b.rows.slice(1)" :key="ri">
                            <td v-for="(c, ci) in row" :key="ci">{{ c }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                    <!-- 列表：预览要与入库后逐块一致，所以这边也得渲染，不能漏 -->
                    <ol v-else-if="b.type === 'list' && b.ordered" class="reader-list">
                      <li v-for="(it, ii) in b.items" :key="ii">{{ it }}</li>
                    </ol>
                    <ul v-else-if="b.type === 'list'" class="reader-list">
                      <li v-for="(it, ii) in b.items" :key="ii">{{ it }}</li>
                    </ul>
                  </template>
                </div>
              </div>
              <pre v-else class="doc-preview">{{ preview.markdown }}</pre>
              <p class="box-note">
                「知识库排版」就是这份文档入库后读者在知识库里看到的样子；「Markdown 源码」是实际存档的文件内容。
                提交后进入知识库的「待审核」；通过且成功建索引后，这条缺口才会被标为已补充。
                同一个缺口可以分几次补 —— 每次都会生成一份新的整合文档。
              </p>
            </template>
          </section>
        </template>
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
.work { max-width: 1080px; margin: 0 auto; display: flex; flex-direction: column; gap: 18px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
.page-title { font-family: var(--serif); font-weight: 700; font-size: 24px; letter-spacing: 0.5px; }
.page-sub { color: var(--ink-2); font-size: 14.5px; margin-top: 6px; }

.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-md); box-shadow: var(--shadow-sm); }
.box { padding: 18px 20px 20px; }
.box-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.box-title { font-family: var(--serif); font-size: 16px; font-weight: 700; display: flex; align-items: center; gap: 8px; }
.count {
  font-family: var(--sans); font-size: 11.5px; font-weight: 600;
  color: var(--clay-deep); background: var(--clay-soft);
  border: 1px solid var(--accent-line); border-radius: 999px; padding: 1px 8px;
}
.head-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.box-note { font-size: 12.5px; color: var(--ink-3); line-height: 1.8; margin-top: 10px; }
.box-note.warn { color: var(--amber); }
.hint { padding: 22px 4px; font-size: 14px; color: var(--ink-3); }

.slot-row { display: flex; gap: 10px; flex-wrap: wrap; }
.slot {
  display: inline-flex; align-items: baseline; gap: 6px;
  padding: 6px 12px; border-radius: var(--r-sm);
  background: var(--bg); border: 1px solid var(--line);
  font-size: 14px; color: var(--ink);
}
.slot em { font-style: normal; font-size: 11.5px; color: var(--ink-3); }

.kw-row { display: flex; gap: 8px; }
.input {
  flex: 1; min-width: 0;
  font-family: var(--sans); font-size: 14px; color: var(--ink);
  border: 1px solid var(--line); border-radius: var(--r-sm);
  padding: 8px 12px; outline: none; background: var(--bg);
}
.input:focus { border-color: var(--clay); }
.site-row { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }

/* ── 数据表 ─────────────────────────────────── */
/* 七列，窄屏靠横向滚动——把行内换行会让表格完全没法对齐着改 */
.table-wrap { overflow-x: auto; margin-top: 4px; }
.rows-table { border-collapse: separate; border-spacing: 0; width: 100%; }
.rows-table th {
  font-size: 11.5px; font-weight: 600; color: var(--ink-3); text-align: left;
  padding: 0 4px 6px; white-space: nowrap;
}
.rows-table td { padding: 0 4px 6px; }
.idx { width: 26px; font-size: 12px; color: var(--ink-3); font-variant-numeric: tabular-nums; }
.ops { width: 30px; }
.cell {
  width: 100%; box-sizing: border-box;
  font-family: var(--sans); font-size: 12.5px; color: var(--ink);
  border: 1px solid var(--line); border-radius: var(--r-xs);
  padding: 5px 7px; outline: none; background: var(--surface);
}
.cell:focus { border-color: var(--clay); background: var(--bg); }
/* 缺来源的行标红：整合资料的价值就在溯源，缺了这条数据不能用 */
.cell.missing { border-color: var(--danger); background: var(--danger-soft); }
.del {
  border: 0; background: transparent; cursor: pointer; color: var(--ink-3);
  font-size: 13px; padding: 4px 6px; border-radius: var(--r-xs); flex: none;
}
.del:hover { color: var(--danger); background: var(--danger-soft); }

/* ── 危险因素 / 专家建议（两份列表并排，窄屏叠成一列） ── */
.list-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 12px; }
.list-col { min-width: 0; }
.list-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 12.5px; font-weight: 600; color: var(--ink-2); margin-bottom: 6px;
}
.list-empty { font-size: 12.5px; color: var(--ink-3); font-style: italic; padding: 4px 0; }
.list-row { display: flex; align-items: center; gap: 6px; }
.list-row + .list-row { margin-top: 6px; }

.submit-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 14px; }
.box-note.inline { margin-top: 0; }

/* 抽取用的原文：默认折叠，要核对「抽得对不对」时才展开 */
.src-fold { margin-top: 12px; font-size: 12.5px; color: var(--ink-3); }
.src-fold summary { cursor: pointer; }
.src-text {
  margin: 8px 0 0; padding: 10px 12px; max-height: 220px; overflow: auto;
  background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-sm);
  font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-all;
}

.src-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.field { display: flex; flex-direction: column; gap: 5px; }
.field-label { font-size: 12px; color: var(--ink-3); }

/* ── 研究信息（适用范围）：四个必填字段，窄屏叠成两列/一列 ── */
.scope-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 12px; }
.req { font-style: normal; color: var(--danger); margin-left: 2px; }
/* 必填却没填：标红提示（与服务端 @NotBlank 一致，提交时也会拦） */
.input.missing { border-color: var(--danger); background: var(--danger-soft); }
@media (max-width: 760px) { .scope-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 480px) { .scope-grid { grid-template-columns: 1fr; } }
.paste-area {
  width: 100%; box-sizing: border-box; resize: vertical;
  font-family: var(--sans); font-size: 13.5px; line-height: 1.8; color: var(--ink);
  border: 1px solid var(--line); border-radius: var(--r-sm);
  padding: 10px 12px; outline: none; background: var(--bg);
}
.paste-area:focus { border-color: var(--clay); }

/* ── 整理与提交 ─────────────────────────────── */
.doc-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
.doc-title { font-family: var(--serif); font-size: 16px; font-weight: 700; color: var(--ink); }
.doc-acts { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }

/* 预览视角切换：知识库排版 / Markdown 源码 */
.mode-switch {
  display: inline-flex; border: 1px solid var(--line); border-radius: 999px;
  background: var(--bg); overflow: hidden;
}
.mode-btn {
  border: 0; background: transparent; cursor: pointer;
  font-family: var(--sans); font-size: 12px; color: var(--ink-3);
  padding: 5px 12px;
}
.mode-btn.on { background: var(--clay-soft); color: var(--clay-deep); font-weight: 600; }
.mode-btn + .mode-btn { border-left: 1px solid var(--line); }

/* ── 知识库排版预览（文献版式，与 KnowledgeView 阅读页同款） ── */
.reader-wrap { max-height: 480px; overflow-y: auto; background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 36px 44px; }
.reader-title { font-family: var(--serif); font-weight: 700; font-size: 22px; line-height: 1.5; margin: 0 0 24px; text-align: center; color: var(--ink); }
.viewer-text { font-size: 16px; line-height: 2; color: var(--ink); font-family: var(--serif); }
.reader-h1 { font-size: 1.28em; font-weight: 700; color: var(--ink); margin: 26px 0 10px; padding-bottom: 6px; border-bottom: 1px solid var(--line); }
.reader-h2 { font-size: 1.14em; font-weight: 700; color: var(--ink); margin: 20px 0 8px; }
.reader-h3 { font-size: 1.02em; font-weight: 600; color: var(--clay-deep); margin: 14px 0 6px; }
.reader-p { margin: 0 0 14px; }
.reader-table-wrap { margin: 18px 0; overflow-x: auto; }
.reader-table { width: 100%; border-collapse: collapse; font-size: 14px; font-family: var(--sans); }
.reader-table th, .reader-table td { border: 1px solid var(--line); padding: 8px 12px; text-align: left; vertical-align: top; }
.reader-table th { background: var(--clay-soft); color: var(--clay-deep); font-weight: 600; }
.reader-table tbody tr:nth-child(even) td { background: var(--bg); }
/* 列表：与知识库阅读器同一套排版（预览要逐块一致） */
.reader-list { margin: 0 0 16px; padding-left: 22px; }
.reader-list li { margin-bottom: 6px; line-height: 1.85; }
.reader-list li::marker { color: var(--clay-deep); }

.doc-preview {
  margin: 0; padding: 14px 16px; max-height: 420px; overflow: auto;
  background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-sm);
  font-size: 12.5px; line-height: 1.8; white-space: pre-wrap; word-break: break-word;
  color: var(--ink);
}

.footer { border-top: 1px solid var(--line); background: var(--surface); }
.footer .wrap { height: 76px; display: flex; align-items: center; justify-content: space-between; }
.foot-brand { font-family: var(--serif); font-weight: 700; font-size: 15px; letter-spacing: 0.5px; color: var(--ink-2); }
.foot-note { font-size: 12.5px; color: var(--ink-3); }

@media (max-width: 560px) {
  .main { padding: 22px 16px 48px; }
  .box { padding: 14px 14px 16px; }
  .src-grid { grid-template-columns: 1fr; }
}
</style>
