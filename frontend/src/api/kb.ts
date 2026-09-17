import request from './request'

/**
 * 来源等级（一级分类）。
 *
 * 与 `KbDoc.source` **不是一回事**：`source` 是文献出处（期刊/出版社，由正文识别）；
 * 这里记的是资料**进来的渠道**。两条轴，字段名刻意错开。
 */
export type SourceLevel = 'official' | 'web_crawl' | 'user_upload'

/** 审核状态；只有 user_upload 会落到 pending */
export type ReviewStatus = 'approved' | 'pending' | 'rejected'

/**
 * 知识库分区。与 `SourceLevel` 是**两条正交的轴**：
 * 来源等级说「这份资料可不可信」，分区说「它是论文原文还是人工提炼过的汇编」。
 *
 * 检索时先查 `integrated`（整合资料库），那里答不上来才降级查 `raw`（原始文献库）——
 * 因为提炼过的资料给出的答案更直接，而原文里同一个问题往往散落在长篇段落中。
 */
export type KbPartition = 'integrated' | 'raw'

/** 知识库文档（列表项，不含全文） */
export interface KbDoc {
  id: string
  title: string
  category: string
  fileName: string
  originalName: string
  size: number
  ext: string
  mime: string
  uploadedAt: number
  pageCount: number | null
  hasContent: boolean
  /** 自动识别的作者（可能为 null） */
  author?: string | null
  /** 自动识别的出处/期刊/出版社（可能为 null） */
  source?: string | null
  /** 自动识别的发表年份（可能为 null） */
  publishYear?: number | null
  /** 自动生成的摘要/概要（可能为 null） */
  summary?: string | null
  /** 文章总结（AI 概括主要内容，可能为 null） */
  articleSummary?: string | null
  /** 结构化证据（JSON 字符串：source/author/ethnicity/disease/studyType/studyYear/population/conclusion/limitations/fragments），可能为 null */
  evidenceJson?: string | null
  /** 后台处理状态：processing（解析中）/ done（完成）/ error（失败） */
  status?: string
  /** 后台处理进度 0-100（处理中时有效） */
  progress?: number
  /** 后台处理阶段文案（如「正在生成文章总结」，处理中时有效） */
  stage?: string | null

  // ── 来源维度（一级分类）──────────────────────────────────
  /** 来源等级：官方上传 / 网页抓取 / 用户上传。由**后端按上传者身份裁决**，前端传的只是申请 */
  sourceLevel?: SourceLevel
  /** 来源机构：国家卫健委 / 人民日报 / 用户上传 … */
  sourceOrg?: string | null
  /** 原始链接（网页抓取时记录） */
  sourceUrl?: string | null
  /** 审核状态 */
  reviewStatus?: ReviewStatus
  /** 上传者用户 ID；鉴权修复前入库的历史数据为 null */
  userId?: number | null

  // ── 知识库分区（与上面的「来源等级」是两条正交的轴）────────────
  /**
   * 入库时按扩展名自动判定（pdf → 原始文献库，其余 → 整合资料库），管理员可手工改。
   *
   * <p>“来源等级”说的是这份资料**可不可信**；“分区”说的是它是**原文还是提炼过的**。
   * 检索时先查整合资料库，命中不了才降级查原始文献库。</p>
   */
  partition?: KbPartition
}

/** 文档后台处理状态（上传后轮询用） */
export interface KbDocStatus {
  status: string
  progress: number
  stage?: string | null
}

/** 文档详情（含提取的全文 content） */
export interface KbDocDetail extends KbDoc {
  content: string
}

/** 解析预览（不落库，供用户确认/编辑后再入库） */
export interface KbParsePreview {
  title: string
  category?: string | null
  source?: string | null
  author?: string | null
  publishYear?: number | null
  summary?: string | null
  /** 文章总结（AI 概括主要内容） */
  articleSummary?: string | null
  /** 结构化证据 JSON 字符串（EvidenceData 序列化） */
  evidenceJson: string
  pageCount?: number | null
  hasContent: boolean
}

/** 列表；可按来源等级 / 审核状态筛选（后者仅官方账号有效，普通用户只会拿到已通过的 + 自己的待审核） */
export function listKbDocs(filter?: { sourceLevel?: SourceLevel; reviewStatus?: ReviewStatus }): Promise<KbDoc[]> {
  return request.get<KbDoc[]>('/kb/docs', { params: filter })
}

/** 详情（含全文） */
export function getKbDoc(id: string): Promise<KbDocDetail> {
  return request.get<KbDocDetail>(`/kb/docs/${id}`)
}

