import request from './request'

/**
 * 动态类型：多轮对话分享 / 一问一答卡片。
 *
 * 两者共用同一张表与同一套详情渲染（载荷都是 `{version:2, turns:[...]}`），
 * 区别只在卡片列表怎么画、以及问答卡**全局唯一**（同一「民族+疾病+意图」只有一张）。
 */
export type DynamicKind = 'dialogue' | 'qa'

/**
 * 动态 / 分享：由某条 AI 回答转存而来的公开内容。
 * createdAt / updatedAt 后端返回 LocalDateTime，可能是 ISO 字符串或时间戳，格式化时需兼容。
 */
export interface DynamicItem {
  id: number
  userId: number
  username: string
  nickname: string
  /** 作者的头像配色 key（按 key 取渐变画「首字头像」）；用户已注销时为 null */
  avatar?: string | null
  sourceConversationId: number | null
  sourceMessageId: number | null
  title: string
  content: string
  /** 动态类型：dialogue（多轮对话分享）/ qa（一问一答卡片）。缺失视为对话（历史数据） */
  kind?: DynamicKind
  /** 问答卡的规范化键「民族|疾病|意图」；对话类为 null */
  qaKey?: string | null
  /** 结构化回答 JSON 字符串（详情页还原展示用） */
  detailJson: string | null
  favoriteCount: number
  /** 当前登录用户是否已收藏 */
  favorited: boolean
  /** 是否为当前登录用户发布 */
  owner: boolean
  createdAt: number | string
  updatedAt: number | string
}

export interface CreateDynamicPayload {
  conversationId?: number | null
  messageId?: number | null
  title?: string
  content: string
  detail?: Record<string, any> | null
}

/** 新增动态（同一条 AI 回答重复添加时后端幂等返回已有动态） */
export function createDynamic(data: CreateDynamicPayload): Promise<DynamicItem> {
  return request.post<DynamicItem>('/dynamics', data)
}

/**
 * 公开动态列表（按发布时间倒序）。
 * `before` 为游标：传上一页最后一条的 id，取更早的一页；不传则取第一页。
 * `kind` 过滤动态类型：不传 = 全部（对话 + 问答），传 'qa' 只取一问一答卡。
 * 两个 Tab 的游标各自独立，切换时要重置游标重新拉第一页，不能共用一个。
 */
export function listDynamics(
  limit?: number,
  before?: number | null,
  kind?: DynamicKind,
): Promise<DynamicItem[]> {
  const params: Record<string, number | string> = {}
  if (limit != null) params.limit = limit
  if (before != null) params.before = before
  if (kind) params.kind = kind
  return request.get<DynamicItem[]>('/dynamics', { params })
}

/**
 * 发布 / 刷新一张「一问一答」卡片。
 *
 * **全局唯一**：同一个「民族 + 疾病 + 意图」在社区里只会有一张卡，重复分享是**刷新**
 * 内容与标题，不新增。幂等键是 `qaKey`，由后端按它跨用户查重，所以换个账号分享
 * 同一组合仍然只有一张卡（`user_id` 保持首次分享者）。
 */
export function publishQaCard(payload: {
  qaKey: string
  title?: string
  content: string
  detail?: Record<string, any> | null
}): Promise<DynamicItem> {
  return request.post<DynamicItem>('/dynamics/qa', payload)
}

/** 我的分享 */
export function listMyDynamics(limit?: number): Promise<DynamicItem[]> {
  return request.get<DynamicItem[]>('/dynamics/mine', { params: { limit } })
}

/**
 * 指定用户的公开分享（个人主页查看他人时用）。
 *
 * 注意：后端返回的 `owner` / `favorited` 是相对**当前登录用户**算的，
 * 不是相对被查看的那个人——所以列表里「收藏」按钮的状态是对的，
 * 而「我的」标记会正确地不出现。
 */
export function listUserDynamics(userId: number, limit?: number): Promise<DynamicItem[]> {
  return request.get<DynamicItem[]>(`/dynamics/user/${userId}`, { params: { limit } })
}

/** 当前用户已添加为动态的来源消息ID（对话页「已添加」状态回显） */
export function listAddedMessageIds(conversationId?: number | null): Promise<number[]> {
  return request.get<number[]>('/dynamics/added', {
    params: conversationId ? { conversationId } : undefined,
  })
}

/** 动态详情 */
export function getDynamic(id: number): Promise<DynamicItem> {
  return request.get<DynamicItem>(`/dynamics/${id}`)
}

/** 删除动态（仅发布者本人） */
export function deleteDynamic(id: number): Promise<void> {
  return request.delete<void>(`/dynamics/${id}`)
}

/** 动态作者展示名：昵称优先 */
export function dynamicAuthor(d: DynamicItem): string {
  return d.nickname || d.username || `用户${d.userId}`
}

/** 兼容 ISO 字符串与毫秒时间戳的时间格式化 */
export function fmtDateTime(v: number | string | null | undefined): string {
  if (v == null || v === '') return ''
  const d = typeof v === 'number' ? new Date(v) : new Date(String(v).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(v)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 具体日期。
 * 列表里「3 分钟前」比「2026-09-12 14:30」好读得多——用户关心的是「新不新」，
 * 不是精确到分的时间点；超过 30 天才回退成具体日期。
 */
export function timeAgo(v: number | string | null | undefined): string {
  if (v == null || v === '') return ''
  const d = typeof v === 'number' ? new Date(v) : new Date(String(v).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(v)
  const diff = Date.now() - d.getTime()
  // 时钟偏差可能让服务端时间比本地略晚，负数一律按「刚刚」处理
  if (diff < 60_000) return '刚刚'
  const mins = Math.floor(diff / 60_000)
  if (mins < 60) return `${mins} 分钟前`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days === 1) return '昨天'
  if (days < 30) return `${days} 天前`
  return fmtDateTime(v)
}
