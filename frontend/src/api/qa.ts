import request from './request'
// 来源等级是跨模块共享的契约（证据池与知识库文档共用同一套取值），定义在 api/kb.ts
// 分区同理：证据条目要带 partition，前端才知道溯源时展示文档片段还是 PDF 原文
import type { KbPartition, SourceLevel } from './kb'

/**
 * 查询意图英文编码。
 *
 * 前 5 档（prevalence/risk/diet/genetics/overview）是**智能对话**能识别出的全部意图，
 * 由服务端 `understanding.py:VALID_INTENTS` 与 Java `QaService.VALID_INTENTS` 划定；
 * 后 5 档（prevention/symptoms/medication/treatment/burden）**只有高级检索会用**——
 * 那条链路跳过 `/api/understand`，三个槽位由下拉直接给出，所以不经过意图识别，
 * 也就不必（更不应该）去扩智能对话的关键词表和澄清选项。
 *
 * 后果要记住：智能对话里自由输入永远问不出后 5 档，只有高级检索能选到它们。
 */
export type IntentCode =
  | 'prevalence'
  | 'risk'
  | 'diet'
  | 'genetics'
  | 'overview'
  | 'prevention'
  | 'symptoms'
  | 'medication'
  | 'treatment'
  | 'burden'
  | 'all'

/**
 * 意图码 → 中文标签（前端展示用）。
 *
 * 声明顺序 = 高级检索下拉的排列顺序，**按使用频率排**，与 Python `_INTENT_ORDER` 一致
 * （那边排的是 `pairs[].intents` 与默认选中项，这边排的是选项列表本身，两者是两条独立路径，
 * 改了其中一处记得对一下另一处；真正会「不一致就出 bug」的只有 `intentPhrases`，那份是服务端下发的）。
 *
 * 注意 `diet` 在这里是「饮食与生活方式」，而标准问句里用的是「饮食情况」——两者**本来就不该相等**，
 * 标准问句要贴着文献用词才对 BM25 有利。
 */
export const INTENT_LABELS: Record<IntentCode, string> = {
  // 「患病率/发病率」是从原来的「患病情况」里拆出来的窄档：只答「率」类的数据。
  // 笼统的「患病情况」下沉成兜底档 all（高级检索里排在最后那个选项）。
  prevalence: '患病率/发病率',
  risk: '危险因素',
  prevention: '预防与筛查',
  diet: '饮食与生活方式',
  genetics: '遗传相关研究',
  medication: '用药注意事项',
  symptoms: '症状与早期信号',
  /**
   * 「治疗与干预方向」这一档**不在高级检索的下拉里**（当前的下拉是用户指定的 9 项）。
   * 码与标签都留着：它是服务端的一张合法意图表项，去掉会让 `Record<IntentCode, …>`
   * 缺键、也会让历史记录里的旧值显示成英文码。要重新放进下拉，加进
   * `AdvancedSearchView` 的 `ADVANCED_INTENTS` 即可。
   */
  treatment: '治疗与干预方向',
  burden: '疾病负担与严重程度',
  /** 兜底档：不指向某个方面，走全量检索 + 综合回答 */
  all: '患病情况',
  /**
   * 只有对话页（NLU）会产出这个码，高级检索的下拉里**没有**它。
   * 上一行 all 才是高级检索的兜底项——两者行为一致，但语义场合不同：
   * overview 是「研究总体情况」（对话页的泛化意图），all 是「用户没选具体方面」。
   */
  overview: '研究概况',
}

/** 把意图码（或历史中文值）转为中文标签；空值回退「相关」 */
export function intentLabel(code?: string | null): string {
  if (!code) return '相关'
  return INTENT_LABELS[code as IntentCode] || code
}

/**
 * 界面标签太长，直接拿去当检索词会拖低召回（「遗传相关研究」不如「遗传」）。
 * 这里按后缀表机械缩短，而不是再维护一张 10 档的短名表——那种平行表迟早与
 * INTENT_LABELS 漂移，本项目已经因为「同一逻辑多份拷贝」吃过多次亏。
 *
 * 表外的意图原样返回（患病情况 / 危险因素 / 研究概况本就是短词），不做猜测。
 */
