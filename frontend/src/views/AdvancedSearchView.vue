<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAnalysisStore } from '@/stores/analysis'
import { useAdvancedSearchStore } from '@/stores/advancedSearch'
import AppTopbar from '@/components/AppTopbar.vue'
/** 等待检索时的三步进度。与智能对话页共用同一个组件，保证两个入口样式一致 */
import AnswerProgress from '@/components/AnswerProgress.vue'
import DocEvidenceViewer from '@/components/DocEvidenceViewer.vue'
import SourceBadge from '@/components/SourceBadge.vue'
import { getKbVocab } from '@/api/kb'
import { ETHNICS, DISEASES } from '@/utils/medicalVocab'
import { INTENT_LABELS, evidencePool, generate, fetchDocFullText } from '@/api/qa'
import type { EvidencePoolResult, GenerateAnswer, GenerateResult, IntentCode, PoolEvidence, RetrieveResult } from '@/api/qa'
import {
  deleteSearchHistory,
  listSearchHistory,
  parseSnapshot,
  recordSearch,
  searchHistoryTitle,
} from '@/api/searchHistory'
import type { SearchHistoryItem, SearchSnapshot } from '@/api/searchHistory'
import { feedbackGap, gapSlotKey, myFeedbackSlots, recordGap } from '@/api/gap'
import { timeAgo } from '@/api/dynamic'
import { groupByTime } from '@/utils/timeGroup'
import { structuredAnswerHtml } from '@/utils/answerHtml'
import { esc } from '@/utils/markdown'
import { highlightTerms } from '@/utils/highlight'
import { packQaCard } from '@/utils/shareConversation'
import type { ShareCitation, ShareTurn } from '@/utils/shareConversation'
import { publishQaCard } from '@/api/dynamic'

const router = useRouter()
const route = useRoute()

/** 个人中心「高级检索」子 Tab 点进来时带的记录 id（`/search?h=12`），非法/缺省为 null */
const wantedHistoryId = computed(() => {
  const n = Number(route.query.h)
  return Number.isFinite(n) && n > 0 ? n : null
})

// ============================================================
// 页面状态：整体放进 store
// ============================================================
// 这个组件**不在 keep-alive 名单里**（只有对话页在），点顶栏切到知识库 / 动态再切回来，
// 组件会卸载重建，组件内的 ref 一律清零——那正是「检索结果切走就没了」的成因。
// 提到 store 后重挂载直接接着用。
// 纯 transient 的 UI 状态不在此列（证据折叠、文献弹窗、分享按钮的忙碌态），
// 那些丢了无所谓，留在组件内即可。
const adv = useAdvancedSearchStore()
const analysisStore = useAnalysisStore()
const {
  ethnicity, disease, intent, vocab, vocabError,
  shown, result, miss, searching, activeHistoryId,
  rawPool, rawGenerate, gapId, gapFeedbackSent,
} = storeToRefs(adv)

/** 词表已缓存时直接算加载完成，省掉重挂载时下拉先空一帧再填上 */
const vocabLoading = ref(!vocab.value)

// ============================================================
// 下拉词表：只列知识库里**真有资料**的组合
// ============================================================
// 候选由前端给（medicalVocab 那份覆盖面最广），服务端回答「哪些真有」。
// 这样下拉里选不出空组合——否则用户选哪个都是「暂无数据」，页面等于不可用。

/**
 * 下拉项：**按使用频率排，兜底项放最后**。
 *
 * 刻意写成显式列表而不是 `Object.keys(INTENT_LABELS)`：标签表里还有两个码
 * （`overview` 研究概况、`treatment` 治疗与干预方向）不该出现在这个下拉里，
 * 从标签表派生会把它们一起带出来。
 */
const INTENT_OPTIONS: IntentCode[] = [
  'prevalence', 'risk', 'prevention', 'diet', 'genetics',
  'medication', 'symptoms', 'burden', 'all',
]

/**
 * 选项后面那半句大白话——把「患病率」翻译成用户自己会问的话。
 *
 * 只在这里拼给下拉看，**不进 `INTENT_LABELS`**：那个标签还要用在结果标题、
 * 历史记录、需求分析榜单上，写长了大面积难看。原生 select 的 option 只能是纯文本，
 * 所以直接拼在标签后面（这也正是用户建议的写法）。
 */
const INTENT_HINTS: Partial<Record<IntentCode, string>> = {
  prevalence: '得病的人多不多',
  risk: '哪些人更容易得',
  prevention: '怎么防、要不要筛查',
  diet: '怎么吃、怎么动',
  genetics: '跟基因有没有关系',
  medication: '吃药要注意什么',
  symptoms: '早期有什么表现',
  burden: '这个病有多重',
  all: '以上都看看（兜底）',
}

/** 下拉项的完整文案：「患病率/发病率（得病的人多不多）」 */
function intentOptionText(c: IntentCode): string {
  const hint = INTENT_HINTS[c]
  return hint ? `${INTENT_LABELS[c]}（${hint}）` : INTENT_LABELS[c]
}

const ethnicityOptions = computed(() => vocab.value?.ethnicities || [])

/** 疾病下拉：只列所选民族**有数据**的疾病（换民族要重置，否则会留下不属于该民族的疾病） */
const diseaseOptions = computed(() =>
  (vocab.value?.pairs || []).filter((p) => p.ethnicity === ethnicity.value).map((p) => p.disease),
)

/** 当前「民族+疾病」实际覆盖的意图，用于默认选中与未命中时的建议 */
const pairIntents = computed<IntentCode[]>(() => {
  const p = (vocab.value?.pairs || []).find(
    (x) => x.ethnicity === ethnicity.value && x.disease === disease.value,
  )
  // **只留下拉里真有的那些**：服务端的意图表比这个下拉大（还多 treatment、overview），
  // 让它们当默认值或「换个方面」建议，会选中一个下拉里根本不存在的项——框里显示原样
  // 的英文码，用户也不知道那是什么。
  return ((p?.intents || []) as IntentCode[]).filter((c) => INTENT_OPTIONS.includes(c))
})

