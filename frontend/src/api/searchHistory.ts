import request from './request'
import { intentLabel } from './qa'
import type { EvidenceStatus, GenerateAnswer, PoolEvidence } from './qa'

/**
 * 一次高级检索的结果快照——「查看当时的结果」要还原的全部内容。
 *
 * **刻意不等于 evidence-pool 的原始返回**：那里面 `candidates[].fullText` 是整份文档正文
 * （一份专家共识全文就 35k 字），原样存进历史表会让每条记录都变成几十 KB，而侧栏
 * 还原时根本用不到 candidates——结果区只渲染 answer 与 evidence。
 * 所以这里只留真的会被用到的四项。
 *
 * 服务端不解析这份 JSON（`RecordSearchRequest.detail` 就是个 Object），只是原样存档，
 * 因此形状由这里说了算，改字段不需要动后端。
 */
export interface SearchSnapshot {
  /** 结构化答案；未命中时为 null */
  answer: GenerateAnswer | null
  /** 打包好的证据（含来源等级与原文定位信息），供结果区与「点开原文」用 */
  evidence: PoolEvidence[]
  /** 检索说明 / 未命中原因 */
  reason: string
  /** 证据充分度分级：sufficient / partial / none */
  status: EvidenceStatus
}

/**
 * 一条检索历史。
 *
 * 没有 `title` 字段是有意的：侧栏要显示的「白族 + 糖尿病 + 患病情况」由前端用
 * 三个槽位 + `INTENT_LABELS` 拼出（见 {@link searchHistoryTitle}）。服务端拼不了——
 * 意图只存了英文码，而 Java 侧那两张 intent 标签表只有 5 档，不含高级检索新增的 5 档。
 */
export interface SearchHistoryItem {
  id: number
  ethnicity: string
  disease: string
  /** 想了解的方面（意图码）；展示时用 intentLabel() 换中文 */
  intent: string
  /** 结果快照 JSON 字符串（后端与 DynamicDto 一致，返回未解析的原文） */
  detailJson: string | null
  /** 首次检索时间 */
  createdAt: number | string
  /** 最后一次检索时间，列表按它倒序 */
  updatedAt: number | string
}

/**
 * 记录 / 刷新一条检索历史（幂等）。
 *
 * 幂等键是「用户 + 民族 + 疾病 + 方面」，由后端判定新增还是覆盖，前端无从选择——
 * 所以这里没有 id，重复检索同一组合只是刷新时间与快照，不会在侧栏里堆出多条。
 */
export function recordSearch(payload: {
  ethnicity: string
  disease: string
  intent: string
  detail: SearchSnapshot
}): Promise<SearchHistoryItem> {
  return request.post<SearchHistoryItem>('/search-history', payload)
}

/** 我的检索历史（按最后一次检索时间倒序） */
export function listSearchHistory(limit?: number): Promise<SearchHistoryItem[]> {
  return request.get<SearchHistoryItem[]>('/search-history', { params: { limit } })
}

/** 删除一条检索历史 */
export function deleteSearchHistory(id: number): Promise<void> {
  return request.delete<void>(`/search-history/${id}`)
}

// ---------- 检索记录收藏（私有书签） ----------
// 与「收藏动态」(/api/favorites) 是两回事：那个收藏的是公开分享的内容，
// 这个收藏的是自己的一条检索记录，不会公开出去。与「会话收藏」同构。

/** 收藏一条检索记录（幂等，重复收藏不会产生重复记录） */
export function addSearchFavorite(id: number): Promise<void> {
  return request.post<void>(`/search-history/${id}/favorite`)
}

/** 取消收藏（只删收藏关系，不影响检索记录本身） */
export function removeSearchFavorite(id: number): Promise<void> {
  return request.delete<void>(`/search-history/${id}/favorite`)
}

/** 我收藏的检索记录（按收藏时间倒序） */
export function listSearchFavorites(): Promise<SearchHistoryItem[]> {
  return request.get<SearchHistoryItem[]>('/search-history/favorites')
}

/** 我收藏过的检索记录ID（列表页「已收藏」状态回显） */
export function listSearchFavoriteIds(): Promise<number[]> {
  return request.get<number[]>('/search-history/favorites/ids')
}

/** 侧栏展示标题：「白族 + 糖尿病 + 患病情况」 */
export function searchHistoryTitle(h: SearchHistoryItem): string {
  return `${h.ethnicity} + ${h.disease} + ${intentLabel(h.intent)}`
}

/**
 * 取出快照。`detailJson` 损坏或缺失时返回 null——历史行是旧版本写的就可能解析不了，
 * 调用方据此禁用「查看当时的结果」而不是整栏报错。
 */
export function parseSnapshot(h: SearchHistoryItem): SearchSnapshot | null {
  if (!h.detailJson) return null
  try {
    return JSON.parse(h.detailJson) as SearchSnapshot
  } catch {
    return null
  }
}
