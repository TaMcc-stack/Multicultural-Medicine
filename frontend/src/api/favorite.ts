import request from './request'
import type { DynamicItem } from './dynamic'
import type { Conversation } from './chat'

/** 收藏动态（幂等） */
export function addFavorite(dynamicId: number): Promise<DynamicItem> {
  return request.post<DynamicItem>('/favorites', { dynamicId })
}

/** 取消收藏（只删除收藏关系，不影响原动态） */
export function removeFavorite(dynamicId: number): Promise<void> {
  return request.delete<void>(`/favorites/${dynamicId}`)
}

/** 我的收藏（动态列表，按收藏时间倒序） */
export function listFavorites(): Promise<DynamicItem[]> {
  return request.get<DynamicItem[]>('/favorites')
}

/** 我收藏过的动态ID（列表页「已收藏」状态回显） */
export function listFavoriteIds(): Promise<number[]> {
  return request.get<number[]>('/favorites/ids')
}

// ---------- 会话收藏（私有书签） ----------
// 与上面的「动态收藏」是两回事：动态收藏收藏的是别人公开分享的内容，
// 会话收藏收藏的是自己的对话、不会公开出去。接口刻意分开，避免语义混淆。

/** 收藏一段对话（只写私有书签，不会创建动态） */
export function addConversationFavorite(conversationId: number): Promise<void> {
  return request.post<void>('/favorites/conversations', { conversationId })
}

/** 取消收藏一段对话 */
export function removeConversationFavorite(conversationId: number): Promise<void> {
  return request.delete<void>(`/favorites/conversations/${conversationId}`)
}

/** 我收藏的对话（会话摘要，按收藏时间倒序） */
export function listConversationFavorites(): Promise<Conversation[]> {
  return request.get<Conversation[]>('/favorites/conversations')
}

/** 我收藏的会话ID（对话页「已收藏」状态回显） */
export function listConversationFavoriteIds(): Promise<number[]> {
  return request.get<number[]>('/favorites/conversations/ids')
}
