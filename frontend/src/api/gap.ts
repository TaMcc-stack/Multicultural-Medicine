import request from './request'
import { intentLabel } from './qa'

/**
 * 一条知识缺口：「某个民族 × 某个疾病 × 某个方面」在知识库里没有资料。
 *
 * 与「检索历史」（`searchHistory.ts`）的区别是**全库唯一、跨用户汇总**：
 * 同一组合只有一行，被问次数是所有用户累加的。检索历史回答「我查过什么」，
 * 缺口回答「知识库缺什么」——所以前者的幂等键含 user_id，后者不含。
 */
export interface GapItem {
  id: number
  ethnicity: string
  disease: string
  /** 想了解的方面（意图码）；展示时用 intentLabel() 换中文 */
  intent: string
  /** 被问次数（全用户累加） */
  askCount: number
  /** 反馈人数（按用户去重，不是点击次数） */
  feedbackCount: number
  /** open 待补充 / filled 已补充 */
  status: 'open' | 'filled'
  /** 已补充时指向那份文档 */
  filledDocId: string | null
  /**
   * 来源之一：高级检索未命中自动登记过。
   * 与 `fromChat` **不互斥**——同一组合可以被两个入口都碰过，界面上两个徽标同时显示。
   */
  fromSearch: boolean
  /** 来源之一：用户在智能对话里主动点过「反馈此问题」 */
  fromChat: boolean
  /** 首次触发这条缺口的原始提问；高级检索来源没有原话，为 null */
  originalQuery: string | null
  createdAt: number | string
  lastAskedAt: number | string
}

/**
 * 登记一次未命中。
 *
 * 幂等键是「民族 + 疾病 + 方面」，由服务端判定新增还是次数 +1，前端无从选择——
 * 与检索历史同一套语义，所以这里也不返回「是不是新建的」。
 */
export function recordGap(payload: {
  ethnicity: string
  disease: string
  intent: string
  /** 组合串当原话（如「白族+高血压+患病率」）；高级检索没有自由问句，用三个槽位拼 */
  query?: string
}): Promise<GapItem> {
  return request.post<GapItem>('/gaps', payload)
}

/** 「反馈缺文献」：同一用户重复点不会再加（服务端按 (缺口, 用户) 去重） */
export function feedbackGap(id: number): Promise<GapItem> {
  return request.post<GapItem>(`/gaps/${id}/feedback`)
}

/**
 * 智能对话未命中时点【反馈此问题】。
 *
 * 与 {@link recordGap} 的区别：那条是高级检索**自动**登记（只加次数）；这条是用户**主动**
 * 反馈，服务端把「登记 + 反馈」一次做完，并打上「来自对话」的来源标记。
 * 三个槽位来自这一轮已经跑过的 NLU 结果，原样带上即可，前端不必再识别一次。
 */
export function chatFeedback(payload: {
  ethnicity: string
  disease: string
  intent: string
  /** 用户原话，让管理员在榜单上看到「到底是谁在问什么」 */
  question: string
}): Promise<GapItem> {
  return request.post<GapItem>('/gaps/chat-feedback', payload)
}

/**
 * 我在对话里反馈过的缺口（对话页据此回显「已反馈」）。
 *
 * 返回的是自己的反馈记录，不是运营数据，所以不需要管理员身份。
 * 回显按**槽位**比对（民族+疾病+方面），与「已反馈」在界面上的粒度一致：
 * 同一组合再问一次仍然显示已反馈——服务端本来就按 (缺口, 用户) 去重，再点也是空操作。
 */
export function myFeedbackSlots(): Promise<GapItem[]> {
  return request.get<GapItem[]>('/gaps/feedback/mine')
}

/** 把一个缺口转成「民族|疾病|方面」这个比对键（前后端都用它做槽位比对） */
export function gapSlotKey(g: { ethnicity: string; disease: string; intent: string }): string {
  return `${g.ethnicity}|${g.disease}|${g.intent}`
}

/**
 * 疾病槽位的占位值：用户问的病没被识别出来（NLU 词表里没有）时用它顶上。
 *
 * **为什么用占位串而不是留空**：`knowledge_gap.disease` 是 NOT NULL，而唯一键是
 * 「民族 + 疾病 + 方面」——留空的话 SQL 里 NULL 之间互不相等，同一组合每反馈一次就会
 * 新建一行，「同一缺口全库一行」的前提就没了。占位串能正常参与去重。
 *
 * 服务端 `GapService` 里有一份同值的兜底（收到空白时替换成它），改这里记得两处一起改。
 */
export const DISEASE_UNSPECIFIED = '未识别疾病'

/** 单条缺口——文献补录工作台据此显示「在补哪个缺口」并预填关键词 */
export function getGap(id: number): Promise<GapItem> {
  return request.get<GapItem>(`/gaps/${id}`)
}

/**
 * 榜单（仅管理员）。
 *
 * @param sort ask = 热门话题排行（按被问次数）；feedback = 未解决话题排行（按反馈人数，只含待补充）；
 *             resolved = 已解决历史（补录入库的 + 人工标记的）
 */
export function listGaps(
  sort: 'ask' | 'feedback' | 'resolved',
  limit?: number,
): Promise<GapItem[]> {
  return request.get<GapItem[]>('/gaps', { params: { sort, limit } })
}

/**
 * 人工标记「已解决」（管理员）：从「未解决话题排行」移到「已解决历史」。
 *
 * 与「补录文献 → 审核通过后自动结掉」是两条路——这条不关联任何文档，
 * 用于管理员判定「这条不用补了」。幂等，重复点不会报错。
 */
export function resolveGap(id: number): Promise<GapItem> {
  return request.post<GapItem>(`/gaps/${id}/resolve`)
}

/** 展示标题：「白族 + 糖尿病 + 遗传相关研究」 */
export function gapTitle(g: GapItem): string {
  return `${g.ethnicity} + ${g.disease} + ${intentLabel(g.intent)}`
}