const SEARCH_SUFFIXES = [
  '相关研究', '与生活方式', '注意事项', '与干预方向', '与严重程度', '与早期信号', '与筛查',
]

/** 意图码 → 用于外部文献站检索的短词，如 genetics → 「遗传」 */
export function intentSearchWord(code?: string | null): string {
  const full = intentLabel(code)
  for (const suffix of SEARCH_SUFFIXES) {
    if (full.endsWith(suffix)) return full.slice(0, -suffix.length)
  }
  return full
}

/**
 * 置信度 → 澄清档位（状态仍统一为 clarify，仅区分文案）：
 * 0.6~0.8「建议澄清」；<0.6「必须澄清」；无置信度（关键词降级）默认「建议澄清」。
 */
export type ClarifyTier = 'suggest' | 'must'
export function clarifyTier(conf: number | null | undefined): ClarifyTier {
  return conf != null && conf < 0.6 ? 'must' : 'suggest'
}
export const CLARIFY_TIER_LABEL: Record<ClarifyTier, string> = {
  suggest: '建议澄清',
  must: '必须澄清',
}

/** 问题理解结果：民族 / 疾病 / 查询意图 / 问题类型 / 标准化问题 / 置信度 / 分级 */
export interface Understanding {
  /** 民族（后端已规范化） */
  ethnicity: string
  /** 疾病 */
  disease: string
  /** 查询意图（英文 5 码：prevalence / risk / diet / genetics / overview） */
  intent: IntentCode | string
  /** 查询意图（与 intent 一致，方便按新字段消费） */
  query_intent?: string
  /** 问题中明确问到的具体指标（如「患病率」「危险因素」）；未指明则为空 */
  metric?: string | null
  /** 查询意图展示名：命中具体指标时为指标名（患病率），否则为通用名（患病情况） */
  intent_label?: string | null
  /** 问题类型：事实查询 / 比较评价 / 因素分析 / 因果分析 */
  question_type?: string
  /** 标准化后的规范问句，可直接用于检索 */
  standardized_question?: string
  /** 置信度 0-1 */
  confidence: number
  /** pass | clarify | fail */
  status: 'pass' | 'clarify' | 'fail'
  /**
   * 意图不明确的原因码（仅 clarify 时有意义）：
   * - no_intent：民族/疾病齐全，但完全没识别出查询意图
   * - inferred_intent：问题里没有任何意图关键词，意图是大模型推测的
   *   （如只输入「白族糖尿病」）——不替用户猜，让用户选
   * - ambiguous：命中「怎么样/如何/什么情况」等模糊词，且未指明具体指标
   *    （如「患病情况如何」——患病情况是上位概念，含患病率/知晓率/控制率）
   * - 空串：缺民族或缺疾病导致的 clarify
   */
  clarity_reason?: string
  /**
   * 泛化检索标记：民族明确、只缺疾病时后端仍放行（用「民族 + 意图」检索），
   * 前端据此给出「暂未收录…您是否想了解…」的降级提示，而不是报「无法理解」。
   */
  generalized?: boolean
  /** 本轮仍缺失的槽位（ethnicity / disease / intent） */
  missing?: string[]
  /** 民族是从上一轮对话继承来的（追问场景），前端可提示「已接着上文理解」 */
  ethnicity_from_context?: boolean
  /** 疾病是从上一轮对话继承来的 */
  disease_from_context?: boolean
  /**
   * 用药 / 诊疗咨询：命中医疗安全边界。
   * 后端据此不做任何检索、直接返回固定拒绝话术；前端据此跳过「民族 + 疾病」校验。
   */
  medical_advice?: boolean
}