/**
 * 是否正在**程序化**地设置三个槽位（历史还原、未命中换方面重试）。
 *
 * 下面的 watcher 是「用户改了条件」的响应：改条件就丢掉上一个组合的结果，
 * 免得结果区还挂着一份与当前下拉对不上的答案。但程序化设置槽位时不能让它们插手——
 * 两个 watcher 都在 flush 后才跑：还原历史时会把刚还原出来的结果清掉，
 * 换方面重试时会把刚要开始的那次检索的 `shown` 清掉（合成问句随之变空）。
 */
let settingSlotsProgrammatically = false
function setSlotsProgrammatically(fn: () => void) {
  settingSlotsProgrammatically = true
  fn()
  // 两个 watcher 与这里同属一次 flush，nextTick 的回调排在它们之后
  void nextTick(() => {
    settingSlotsProgrammatically = false
  })
}

watch(ethnicity, () => {
  if (settingSlotsProgrammatically) return
  if (!diseaseOptions.value.includes(disease.value)) disease.value = diseaseOptions.value[0] || ''
})
// 兜底只挂在「民族 / 疾病」上：单独改方面是用户的明确选择，不能被改回去——
// 选一个该组合没资料的方面是**正常用法**（结果区会说明实际收录了哪些方面）。
watch([ethnicity, disease], () => {
  if (settingSlotsProgrammatically) return
  if (!pairIntents.value.includes(intent.value as IntentCode)) {
    intent.value = pairIntents.value[0] || ('' as IntentCode | '')
  }
  clearOutcome()
})
watch(intent, () => {
  if (settingSlotsProgrammatically) return
  clearOutcome()
})

/**
 * 丢掉上一次检索的结果。
 *
 * 条件变了，上一份结果就不属于当前条件了：留着会出现「下拉写着 A、答案却是 B」，
 * 用户点「检索」之前分不清那份结果到底对应哪个组合。
 */
function clearOutcome() {
  shown.value = null
  result.value = null
  miss.value = null
  rawPool.value = null
  rawGenerate.value = null
  activeHistoryId.value = null
  gapId.value = null
  gapFeedbackSent.value = false
}

const canSearch = computed(
  () => !!ethnicity.value && !!disease.value && !!intent.value && !searching.value,
)

onMounted(async () => {
  // 词表与历史互不依赖，并行拉；一边失败不该拖住另一边
  // 定位必须等历史到位，所以挂在 loadHistory 之后而不是和它并行
  void loadHistory().then(focusHistoryFromQuery)
  // 我反馈过的槽位：未命中回显「已反馈」要它（与词表/历史互不依赖，可并行）
  void loadMyFeedback()
  // 词表按会话缓存：带着上次的选择切回来时不必再拉，也就不会先空一帧再填上
  if (vocab.value) return

  try {
    vocab.value = await getKbVocab(ETHNICS, DISEASES)
    // 只在**首次进入**时落默认值。不能无条件覆盖：那样带着上次的选择切回来时，
    // 三个下拉会被重置成第一项，而结果区还停在上一次检索上，两边对不上。
    if (!ethnicity.value) ethnicity.value = vocab.value.ethnicities[0] || ''
  } catch {
    // 词表接口不通（如 AI 服务未重启到新版本）时页面不能白屏：给出可执行的提示
    vocabError.value = '知识库词表加载失败，请确认 AI 服务已启动。'
  } finally {
    vocabLoading.value = false
  }
})

/**
 * 组件被复用时（路由 name 相同、只有 query 变）onMounted 不会再跑——在个人中心里
 * 连点两条检索记录正好是这种情况，不自己响应就会停在上一条的结果上。
 */
watch(wantedHistoryId, (id) => {
  if (id == null) return
  if (history.value.length) focusHistoryFromQuery()
  else void loadHistory().then(focusHistoryFromQuery)
})

// ============================================================
// 检索：直接调 evidence-pool + generate
// ============================================================
// 刻意**跳过** /api/understand：下拉已经给出三个槽位，不必再让大模型从自由文本里猜。
// 服务端 _slots_from_understanding 对传入的 understand 原样透传（不校验词表），
// 而 /api/generate 的「槽位不全就重新理解」兜底也不会触发——因为两个槽位都给了。
// `searching` / `result` / `miss` 都在 store 里（见上），这里不再重复声明。

/**
 * 分步进度：0=理解 1=检索 2=组织。**由真实 await 边界驱动，不是定时器**。
 *
 * 与对话页的一处差别：这里**没有「理解」这个真实动作**——用户是用下拉框选好三个槽位的，
 * `buildUnderstanding` 只是同步本地拼装，耗时约 0（也正因如此才刻意跳过了 /api/understand）。
 * 所以一开始就把进度设成 1：第 1 步呈现为「已完成」（灰色打勾），第 2 步高亮。
 * 这不是假进度——理解那步确实瞬间就完成了。
 *
 * 纯 UI 状态，不进 store：切走再回来时重新开始提示，比停在上一次的旧状态更合理。
 */
const aiStep = ref(0)

/** 检索失败（网络 / AI 服务挂了）：进度卡片换成失败提示并停住，直到下次检索 */
const failed = ref(false)

/**
 * 失败提示要一直留到下次检索（不能像进度那样一失败就消失，否则用户只看到一闪而过的红条），
 * 但一旦有了结果或未命中——比如用户随后点了历史记录——就该让位，不能盖住内容。
 */
const showFailure = computed(() => failed.value && !result.value && !miss.value)

/**
 * 结果区当前展示的是**哪一次**检索。
 *
 * 不从 `ethnicity/disease/intent` 三个 ref 直接读：那三个是「下一次要检索什么」的输入框，
 * 而这里是「已经检索完的那一次是什么」。两者平时相同，但点开历史记录后就会分叉
 * （还原的是记录里的组合，输入框随后可能被 watch 改回该组合的默认方面）。
 * 分不开的话，卡片标题会显示成另一个方面，而正文还是旧的那份。
 *
 * 它同样活在 store 里：跟着「那一次检索」一起留存，切回来时标题与正文才配套。
 */

