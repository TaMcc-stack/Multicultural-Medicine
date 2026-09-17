import request from './request'

/**
 * 会话摘要（列表）。
 * createdAt / updatedAt 后端返回 LocalDateTime，序列化成 ISO 字符串
 * （如 "2026-09-11T17:34:20.549038"），无时区、按服务器本地时间。
 * 类型声明成 number | string 与 dynamic.ts 保持一致，格式化时需兼容两种。
 */
export interface Conversation {
  id: number
  title: string
  createdAt: number | string
  updatedAt: number | string
  /** 会话内消息数（后端 conversations 接口已返回，用于列表展示） */
  messageCount?: number
}

/** 会话消息（历史回放用） */
export interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  kind: string
  content: string
  /** assistant 消息的完整响应（含 answer/citations 等），用于完整回放；用户消息为 null */
  detail?: Record<string, any> | null
  /** 后端 LocalDateTime → ISO 字符串 */
  createdAt?: number | string
}

/** 会话详情：会话信息 + 全部消息 */
export interface ConversationDetail {
  conversation: Conversation
  messages: ChatMessage[]
}

export interface CreateConversationPayload {
  question: string
  title?: string
  ethnic?: string
  disease?: string
  intent?: string
  status?: string
  answer_summary?: string
}

/** 创建会话（title 可空，为空时后端按首问自动生成） */
export function createConversation(data: CreateConversationPayload): Promise<Conversation> {
  return request.post<Conversation>('/conversations', data)
}

/** 会话列表（按最后活跃倒序） */
export function listConversations(): Promise<Conversation[]> {
  return request.get<Conversation[]>('/conversations')
}

/** 会话详情 */
export function getConversation(id: number): Promise<ConversationDetail> {
  return request.get<ConversationDetail>(`/conversations/${id}`)
}

/**
 * 最近活跃的会话（含全部历史消息），用于刷新后恢复上一次的连续对话。
 * 没有任何会话时后端返回 null。
 */
export function getLastConversation(): Promise<ConversationDetail | null> {
  return request.get<ConversationDetail | null>('/conversations/last')
}

/** 重命名 */
export function renameConversation(id: number, title: string): Promise<Conversation> {
  return request.put<Conversation>(`/conversations/${id}`, { title })
}

/** 删除会话 */
export function deleteConversation(id: number): Promise<void> {
  return request.delete<void>(`/conversations/${id}`)
}

/**
 * 追加一条消息（仅持久化，不生成）：前端本地生成回答后落库，供历史回放。
 * 返回落库后的消息（含服务端自增 id），前端据此做「分享至动态」的来源溯源。
 */
export function appendMessage(
  id: number,
  payload: { role: string; kind: string; content: string; detail?: Record<string, any> | null },
): Promise<ChatMessage> {
  return request.post<ChatMessage>(`/conversations/${id}/messages`, payload)
}