/**
 * 前台「验证通过」后暂存到 localStorage 的问题（key: `mmx_question_v2`）。
 *
 * **这不是接口契约，是本地持久化形状**——`Understanding` 的子集，由对话页写入，
 * 后台工作台与 analysis store 读取。字段全部可选（除 `text`）是因为读到的是
 * **未校验的历史 JSON**：老版本写入的记录可能缺字段，当成必填会在运行时骗过类型检查。
 *
 * 此前这个形状在 QaView / BackendView / analysis store 里**各写了一份**，
 * 三份已经漂移（BackendView 那份漏了 `medicalAdvice`，直接导致类型检查失败）。
 * 现在只此一处定义，三处共用。
 */
export interface StoredQuestion {
  text: string
  ethnic?: string
  disease?: string
  intent?: string
  /** 查询意图展示名（命中具体指标时为「患病率」等指标名，否则为通用意图名） */
  intentText?: string
  questionType?: string
  standardized?: string
  status?: string
  confidence?: number | null
  /**
   * 用药 / 诊疗咨询：命中医疗安全边界。
   * 后端据此不做任何检索、直接返回固定拒绝话术；前端据此跳过「民族 + 疾病」校验。
   */
  medicalAdvice?: boolean
  /**
   * 这次分析来自哪个入口，后台分析工作台据此标「来源」。
   * 缺省视为 `'dialogue'`——存量数据都没有这个字段，那时只有对话页会写分析。
   */
  source?: 'dialogue' | 'search'
  /** 写入时间戳（毫秒） */
  ts?: number
}

/** 知识库提取智能体返回：证据是否命中 + 证据片段 + 原因 */
export interface RetrieveResult {
  evidence_found: boolean
  evidence: {
    id?: string
    title: string
    fragment: string
    page?: string
    doi?: string | null
    url?: string
    source?: string
    /** 片段在文档全文中的起止字符区间；-1 表示定位不到（如论文证据没有本地全文） */
    startOffset?: number
    endOffset?: number
    /** 命中的关键词（长词在前）：前端在片段里标出来，说明这条为什么被检索到 */
    matchedTerms?: string[]
    /** 来源等级（一级分类）：official / web_crawl / user_upload */
    sourceLevel?: SourceLevel
    /** 来源机构：国家卫健委 / 人民日报 / 用户上传 … */
    sourceOrg?: string | null
    /** 原始链接（网页抓取时记录） */
    sourceUrl?: string | null
    /** 知识库分区：raw 的溯源展示 PDF 原页，integrated 展示正文片段 */
    partition?: KbPartition
  }[]
  reason: string
  /**
   * 有证据，但都不对应用户所问的方面（如问饮食只找到患病率背景资料）。
   * 后台据此明示「未找到与【饮食】直接相关的证据」，避免把不相干资料当作该问题的证据。
   */
  intent_missing?: boolean
  /** 触发 intent_missing 的查询意图码 */
  intent?: string
}

/** 结构化回答：专家核心解答 + 文献数据说明 + 专家提醒与适用边界 + 💡您可能还想问（可点击追问） */
export interface GenerateAnswer {
  conclusion: string
  /** 文献数据说明（Markdown 正文） */
  detailed?: string
  /** 👨‍⚕️ 专家行动建议：筛查 / 饮食 / 就医建议（新结构） */
  actions?: string
  /** 📚 证据来源：一句「依据《文献名》」 */
  sources?: string
  /** 🎯 适用范围：该数据适用于「地区+年龄+人群」，缺信息时写明「未明确说明」 */
  scope?: string
  /** 依据分节（旧结构兼容，历史数据回放用） */
  sections?: { title: string; content: string }[]
  /** 适用人群（旧结构字段，新结构下为「—」） */
  applicable?: string
  /** 研究时间/地区（旧结构字段，新结构下为「未明确」） */
  timeRegion?: string
  /** 专家提醒：新结构是「特别提醒」，旧结构是「局限性与注意」条目 */
  cautions?: string
  /** 💡 您可能还想问：3 个可点击追问选项 */
  followUps?: string[]
  format: 'text' | 'table' | 'chart'
  table?: { columns: string[]; rows: string[][] } | null
  chart?: { title: string; unit?: string; data: { label: string; value: number }[] } | null
  engine?: string
}