/**
 * 合成问句：与后端标准化问句**逐字同形**，这样 BM25 打分与对话页一致，
 * 同一个组合在两处检索到的证据排序才一样。
 *
 * 后缀取自服务端下发的 `intentPhrases`（`_INTENT_PHRASE` 的原文），**不是** INTENT_LABELS——
 * 两者只是碰巧有四项相同：界面上 diet 写「饮食与生活方式」，标准问句里是「饮食情况」。
 */
const composedQuestion = computed(() =>
  shown.value
    ? `${shown.value.ethnicity}${shown.value.disease}的${
        vocab.value?.intentPhrases?.[shown.value.intent] || INTENT_LABELS[shown.value.intent] || ''
      }`
    : '',
)

function buildUnderstanding(searched: { ethnicity: string; disease: string; intent: IntentCode }) {
  return {
    ethnicity: searched.ethnicity,
    disease: searched.disease,
    intent: searched.intent as string,
    // 民族疾病都有，不走泛化检索
    generalized: false,
    // 下拉检索不是用药咨询，不触发医疗安全边界
    medical_advice: false,
    confidence: 0.9,
    question_type: INTENT_LABELS[searched.intent],
  }
}

/** 未命中提示：说清实际收录的是哪个方面，并给出可换的选项 */
function setMiss(reason: string, forIntent: IntentCode) {
  miss.value = {
    reason: reason || '未检索到相关证据。',
    alternatives: pairIntents.value.filter((i) => i !== forIntent),
  }
}

/**
 * 结果快照：只留结果区真正会渲染的三项。
 *
 * **不存 evidence-pool 的整个返回**——里面的 `candidates[].fullText` 是整份文档正文
 * （一份专家共识全文就 35k 字），原样存进历史表每条记录都会变成几十 KB，
 * 而还原时根本不读 candidates。服务端不解析这份 JSON，形状由前端说了算。
 */
function buildSnapshot(pool: EvidencePoolResult, answer: GenerateAnswer | null): SearchSnapshot {
  return {
    answer,
    evidence: pool.evidence || [],
    reason: pool.reason || '',
    status: pool.status,
  }
}

async function runSearch() {
  if (!canSearch.value) return
  // 三个槽位在检索开始时定下：中途用户可能去改下拉，但这一次检索的归属不该跟着变
  const searched = { ethnicity: ethnicity.value, disease: disease.value, intent: intent.value as IntentCode }
  shown.value = searched
  searching.value = true
  // 槽位已经由下拉框定好了，「理解」这一步瞬间完成，所以进度直接从「检索」开始
  aiStep.value = 1
  failed.value = false
  result.value = null
  miss.value = null
  activeHistoryId.value = null

  let snapshot: SearchSnapshot | null = null
  // 上一次检索留下的链路先清掉：这一次要是失败（网络/AI 服务挂了），
  // 后台不该还捧着一份旧的当作这次的分析
  rawPool.value = null
  rawGenerate.value = null
  try {
    // 合成问句走 composedQuestion：它读的是 shown，而 shown 上面刚被设成本次的三个槽位，
    // 所以这里拿到的就是这一次的问句——只此一处拼问句，免得分享时用另一份拼法拼出不同的串
    const q = composedQuestion.value
    const understanding = buildUnderstanding(searched)
    // knowledge 参数在服务端是死字段（已核对：声明了但从未读取），传空数组即可
    const pool = await evidencePool(q, understanding, [], [])
    // 原始证据池留存：结果区只用得到 answer + 打包证据，而后台的知识检索模块
    // 渲染的是 candidates（候选资料与命中片段），那份只在 pool 里
    rawPool.value = pool
    if (!pool.evidence_found || !(pool.evidence || []).length) {
      setMiss(pool.reason, searched.intent)
      snapshot = buildSnapshot(pool, null)
      // 登记知识缺口——「需求分析」榜单的数据源。与存档一样是 fire-and-forget：
      // 登记失败最多是榜单少一条，不该干扰用户看结果。
      void recordGapFor(searched)
    } else {
      // retrieve_result 在服务端同样未被读取（generate_endpoint 自己重新检索了一遍），
      // 这里仍按形状传，保持与对话页调用一致
      const retrieveForGen: RetrieveResult = {
        evidence_found: pool.evidence_found,
        evidence: pool.evidence,
        reason: pool.reason,
      }
      // 生成才是等待的大头（数秒），进度推进到「组织回答」让用户知道在等什么
      aiStep.value = 2
      const gen = await generate(q, understanding, retrieveForGen, [])
      rawGenerate.value = gen
      result.value = { answer: gen.answer, evidence: pool.evidence || [] }
      snapshot = buildSnapshot(pool, gen.answer)
    }
  } catch (e) {
    // 检索本身失败（网络 / AI 服务挂了）：没有可存档的快照，也就不写历史——
    // 记一条「什么都没查到」会与真正的「未命中」混在一起，看不出区别
    // failed 让进度卡片留在原地显示「生成失败，请重试」，而不是直接消失回落成初始空态
    failed.value = true
    ElMessage.error(e instanceof Error ? e.message : '检索失败，请稍后重试')
  } finally {
    searching.value = false
  }

  // 登记到后台分析工作台。未命中同样要登记——「为什么没命中」正是后台该说清的事。
  // 刻意用 raw 而不是结果区那份精简数据：后台要的是完整链路。
  if (rawPool.value) publishToWorkbench(searched, rawPool.value, rawGenerate.value)

  // 存档放在 finally 之后：它是「看结果」的副产物，不该把「检索中…」再拖长一个来回；
  // 失败也不该把已经拿到的答案一起判死（recordHistory 自己兜住异常）
  if (snapshot) void recordHistory(searched, snapshot)
}