/** 文档后台处理状态（上传后轮询进度） */
export function getKbDocStatus(id: string): Promise<KbDocStatus> {
  return request.get<KbDocStatus>(`/kb/docs/${id}/status`)
}

/** 删除 */
export function deleteKbDoc(id: string): Promise<{ ok: boolean }> {
  return request.delete<{ ok: boolean }>(`/kb/docs/${id}`)
}

/** 原文文件下载/预览地址（PDF 可在新标签内联查看） */
export function kbFileUrl(id: string): string {
  const base = import.meta.env.VITE_API_BASE || '/api'
  return `${base}/kb/files/${id}`
}

/** PDF 某页的原页图（渲染 PNG），供阅读器在每页正文后显示原版页面（含矢量图） */
export function kbPageImageUrl(id: string, page: number): string {
  const base = import.meta.env.VITE_API_BASE || '/api'
  return `${base}/kb/docs/${id}/page/${page}`
}

/**
 * 解析（仅抽取、不落库）：返回自动识别的基本信息与结构化证据，供用户确认/编辑。
 * LLM 抽取可能较慢，放宽超时。
 */
export function parseKbDoc(
  file: File,
  opts: {
    title?: string
    category?: string
    source?: string
    author?: string
    publishYear?: number
  },
): Promise<KbParsePreview> {
  const fd = new FormData()
  fd.append('file', file)
  if (opts.title) fd.append('title', opts.title)
  if (opts.category) fd.append('category', opts.category)
  if (opts.source) fd.append('source', opts.source)
  if (opts.author) fd.append('author', opts.author)
  if (opts.publishYear) fd.append('publishYear', String(opts.publishYear))
  return request.post<KbParsePreview>('/kb/parse', fd, { timeout: 180000 })
}

/**
 * 审核用户提交的资料（通过 / 驳回）——仅管理员账号可调用。
 * 通过时需指定归入的一级分类（official=官方权威资料 / web_crawl=网页抓取补充）；
 * 通过后才推入 AI 检索索引；驳回会从索引移除。
 */
export function reviewKbDoc(
  id: string,
  decision: 'approved' | 'rejected',
  targetLevel?: 'official' | 'web_crawl',
): Promise<{ ok: boolean }> {
  return request.post<{ ok: boolean }>(`/kb/docs/${id}/review`, { decision, sourceLevel: targetLevel })
}

/**
 * 导入外部内容到知识库（仅官方账号）。
 *
 * 两种取数方式**结果一致**（都标 web_crawl、都进待审核），区别只在正文从哪来：
 *  - 给了 `text` → **粘贴模式**，直接采用（用于抓不了的站点，如 SinoMed）
 *  - 只给 `url`  → **抓取模式**，服务端去抓；域名必须在后端白名单内
 *
 * 粘贴模式下 `url` 只作溯源记录，服务端不会去访问它。
 */
export function importKbExternal(payload: {
  url?: string
  text?: string
  title?: string
  /** 文献补录工作台带上的缺口 id：非空则强制进待审核，审核通过且索引成功后自动结掉该缺口 */
  gapId?: number
}): Promise<KbDoc> {
  return request.post<KbDoc>('/kb/import', payload)
}

/**
 * 改属知识库分区（管理员）。
 *
 * 入库时按扩展名自动判定，判错了在这里手工改。**后端会重新同步检索索引**（数秒），
 * 所以调用方要给出忙碌反馈——否则界面看起来"点了没反应"。
 */
export function setKbDocPartition(id: string, partition: KbPartition): Promise<{ ok: boolean }> {
  return request.post<{ ok: boolean }>(`/kb/docs/${id}/partition`, { partition })
}

/** 管理员编辑文档标题 / 分类（标题重名时后端自动加序号，存储文件名同步改名） */
export function updateKbDocMeta(
  id: string,
  data: { title?: string; category?: string; articleSummary?: string; evidenceJson?: string },
): Promise<{ ok: boolean }> {
  return request.put<{ ok: boolean }>(`/kb/docs/${id}/meta`, data)
}

/** 上传（带进度回调）。返回含全文的文档详情。
 *  - evidenceJson：用户在「解析→确认」流程中回传的结构化数据（已核对），提供时后端直接采用并跳过 LLM 重复抽取
 *  - summary：用户确认/修改后的摘要
 *  - sourceLevel/sourceOrg/sourceUrl：来源维度。**sourceLevel 只是「申请」**——
 *    非官方账号传 official 也会被后端降级为 user_upload 并进入待审核。 */