/** 加工栏大模型返回：结构化回答（answer 为 null 表示证据不足） */
export interface GenerateResult {
  answer: GenerateAnswer | null
  evidence_found: boolean
}

/** 证据充分度状态：🟢 充分 / 🟡 部分相关 / 🔴 无直接证据 */
export type EvidenceStatus = 'sufficient' | 'partial' | 'none'

/** 单个候选资料里提取出的一条证据（含全文偏移，供点击定位高亮） */
export interface CandidateEvidence {
  topic?: string
  fragment: string
  startOffset?: number
  endOffset?: number
  page?: number | null
  /** 命中的关键词（长词在前），供前端在片段里高亮 */
  matchedTerms?: string[]
  /** 来源等级（一级分类）——证据池里的候选也带来源，供前端标注 */
  sourceLevel?: SourceLevel
  /** 来源机构：国家卫健委 / 人民日报 / 用户上传 … */
  sourceOrg?: string | null
}

/** 知识证据池里的一份候选资料（含完整资料字段，供左栏点击展开查看整份资料） */
export interface PoolCandidate {
  source: 'papers' | 'kb'
  id: string
  title: string
  ethnicity?: string
  disease?: string
  journal?: string
  year?: string
  volume?: string
  authors?: string
  population?: string
  study_type?: string
  study_year?: string
  doi?: string | null
  url?: string
  findings?: string
  limitation?: string
  evidences?: PaperEvidence[]
  content?: string
  /** 文档全文（KB 文档候选专有，供左栏全文浏览） */
  fullText?: string
  table?: {
    page?: number
    caption?: string
    header: string[]
    rows: string[][]
    note?: string
  } | null
  status: EvidenceStatus
  evidence: CandidateEvidence[]
  /** 这份资料属于哪个库；后台「检索结果」列表据此标注「整合资料 / 原始文献」 */
  partition?: KbPartition
}

/** 知识证据池返回：候选资料 + 证据充分度分级 + 打包证据 */
/**
 * 证据池打包好的一条证据：候选资料里挑出的那一段，配好原文定位信息。
 *
 * 与 `CandidateEvidence`（候选内部的那一条）字段基本一致，多出 `id` / `title` / `doi` / `url`
 * ——因为这里已经脱离了候选，得自己带上「来自哪份文档」。服务端的实际返回见
 * `main.py:_entry_to_evidence`；`id` 为空串表示这份证据没有文档可定位（如 papers.json 的条目），
 * `startOffset` 为 -1 表示在原文里没找到位置——两者都不可点开原文。
 */
export interface PoolEvidence {
  id?: string
  title: string
  fragment: string
  /** 在文档全文中的字符偏移；-1 = 定位失败 */
  startOffset?: number
  endOffset?: number
  /** 命中的关键词（长词在前），供前端在片段里高亮 */
  matchedTerms?: string[]
  page?: string
  doi?: string | null
  url?: string
  /** 文献出处（期刊/出版社），与 sourceLevel 是两条不同的轴 */
  source?: string
  sourceLevel?: SourceLevel
  sourceOrg?: string | null
  sourceUrl?: string
  /**
   * 这条证据来自哪个库。`raw` 的溯源该展示 PDF 原页（`kbPageImageUrl`），
   * `integrated` 的展示文档片段即可。
   */
  partition?: KbPartition
}

export interface EvidencePoolResult {
  status: EvidenceStatus
  candidates: PoolCandidate[]
  evidence_found: boolean
  evidence: PoolEvidence[]
  reason: string
  /**
   * 有背景资料，但没有一条对应用户所问的方面（如问饮食只找到患病率资料）。
   * 后台据此显示「❌ 未找到与【饮食】直接相关的证据片段…」，避免误判为已检索到证据。
   */
  intent_missing?: boolean
  /** 命中医疗安全边界：未做检索，直接返回拒绝话术 */
  blocked?: boolean
  /**
   * 本次检索实际用的是哪一档知识库：
   * `integrated` = 命中整合资料库（优先档）；`raw` = 整合资料未命中，已降级检索原始文献库。
   * 后台「知识检索」模块据此显示来源档次。
   */
  retrievalTier?: 'integrated' | 'raw'
  /** 上面那一档的一句话说明，直接展示即可 */
  tierNote?: string
}