/** 未命中时换一个该组合真有的方面重试 */
function useIntent(code: IntentCode) {
  // 走程序化通道：否则「改条件清结果」的 watcher 会在 flush 后把这次检索的 shown 清掉
  setSlotsProgrammatically(() => {
    intent.value = code
  })
  runSearch()
}

// ============================================================
// 知识缺口：未命中 → 登记 → 用户反馈
// ============================================================
/**
 * 我反馈过的缺口槽位（键是 `民族|疾病|方面`）。
 *
 * 与对话页同一套「按槽位回显」语义：服务端按 (缺口, 用户) 去重，同一组合再点一次是空操作，
 * 所以同一个组合无论查多少次都该显示「已反馈」——否则会看到「已反馈」过一会儿又变回
 * 「反馈此问题」，像没记上一样。
 */
const feedbackSentKeys = ref<Set<string>>(new Set())

async function loadMyFeedback() {
  try {
    const list = await myFeedbackSlots()
    feedbackSentKeys.value = new Set(list.map(gapSlotKey))
  } catch {
    // 拉不到就当没反馈过：按钮显示回「反馈此问题」，点下去服务端仍会去重，不会重复计数
    feedbackSentKeys.value = new Set()
  }
}

/**
 * 把这次未命中登记成一条知识缺口。
 *
 * **只在实时检索这条路上调用**，`openHistory`（从历史记录还原）不调用——
 * 否则反复回看旧记录会把「被问次数」刷上去，榜单就失真了。
 *
 * 失败静默：登记不上最多是榜单少一条，不该弹错误跟「未命中」的说明抢注意力。
 */
async function recordGapFor(searched: { ethnicity: string; disease: string; intent: IntentCode }) {
  gapId.value = null
  gapFeedbackSent.value = false
  try {
    const gap = await recordGap({
      ...searched,
      // 组合串当原话（「白族+高血压+患病率」），让管理员在榜单上一眼看懂在查什么组合
      query: `${searched.ethnicity}+${searched.disease}+${INTENT_LABELS[searched.intent]}`,
    })
    gapId.value = gap.id
    gapFeedbackSent.value = feedbackSentKeys.value.has(gapSlotKey(searched))
  } catch {
    /* 榜单少一条而已，不打断用户 */
  }
}

/** 「反馈此问题」：服务端按 (缺口, 用户) 去重，重复点不会再把人数加上去 */
async function sendFeedback() {
  if (gapId.value == null || gapFeedbackSent.value) return
  try {
    await feedbackGap(gapId.value)
    gapFeedbackSent.value = true
    // 本地记下这个槽位，同一组合再查时也能回显「已反馈」，不必重拉列表
    if (shown.value) {
      feedbackSentKeys.value = new Set([...feedbackSentKeys.value, gapSlotKey(shown.value)])
    }
    ElMessage.success('已记录您的需求，感谢反馈！')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '反馈失败，请稍后重试')
  }
}

// ============================================================
// 与后台分析工作台的衔接
// ============================================================
/**
 * 把这次检索登记到后台分析工作台（共享状态）。
 *
 * 为什么必须登记：高级检索是**绕过** analysisStore 直接调接口的，后台读不到它，
 * 于是用户在高级检索里点了半天，后台还停在上一次智能对话的分析上——就是修的那个 Bug。
 *
 * 为什么不调 `analysisStore.load()`：那个会把整条 RAG 再跑一遍（第二次打大模型），
 * 既慢、答案又会因随机性和用户眼前那份不一样。`adopt()` 只登记结果、不重跑。
 *
 * 问题文本取 `composedQuestion`，与真正发给检索接口的那一句**逐字一致**，
 * 后台的「问题理解」和「加工生成」才与这次检索对得上。
 */
function publishToWorkbench(
  searched: { ethnicity: string; disease: string; intent: IntentCode },
  evidencePoolResult: EvidencePoolResult,
  genResult: GenerateResult | null,
) {
  const text = composedQuestion.value
  analysisStore.adopt(
    {
      text,
      ethnic: searched.ethnicity,
      disease: searched.disease,
      intent: searched.intent,
      intentText: INTENT_LABELS[searched.intent],
      questionType: INTENT_LABELS[searched.intent],
      standardized: text,
      status: 'clear',
      confidence: 0.9,
      // 后台据此标「来源：高级检索」，也让它与同槽位的对话缓存区分开（见 analysis.ts 的 sig）
      source: 'search',
      ts: Date.now(),
    },
    [],
    evidencePoolResult,
    genResult,
  )
}

/**
 * 「查看后台分析」：把这次检索**重新登记一遍**再跳过去。
 *
 * 为什么不是直接跳：共享状态里高级检索与智能对话各存一条记录，但**当前看哪一条**
 * 是在这次登记里定下来的。用户点之前哪怕刚回过对话页问了一句，不重登记的话
 * 跳过去看到的又是别人的分析——正是这次要修的毛病。手上本来就留着这次的原始返回，
 * 重登记是零成本的。
 *
 * 提示语与对话页点卡片时那句**逐字一致**（QaView 的 viewAdminTrace）：
 * 用户对「后台已切换」这件事的反馈应该只有一种说法。对话页那边按钮是跳转且不提示、
 * 提示挂在点卡片其他区域的路径上；这里只有按钮这一条路径，所以两者合并成「提示 + 跳转」。
 */
function openWorkbench() {
  if (!shown.value || !rawPool.value) return
  publishToWorkbench(shown.value, rawPool.value, rawGenerate.value)
  ElMessage.success('后台分析工作台已切换至该问题的分析记录，请前往后台查看')
  router.push({ name: 'backend' })
}

// ---------- 结果展示 ----------
// 带上所问民族：回答里的对比表往往混着多个民族的数据，把用户问的那个标出来，
// 其余民族留着作对比。不传这一项就是原来的渲染，一字未变。
const answerHtml = computed(() =>
  result.value?.answer ? structuredAnswerHtml(result.value.answer, shown.value?.ethnicity) : '',
)

const evidenceList = computed<PoolEvidence[]>(() => result.value?.evidence || [])