export function uploadKbDoc(
  file: File,
  opts: {
    title?: string
    category?: string
    source?: string       // 出处/期刊（选填）
    author?: string       // 作者（选填）
    publishYear?: number  // 年份（选填）
    summary?: string      // 摘要（文档真实摘要，确认时可覆盖）
    articleSummary?: string // 文章总结（AI 概括主要内容，确认时可覆盖）
    evidenceJson?: string // 已确认的结构化证据 JSON
    sourceLevel?: SourceLevel
    sourceOrg?: string
    sourceUrl?: string
    /** 文献补录工作台带上的缺口 id（见 importKbExternal 的同名参数） */
    gapId?: number
    onProgress?: (percent: number) => void
  },
): Promise<KbDocDetail> {
  const fd = new FormData()
  fd.append('file', file)
  if (opts.title) fd.append('title', opts.title)
  if (opts.category) fd.append('category', opts.category)
  if (opts.source) fd.append('source', opts.source)
  if (opts.author) fd.append('author', opts.author)
  if (opts.publishYear) fd.append('publishYear', String(opts.publishYear))
  // 摘要/文章总结：即使为空字符串也要传（清空=合法值），仅 undefined(未提供)时不传，交给后端自动识别
  if (opts.summary !== undefined) fd.append('summary', opts.summary)
  if (opts.articleSummary !== undefined) fd.append('articleSummary', opts.articleSummary)
  if (opts.evidenceJson) fd.append('evidenceJson', opts.evidenceJson)
  if (opts.sourceLevel) fd.append('sourceLevel', opts.sourceLevel)
  if (opts.sourceOrg) fd.append('sourceOrg', opts.sourceOrg)
  if (opts.sourceUrl) fd.append('sourceUrl', opts.sourceUrl)
  if (opts.gapId != null) fd.append('gapId', String(opts.gapId))
  return request.post<KbDocDetail>('/kb/upload', fd, {
    onUploadProgress: (e: { loaded: number; total?: number }) => {
      if (opts.onProgress && e.total) {
        opts.onProgress(Math.min(99, Math.round((e.loaded / e.total) * 100)))
      }
    },
  })
}

// ── 文献补录工作台 ────────────────────────────────────────────────
/**
 * 整合资料「患病率数据」表的一行。列与工作台上的表格一一对应。
 *
 * **来源文献是硬要求**：整合资料的价值就在于每条数据都能追到具体文献。
 * 缺出处的数据看起来有据可查、实际无从核对，比不写更糟。
 */
export interface SupplyDataRow {
  ethnicity: string
  region: string
  ageRange: string
  sampleSize: string
  metric: string
  value: string
  source: string
}

/**
 * AI 从正文里抽出来的一行：字段**全部可空**——抽不到就该空着让管理员补，不要编。
 * 比 `SupplyDataRow` 多出 disease / year / note（表格用不上，但年份可以并进来源文献）。
 */
export interface ExtractedRow {
  ethnicity?: string | null
  disease?: string | null
  metric?: string | null
  value?: string | null
  source?: string | null
  year?: string | null
  region?: string | null
  ageRange?: string | null
  sampleSize?: string | null
  note?: string | null
}

/**
 * 把一段文献正文抽成结构化数据（LLM，best-effort）。
 *
 * 一篇里同时报多个民族/多个指标时会拆成多行——那正是要的效果，别把它当重复。
 * 抽取失败返回空数组（不是抛错）：工作台据此提示管理员手工填写，而不是卡住。
 *
 * `dropped` 是被**丢弃**的行数：那些行的数值或出处无法在原文里核对（模型编出来的），
 * 服务端已经剔掉了。界面要提示，否则管理员会以为这篇文献只有这么点数据。
 */
export async function extractSupplyRows(payload: {
  text: string
  ethnicity?: string
  disease?: string
}): Promise<{ rows: ExtractedRow[]; risks: string[]; advice: string[]; dropped: number }> {
  const r = await request.post<{
    ok: boolean
    rows: ExtractedRow[]
    risks?: string[]
    advice?: string[]
    dropped?: number
  }>('/ai/kb/extract-fields', payload)
  return {
    rows: r.rows || [],
    risks: r.risks || [],
    advice: r.advice || [],
    dropped: r.dropped || 0,
  }
}

