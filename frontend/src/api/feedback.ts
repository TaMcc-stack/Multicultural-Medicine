import request from './request'

/**
 * 反馈状态：待补充 / 处理中 / 已补充。
 *
 * 由**管理员**在后台改（一条反馈补进知识库后才会变成 done）。
 * 编码是英文，因为在服务端是按白名单校验的取值，中文只存在于展示层。
 */
export type FeedbackStatus = 'pending' | 'processing' | 'done'

/** 状态码 → 展示文案与图标。声明顺序 = 后台状态切换按钮的排列顺序。 */
export const FEEDBACK_STATUS_LABEL: Record<FeedbackStatus, string> = {
  pending: '📝 待补充',
  processing: '⏳ 处理中',
  done: '✅ 已补充',
}

/**
 * 状态码 → 展示文案，**对未知取值兜底**。
 *
 * 直接用 `FEEDBACK_STATUS_LABEL[status]` 在业务码变了或读到历史数据时会拿到 undefined，
 * 界面上就是一个空白标签。这里统一落到「待补充」并原样显示未知码的兜底文案。
 */
export function feedbackStatusText(status?: string | null): string {
  if (status === 'done') return FEEDBACK_STATUS_LABEL.done
  if (status === 'processing') return FEEDBACK_STATUS_LABEL.processing
  return FEEDBACK_STATUS_LABEL.pending
}

/** 后台状态切换的按钮顺序：待补充 → 处理中 → 已补充 */
export const FEEDBACK_STATUS_ORDER: FeedbackStatus[] = ['pending', 'processing', 'done']

/** 状态码 → CSS 类名（配色见 FeedbackView / DemandView 的 .pill） */
export function feedbackStatusCls(status?: string | null): string {
  if (status === 'done') return 'done'
  if (status === 'processing') return 'processing'
  return 'pending'
}

/**
 * 一条用户反馈。
 *
 * `userId` 为 null = **匿名发布**（未登录发的帖），此时 `userName` / `nickname` / `avatar`
 * 也都是 null，展示名由前端兜底成「匿名用户」。
 */
export interface FeedbackItem {
  id: number
  userId: number | null
  userName: string | null
  nickname: string | null
  avatar: string | null
  content: string
  status: FeedbackStatus | string
  /** 已转为知识缺口时指向那条缺口；null = 还没转 */
  gapId: number | null
  likeCount: number
  /** 当前登录用户是否已点赞。**匿名访客恒为 false**——没有身份就无从判断 */
  liked: boolean
  /** 是否为当前登录用户发布 */
  owner: boolean
  createdAt: number | string
}

/** 反馈的作者展示名：昵称优先，其次用户名，都没有就是匿名 */
export function feedbackAuthor(f: FeedbackItem): string {
  return f.nickname || f.userName || '匿名用户'
}

/**
 * 留言板列表（按点赞数倒序）。
 *
 * **未登录也能调用**：这个接口在服务端的可选登录白名单里，带了 token 会额外回显 `liked`。
 * 因此不需要分「登录版」和「游客版」两个函数。
 */
export function listFeedback(limit?: number): Promise<FeedbackItem[]> {
  return request.get<FeedbackItem[]>('/feedback', { params: { limit } })
}

/** 发布一条反馈。未登录时服务端记匿名，登录则记到本人名下（由 token 决定，请求体里没有用户字段）。 */
export function createFeedback(content: string): Promise<FeedbackItem> {
  return request.post<FeedbackItem>('/feedback', { content })
}

/** 点赞（幂等：重复点不会重复计数） */
export function likeFeedback(id: number): Promise<FeedbackItem> {
  return request.post<FeedbackItem>(`/feedback/${id}/like`)
}

/** 取消点赞 */
export function unlikeFeedback(id: number): Promise<FeedbackItem> {
  return request.delete<FeedbackItem>(`/feedback/${id}/like`)
}

/**
 * 删除一条反馈。
 *
 * 只有**发布者本人或管理员**能删，服务端会把关（其余情况返回 403「无权限删除这条反馈」）。
 * 匿名帖没有本人，因此只有管理员删得掉。
 */
export function deleteFeedback(id: number): Promise<void> {
  return request.delete<void>(`/feedback/${id}`)
}

/** 改反馈状态（管理员） */
export function setFeedbackStatus(id: number, status: FeedbackStatus): Promise<FeedbackItem> {
  return request.post<FeedbackItem>(`/feedback/${id}/status`, { status })
}

/**
 * 转为知识缺口（管理员）。
 *
 * 三个槽位由调用方从反馈原文里识别后传上来——服务端没有民族/疾病词表，
 * 识别一律走前端的 `extractTags`（见 utils/medicalVocab.ts），避免再写第四份词表。
 */
export function feedbackToGap(
  id: number,
  slots: { ethnicity: string; disease: string; intent: string },
): Promise<FeedbackItem> {
  return request.post<FeedbackItem>(`/feedback/${id}/to-gap`, slots)
}