/** 证据片段里的关键词高亮（与对话页同一处理） */
function evidenceHtml(e: PoolEvidence): string {
  return highlightTerms(esc(e.fragment), e.matchedTerms)
}

const expandedEvidence = ref(false)

/**
 * 能不能点开原文：既要有文档 id，也要在全文里定位到了位置。
 * 服务端定位失败时给 -1、没有文档时给空串，两种都不可点——不给用户「点了没反应」的假象。
 */
function canLocate(e: PoolEvidence): boolean {
  return !!e.id && e.startOffset != null && e.startOffset >= 0 && e.endOffset != null
}

const locatableCount = computed(() => evidenceList.value.filter(canLocate).length)

// ---------- 证据原文（复用对话页的做法：拉全文 → 定位高亮） ----------
const docVisible = ref(false)
const docFullText = ref('')
const docTitle = ref('')
const docItems = ref<{ fragment: string; startOffset?: number; endOffset?: number; page?: number | null }[]>([])
const docFocus = ref(0)
const docLoadedFor = ref('')

async function openDoc(index: number) {
  const items = evidenceList.value
  const target = items[index]
  if (!target || !canLocate(target)) return
  // 同一份文档的多条证据一起带过去，弹窗里可以逐条跳转定位
  const sameDoc = items.filter((x) => x.id === target.id)
  docItems.value = sameDoc.map((x) => ({
    fragment: x.fragment,
    startOffset: x.startOffset,
    endOffset: x.endOffset,
    page: x.page == null ? null : Number(x.page),
  }))
  const focus = sameDoc.indexOf(target)
  docFocus.value = focus >= 0 ? focus : 0
  docTitle.value = `《${target.title}》`
  docVisible.value = true

  const docId = target.id!
  if (docLoadedFor.value === docId && docFullText.value) return
  docFullText.value = ''
  try {
    const d = await fetchDocFullText(docId)
    docFullText.value = d.full_text || ''
    docTitle.value = `《${d.title || target.title}》`
    docLoadedFor.value = docId
  } catch {
    docFullText.value = ''
  }
}

// ============================================================
// 检索历史（左侧栏）
// ============================================================
// 服务端按「用户 + 民族 + 疾病 + 方面」幂等 upsert，所以这里不需要「新建」按钮：
// 同一组合再检索一次就是把那条记录的时间与快照刷新，列表不会变长。
const history = ref<SearchHistoryItem[]>([])
const historyLoading = ref(true)
// `activeHistoryId`（当前结果对应的高亮记录）在 store 里：它必须跟着结果一起留存，
// 否则切回来结果是旧的、高亮却没了。

/** 展示标题（「白族 + 糖尿病 + 患病情况」）在这里算好，模板里不必反复调函数 */
const historyGroups = computed(() =>
  groupByTime(
    history.value.map((h) => ({ ...h, title: searchHistoryTitle(h) })),
    (h) => h.updatedAt,
  ),
)