/** 「整理成文档」的入参（预览与入库同一份） */
export interface SupplyPayload {
  gapId?: number
  ethnicity: string
  disease: string
  intent?: string
  /** 不传则服务端按「民族+疾病+方面」生成 */
  title?: string
  /** 文档级「适用范围」元数据：研究地区（如「云南大理」） */
  region: string
  /** 文档级「适用范围」元数据：年龄范围（如「≥18岁」） */
  ageRange: string
  /** 文档级「适用范围」元数据：样本量（如「5439人」） */
  sampleSize: string
  /** 文档级「适用范围」元数据：研究年份（如「2022年」） */
  year: string
  /** 患病率数据表的行，顺序即文档里的顺序 */
  rows: SupplyDataRow[]
  /** 危险因素列表 */
  risks: string[]
  /** 专家建议列表——只摘录原文里写的，系统不生成任何医疗建议 */
  advice: string[]
}

/**
 * 整理成文档：把若干片段渲染成一份整合文档，**只渲染、不落库**。
 *
 * Markdown / Word 由服务端渲染（与入库那份逐字一致），前端不自己拼——
 * 同一份模板两处维护，改一处忘另一处就会出现「预览看到的」≠「存进库的」。
 */
export function previewSupply(payload: SupplyPayload): Promise<{
  title: string
  markdown: string
  html: string
  count: number
}> {
  return request.post('/kb/supply/preview', payload)
}

/**
 * 把片段列表合成**一份**整合文档入库（进待审核）。
 *
 * 合成而不是逐片段入库：整批都冲着同一个「民族 × 疾病 × 方面」来，拼成一份
 * 才能完整回答这一个方面；拆成 N 份碎文档，每份只有一两段，什么都答不上来。
 */
export function submitSupply(payload: SupplyPayload): Promise<KbDoc> {
  return request.post<KbDoc>('/kb/supply', payload)
}

// ── 高级检索的下拉词表 ────────────────────────────────────────────
/** 一个「知识库里真有资料」的民族 × 疾病组合 */
export interface KbVocabPair {
  ethnicity: string
  /** 该组合实际覆盖的意图码（已按 prevalence→risk→diet→genetics→overview 排序，首项可作默认值） */
  intents: string[]
  disease: string
}

export interface KbVocab {
  ethnicities: string[]
  diseases: string[]
  pairs: KbVocabPair[]
  /**
   * 意图码 → **标准问句后缀**（服务端 `_INTENT_PHRASE` 的原文）。
   *
   * 高级检索页要把选中的三个槽位拼成一个问句再送检索，而那个问句必须与对话页的
   * 标准化问句**逐字一致**——否则 BM25 打分不同，同一个组合在两处会给出不同的证据排序。
   * 由服务端下发而不是前端自备一份，就是为了不出现「同一份措辞两处维护」。
   *
   * 注意它**不是**界面标签：界面上 diet 显示「饮食与生活方式」，这里给的是「饮食情况」。
   */
  intentPhrases: Record<string, string>
  /**
   * 意图码 → **检索硬门槛用的锚点词**（服务端 `_INTENT_ANCHOR_KEYWORDS` 的原文）。
   *
   * `_intent_relevant` 判定一条切片属不属于某个方面有两条路：「切片 topic 标签命中」
   * 或「正文命中锚点词」。而新入库的切片**没有 topic 标签**（kb.py 不给 RAG_CHUNK 写 topic），
   * 所以实际只剩后面那条——正文里一个锚点词都没出现，那份资料就永远检索不到，且不报错。
   *
   * 文献补录工作台据此给出可见提示：让管理员在补资料时就看见「这段文字里得有这些词」，
   * 而不是补完、审核通过、再检索依然未命中。
   */
  intentKeywords: Record<string, string[]>
}

/**
 * 取「知识库里真有资料」的民族 / 疾病 / 组合，供高级检索页的级联下拉使用。
 *
 * 走 **FastAPI（8000）** 而不是 Spring Boot —— 路径以 `/api/ai` 开头，vite 按前缀分流
 * （见 vite.config.ts）。判定在服务端扫描文档切片文本完成，前端传的只是候选范围。
 *
 * 之所以传候选而不是让服务端自己列：前端 `medicalVocab.ts` 那份词表覆盖面最广
 * （含心脏瓣膜病、NAFLD 等后端兜底词表没有的疾病），服务端再与自身兜底词表取并集。
 */
export function getKbVocab(ethnicities: string[], diseases: string[]): Promise<KbVocab> {
  return request.post<KbVocab>('/ai/kb/vocab', { ethnicities, diseases })
}