/** 知识库文档全文（供「点击证据片段 → 打开原文并定位高亮」） */
export interface DocFullText {
  doc_id: number
  title: string
  full_text: string
}

/**
 * 取知识库文档全文。
 * 走 /api/ai/... 前缀：AI 服务自身也有 /api/kb/documents/{id}，但 vite 把 /api/kb
 * 代理到了 Spring Boot（两边 doc_id 空间不同），前端只能走 /api 兜底到 AI 服务 8000。
 */
export function fetchDocFullText(docId: string): Promise<DocFullText> {
  return request.get<DocFullText>(`/ai/kb/documents/${docId}`)
}

/** 知识库文档（localStorage 中的结构） */
export interface KnowledgeDoc {
  id: string
  title: string
  category?: string
  content?: string
  excerpt?: string
  table?: {
    page?: number
    caption?: string
    header: string[]
    rows: string[][]
    note?: string
  }
  ethnic?: string
  disease?: string
}

/** papers.json 里一条证据片段 */
export interface PaperEvidence {
  topic?: string
  ethnicity?: string
  content: string
}

/** papers.json 里的一篇真实文献（后端 /api/papers 返回） */
export interface Paper {
  id: string
  title: string
  authors?: string
  journal?: string
  year?: string
  volume?: string
  doi?: string | null
  url?: string
  ethnicity?: string
  disease?: string
  population?: string
  studyYear?: string
  studyType?: string
  findings?: string
  limitation?: string
  evidences?: PaperEvidence[]
}

/** 拉取真实文献数据集（papers.json），供知识库书架展示 */
export function fetchPapers(): Promise<Paper[]> {
  return request.get<Paper[]>('/papers')
}

/** 一问一答的轻量上下文，用于让后端补全追问里省略的实体 */
export interface TurnContext {
  question: string
  ethnicity?: string
  disease?: string
}

/**
 * 语义理解：调用 FastAPI /api/understand（Qwen 结构化拆解）。
 * 这是本产品「我的对话逻辑」的核心第一步：验证 → 澄清 → 回答。
 *
 * history 必须带上最近几轮：追问常省略疾病名（「有没有适合白族人的日常血糖监测方法」），
 * 后端要靠它把「糖尿病」继承下来，否则会被判成「缺疾病」而拒绝回答。
 */
export function understand(question: string, history: TurnContext[] = []): Promise<Understanding> {
  return request.post<Understanding>('/understand', { question, history })
}

/** 知识库提取智能体：检索知识库并判断证据是否真的回答了查询意图 */
export function retrieve(
  question: string,
  understand: Partial<Understanding>,
  knowledge: KnowledgeDoc[],
): Promise<RetrieveResult> {
  return request.post<RetrieveResult>('/retrieve', { question, understand, knowledge })
}

/** 加工栏大模型：仅依据问题 + 证据生成最终回答 */
export function generate(
  question: string,
  understand: Partial<Understanding>,
  retrieve_result: RetrieveResult,
  history: TurnContext[] = [],
): Promise<GenerateResult> {
  return request.post<GenerateResult>('/generate', { question, understand, retrieve_result, history })
}

/** 知识证据池：候选资料检索 → 证据提取 → 状态分级（papers.json + 知识库合并检索） */
export function evidencePool(
  question: string,
  understand: Partial<Understanding>,
  knowledge: KnowledgeDoc[],
  history: TurnContext[] = [],
): Promise<EvidencePoolResult> {
  return request.post<EvidencePoolResult>('/evidence-pool', { question, understand, knowledge, history })
}