async function loadHistory() {
  historyLoading.value = true
  try {
    history.value = await listSearchHistory(50)
  } catch {
    // 历史栏拉不到不该让整个检索页不可用（词表与检索链路是独立的），静默降级成空栏
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

/**
 * 从个人中心点进来的定位：`/search?h=<id>` 要还原成「那条记录的结果」。
 *
 * 刻意不新增「按 id 取一条」的接口——列表本来就要拉，在这份数据里找是零成本的，
 * 而多一个端点就多一处鉴权与一份要维护的 SQL。代价是只找得到第一页（50 条）内的记录；
 * 找不到时如实提示，用户仍可手动再检索一次。
 */
function focusHistoryFromQuery() {
  const id = wantedHistoryId.value
  if (id == null) return
  const hit = history.value.find((x) => x.id === id)
  if (hit) openHistory(hit)
  else ElMessage.info('这条检索记录已不在最近的历史里，可重新检索一次')
}

async function recordHistory(
  searched: { ethnicity: string; disease: string; intent: IntentCode },
  snapshot: SearchSnapshot,
) {
  try {
    const item = await recordSearch({ ...searched, detail: snapshot })
    // 同一组合是刷新而非新增：先摘掉旧的那条再把新的插到最前，否则列表里会出现两条
    history.value = [item, ...history.value.filter((x) => x.id !== item.id)]
    activeHistoryId.value = item.id
  } catch {
    // 只是存档失败，检索结果已经拿到了，不该报成「检索失败」吓用户
    ElMessage.warning('结果已展示，但这次检索未能记入历史')
  }
}

/**
 * 点开一条历史：还原当时的槽位与结果。
 *
 * 还原的是**快照**而不是重跑一次：重跑要打一次大模型（数秒），而且答案会因随机性
 * 与当时不同——那就不是「当时的结果」了。想刷新，用户在还原后再点一次「检索」即可。
 */
function openHistory(h: SearchHistoryItem) {
  const restoredIntent = h.intent as IntentCode
  activeHistoryId.value = h.id
  // 先把三个槽位设回去：这样「检索」按钮处在「接着这条记录再跑一次」的位置上。
  // 走程序化通道：否则「改条件清结果」的 watcher 会在 flush 后把刚还原出来的结果清掉，
  // 而且它还会把这条记录的方面改写成该组合的默认方面（记录里可能是没资料的那个方面）。
  setSlotsProgrammatically(() => {
    ethnicity.value = h.ethnicity
    disease.value = h.disease
    intent.value = restoredIntent
  })
  shown.value = { ethnicity: h.ethnicity, disease: h.disease, intent: restoredIntent }
  // 还原的是**快照**（只有答案 + 打包证据），没有链路的原始返回。清掉，否则
  // 「查看后台分析」会把上一次检索的链路当成这一条交出去，后台显示的就是另一回事。
  rawPool.value = null
  rawGenerate.value = null

  const snap = parseSnapshot(h)
  if (!snap) {
    result.value = null
    miss.value = null
    ElMessage.info('这条记录没有可还原的结果，可直接点「检索」重新跑一次')
    return
  }
  if (snap.answer || snap.evidence.length) {
    result.value = { answer: snap.answer, evidence: snap.evidence }
    miss.value = null
  } else {
    result.value = null
    setMiss(snap.reason, restoredIntent)
  }
}

async function removeHistory(h: SearchHistoryItem) {
  try {
    await ElMessageBox.confirm(`确定删除检索记录「${searchHistoryTitle(h)}」吗？`, '删除记录', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteSearchHistory(h.id)
    history.value = history.value.filter((x) => x.id !== h.id)
    // 删掉的正是结果区的那条时，只清高亮不清结果：用户还在看的内容没必要跟着消失
    if (activeHistoryId.value === h.id) activeHistoryId.value = null
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ============================================================
// 分享到一问一答社区
// ============================================================
const sharing = ref(false)
const sharedId = ref<number | null>(null)

/** 幂等键：「民族|疾病|意图」。全局唯一，重复分享是刷新而非新增。 */
const qaKey = computed(() =>
  shown.value ? `${shown.value.ethnicity}|${shown.value.disease}|${shown.value.intent}` : '',
)

async function share() {
  if (!result.value?.answer || sharing.value || !shown.value) return
  sharing.value = true
  try {
    const a = result.value.answer
    const citations: ShareCitation[] = evidenceList.value.map((e) => ({
      title: e.title,
      quote: e.fragment,
      id: e.id,
      startOffset: e.startOffset,
      endOffset: e.endOffset,
      matchedTerms: e.matchedTerms,
      sourceLevel: e.sourceLevel,
      sourceOrg: e.sourceOrg ?? null,
    }))
    const turn: ShareTurn = {
      question: composedQuestion.value,
      answer: a as unknown as Record<string, unknown>,
      plain: a.conclusion || '',
      citations,
    }
    const packed = packQaCard(turn)
    const item = await publishQaCard({ qaKey: qaKey.value, ...packed })
    sharedId.value = item.id
    ElMessage.success('已分享到「一问一答」社区')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '分享失败，请稍后重试')
  } finally {
    sharing.value = false
  }
}

function goCommunity() {
  router.push({ name: 'dynamics', query: { tab: 'qa' } })
}
</script>

<template>
  <div class="adv">
    <AppTopbar />

    <div class="adv-body">
      <!-- 左侧检索历史：形制对齐对话页的会话侧栏（同一套分组标题 + 条目 + 悬停操作） -->
      <aside class="adv-side">
        <div class="side-head">检索历史</div>
        <div v-if="historyLoading" class="side-hint">加载中…</div>
        <div v-else-if="!history.length" class="side-empty">
          还没有检索记录。<br />选好条件点「检索」，就会记在这里。
        </div>
        <div v-else class="side-list">
          <template v-for="g in historyGroups" :key="g.label">
            <div class="side-group">{{ g.label }}</div>
            <div
              v-for="h in g.items"
              :key="h.id"
              class="side-item"
              :class="{ on: activeHistoryId === h.id }"
              @click="openHistory(h)"
            >
              <div class="side-item-main">
                <div class="side-item-title" :title="h.title">{{ h.title }}</div>
                <div class="side-item-meta">{{ timeAgo(h.updatedAt) }}</div>
              </div>
              <div class="side-item-actions" @click.stop>
                <button
                  class="btn btn-sm btn-icon btn-ghost btn-danger"
                  type="button"
                  title="删除"
                  @click.stop="removeHistory(h)"
                >🗑</button>
              </div>
            </div>
          </template>
        </div>
      </aside>

      <main class="adv-main">
        <header class="adv-head">
          <h1 class="adv-title">高级检索</h1>
          <p class="adv-sub">
            按「民族 × 疾病 × 想了解的方面」直接取用知识库里的文献依据，省去组织问句。
          </p>
        </header>

        <!-- 检索条件 -->
        <section class="adv-form">
          <div v-if="vocabLoading" class="adv-hint">正在读取知识库收录范围…</div>
          <div v-else-if="vocabError" class="empty-state empty-state-sm">
            <p class="empty-state-title">{{ vocabError }}</p>
            <p class="empty-state-desc">重启 AI 服务后刷新本页即可。</p>
          </div>
          <div v-else-if="!ethnicityOptions.length" class="empty-state empty-state-sm">
            <p class="empty-state-title">知识库暂未收录可检索的资料</p>
            <p class="empty-state-desc">待管理员上传资料后，这里会列出可查的民族与疾病。</p>
          </div>

          <div v-else class="adv-fields">
            <label class="adv-field">
              <span class="adv-label">民族</span>
              <select v-model="ethnicity" class="adv-select">
                <option v-for="e in ethnicityOptions" :key="e" :value="e">{{ e }}</option>
              </select>
            </label>

            <label class="adv-field">
              <span class="adv-label">疾病</span>
              <select v-model="disease" class="adv-select">
                <option v-for="d in diseaseOptions" :key="d" :value="d">{{ d }}</option>
              </select>
            </label>

            <label class="adv-field">
              <span class="adv-label">想了解的方面</span>
              <select v-model="intent" class="adv-select">
                <option v-for="c in INTENT_OPTIONS" :key="c" :value="c">{{ intentOptionText(c) }}</option>
              </select>
            </label>

            <button class="btn btn-solid adv-go" type="button" :disabled="!canSearch" @click="runSearch">
              {{ searching ? '检索中…' : '检索' }}
            </button>
          </div>
        </section>

        <!-- 检索中 / 检索失败 -->
        <section v-if="searching || showFailure" class="adv-card">
          <AnswerProgress :step="aiStep" :failed="failed" />
        </section>

        <!-- 未命中：说清实际收录的是哪个方面，并给出可换的方面 -->
        <section v-else-if="miss" class="adv-card">
          <div class="empty-state">
            <p class="empty-state-title">暂无该方面的专属文献</p>
            <p class="empty-state-desc">该组合暂无收录的资料。您可以尝试其他方面，或点击下方按钮反馈需求。</p>
            <!-- 服务端算出的「该组合实际收录了哪些方面」比上面那句通用提示更有用，降一级显示 -->
            <p class="miss-reason">{{ miss.reason }}</p>
            <div v-if="miss.alternatives.length" class="miss-alt">
              <span class="miss-alt-label">换个方面试试：</span>
              <button
                v-for="c in miss.alternatives"
                :key="c"
                class="btn btn-sm btn-outline"
                type="button"
                @click="useIntent(c)"
              >{{ INTENT_LABELS[c] }}</button>
            </div>
            <div class="fb-prompt">
              <span class="fb-prompt-text">
                如果这个问题对您很重要，点击【反馈此问题】，我们会优先补充。
              </span>
              <button
                class="btn btn-sm btn-soft"
                type="button"
                :disabled="gapId == null || gapFeedbackSent"
                :title="gapId == null ? '缺口登记未完成，暂时无法反馈' : '已经反馈过了，我们会优先补充'"
                @click="sendFeedback"
              >{{ gapFeedbackSent ? '已反馈' : '反馈此问题' }}</button>
            </div>
            <div class="miss-alt">
              <button class="btn btn-sm btn-ghost" type="button" @click="router.push({ name: 'kb' })">
                去知识库看看已收录的资料 ›
              </button>
              <!-- 未命中同样能进后台：「为什么没命中」正是后台该说清的事 -->
              <button
                v-if="rawPool"
                class="btn btn-sm btn-outline btn-sage"
                type="button"
                @click="openWorkbench"
              >查看后台分析</button>
            </div>
          </div>
        </section>

        <!-- 结果 -->
        <section v-else-if="result" class="adv-card">
          <div class="answer-card">
            <div class="answer-head">
              <span class="answer-title">{{ shown?.ethnicity }} · {{ shown?.disease }}</span>
              <span v-if="shown" class="answer-badge">{{ INTENT_LABELS[shown.intent] }}</span>
            </div>

            <!-- 依据文献（可折叠；默认收起，避免大段原文喧宾夺主） -->
            <div v-if="evidenceList.length" class="evidence-fold">
              <button class="evidence-fold-head" type="button" @click="expandedEvidence = !expandedEvidence">
                <span class="evidence-fold-caret" :class="{ open: expandedEvidence }">▸</span>
                <span class="evidence-fold-label">依据文献：</span>
                <span class="evidence-fold-names">
                  {{ evidenceList.map((e) => `《${e.title}》`).join('、') }}
                </span>
              </button>
              <div v-show="expandedEvidence" class="evidence-fold-body">
                <p v-if="locatableCount" class="evidence-hint">
                  点击下方任一片段，可查看它在文献原文中的位置
                </p>
                <div v-for="(e, ei) in evidenceList" :key="ei" class="evidence-item">
                  <div class="evidence-src-row">
                    <button v-if="canLocate(e)" class="evidence-src" type="button" @click="openDoc(ei)">《{{ e.title }}》</button>
                    <span v-else class="evidence-src-text">《{{ e.title }}》</span>
                    <SourceBadge :level="e.sourceLevel" :org="e.sourceOrg" show-org />
                  </div>
                  <div
                    class="evidence-text"
                    :class="{ locatable: canLocate(e) }"
                    @click="canLocate(e) && openDoc(ei)"
                    v-html="evidenceHtml(e)"
                  ></div>
                </div>
              </div>
            </div>

            <!-- 答案正文：样式来自全局 answer.css（.answer-body），与对话页同一套 -->
            <div class="answer-conclusion answer-body" v-html="answerHtml"></div>

            <div class="adv-actions">
              <!-- 让这次检索的链路在后台可见：与对话页的「查看后台分析」是同一个去处 -->
              <button
                v-if="rawPool"
                class="btn btn-sm btn-outline btn-sage"
                type="button"
                title="在后台分析工作台查看这次检索的问题理解 / 知识检索 / 加工生成"
                @click="openWorkbench"
              >查看后台分析</button>
              <button
                v-if="result.answer"
                class="btn btn-sm btn-soft"
                type="button"
                :disabled="sharing"
                @click="share"
              >
                {{ sharing ? '分享中…' : '分享到一问一答社区' }}
              </button>
              <button
                v-if="sharedId != null"
                class="btn btn-sm btn-link"
                type="button"
                @click="goCommunity"
              >去社区看看 ›</button>
            </div>
          </div>
        </section>

        <!-- 初始空状态 -->
        <section v-else class="adv-card">
          <div class="empty-state">
            <p class="empty-state-title">选择民族、疾病与想了解的方面</p>
            <p class="empty-state-desc">
              下拉里只列出知识库已收录的组合，选好点「检索」即可，结果会附上可溯源的文献依据。
            </p>
          </div>
        </section>
      </main>
    </div>

    <!-- 证据原文：点片段后打开整份文献并定位高亮 -->
    <el-dialog v-model="docVisible" :title="docTitle || '文献原文'" width="72%" top="6vh" destroy-on-close>
      <DocEvidenceViewer
        v-if="docFullText"
        :full-text="docFullText"
        :evidence="docItems"
        :focus-index="docFocus"
      />
      <div v-else class="lit-note">正在加载文献原文…</div>
    </el-dialog>
  </div>
</template>

<style scoped>
.adv {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}

.adv-body {
  flex: 1;
  display: flex;
  align-items: flex-start;
}

/* ---------- 左侧历史栏 ---------- */
/* 228px 与对话页侧栏同宽：两处的条目字号、分组标题、悬停操作是一套形制，
   宽度不同会让「保持一致」只停在字面上 */
.adv-side {
  width: 228px;
  flex-shrink: 0;
  position: sticky;
  /* 60px 顶栏 + 1px 下边框。用 100vh 减去它，侧栏才能自己滚而不带着整页滚 */
  top: 61px;
  max-height: calc(100vh - 61px);
  overflow-y: auto;
  border-right: 1px solid var(--line);
  padding: 18px 8px 24px;
}
.side-head {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-2);
  padding: 0 12px 8px;
}
.side-hint,
.side-empty {
  font-size: 12.5px;
  color: var(--ink-3);
  line-height: 1.7;
  padding: 4px 12px;
}
.side-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
/* 时间线分组标题：与对话页 .conv-group 同一形制（小字浅色、吸顶、靠留白分隔）。
   吸顶时背景必须是不透明的 var(--bg)，否则条目会从标题下面透出来 */
.side-group {
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
.side-group:first-child { padding-top: 6px; }

.side-item {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  box-sizing: border-box;
  text-align: left;
  border: none;
  background: transparent;
  border-radius: 8px;
  padding: 10px 12px;
  cursor: pointer;
  transition: background 0.12s;
}
.side-item:hover { background: rgba(0, 0, 0, 0.04); }
.side-item.on { background: rgba(160, 82, 45, 0.08); }
.side-item-main { flex: 1; min-width: 0; }
.side-item-title {
  font-size: 13px;
  color: var(--ink);
  line-height: 1.4;
  /* 两行截断：「白族 + 糖尿病 + 疾病负担与严重程度」这种长组合单行放不下，
     硬截成一行会把疾病名吃掉，剩下的+片段反而看不出是哪条 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.side-item-meta {
  font-size: 11.5px;
  color: var(--ink-3);
  margin-top: 2px;
}
.side-item-actions {
  display: none;
  flex-shrink: 0;
  align-items: center;
  gap: 2px;
}
.side-item:hover .side-item-actions { display: flex; }

.adv-main {
  flex: 1;
  min-width: 0;
  width: 100%;
  max-width: 880px;
  margin: 0 auto;
  padding: 28px 20px 64px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.adv-head { display: flex; flex-direction: column; gap: 6px; }
.adv-title {
  font-size: 23px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: 0.01em;
}
.adv-sub { font-size: 13.5px; color: var(--ink-3); line-height: 1.7; }

/* ---------- 条件区 ---------- */
.adv-form {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 18px 20px;
  box-shadow: var(--shadow-sm);
}
.adv-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 14px;
}
.adv-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1 1 180px;
  min-width: 0;
}
.adv-label { font-size: 12.5px; color: var(--ink-3); }
.adv-select {
  width: 100%;
  font-family: var(--sans);
  font-size: 14px;
  color: var(--ink);
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 9px 11px;
  transition: 0.15s;
}
.adv-select:hover { border-color: var(--line-strong); }
.adv-select:focus {
  outline: none;
  border-color: var(--clay);
  background: var(--surface);
}
.adv-go { flex: none; padding: 10px 26px; }

.adv-hint { font-size: 13px; color: var(--ink-3); }

/* ---------- 结果区 ---------- */
.adv-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 18px 20px;
  box-shadow: var(--shadow-sm);
}
/* 原来的 .adv-loading（单行加载文案）已被 AnswerProgress 组件取代 */

.miss-alt {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.miss-alt-label { font-size: 12.5px; color: var(--ink-3); }

/* 服务端算出的具体原因（该组合实际收录了哪些方面），比上面那句通用提示低一级 */
.miss-reason {
  font-size: 12.5px;
  color: var(--ink-3);
  line-height: 1.75;
  max-width: 46em;
  margin-top: 6px;
}

/* 未命中时的反馈入口：与对话页（QaView）同一套形制（虚线框 + 文案 + 按钮）。
   两个页面共用这条提示语，改这里记得对一下 QaView 那边 */
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

/* 卡片骨架：与对话页回答卡同一形制（.answer-body 里的内容样式在全局 answer.css） */
.answer-card { display: flex; flex-direction: column; }
.answer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.answer-title { font-size: 15px; font-weight: 700; color: var(--ink); }
.answer-badge {
  font-size: 11.5px;
  color: var(--clay-deep);
  background: var(--clay-soft);
  border-radius: 999px;
  padding: 3px 10px;
}

.answer-conclusion { margin-top: 4px; }

/* ---------- 依据文献折叠 ----------
   与对话页（QaView）**同一套样式**：两处的内层 class 名完全一致，所以这里是逐条对齐的拷贝，
   不是各写一份。之前这一块是「一条虚线下划线 + 无边框的灰底块」，与对话页那个带边框的框体
   长得不一样——同一个组件出来的内容，换个页面就变了样。改这里记得对一下 QaView 那边。 */
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
.evidence-hint { font-size: 12px; color: var(--ink-3); margin-bottom: 10px; }
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
  background: none;
  border: 0;
  padding: 0;
  font-family: var(--sans);
  font-size: 13px;
  font-weight: 600;
  color: var(--clay-deep);
  cursor: pointer;
}
.evidence-src:hover { text-decoration: underline; }
.evidence-src-text { font-size: 13px; font-weight: 600; color: var(--ink-2); }
.evidence-text {
  font-size: 13.5px;
  line-height: 1.85;
  color: var(--ink-2);
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
.evidence-text :deep(mark) {
  background: rgba(255, 214, 102, 0.45);
  color: inherit;
  border-radius: 3px;
  padding: 0 1px;
}
/* 可见的跳转提示：光靠 cursor:pointer 在触屏与截图里都看不出这一块能点 */
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

/* ---------- 底部操作 ---------- */
.adv-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}

/* 窄屏：历史栏从左侧挪到上方并压矮。挤在 228px 的竖条里会让每条的标题只剩几个字，
   不如横过来给主区让出整幅宽度 */
@media (max-width: 860px) {
  .adv-body { flex-direction: column; }
  .adv-side {
    position: static;
    width: 100%;
    max-height: 220px;
    border-right: 0;
    border-bottom: 1px solid var(--line);
    padding: 12px 12px 14px;
  }
}

@media (max-width: 640px) {
  .adv-main { padding: 20px 14px 48px; }
  .adv-fields { flex-direction: column; align-items: stretch; }
  .adv-go { width: 100%; }
}
</style>
