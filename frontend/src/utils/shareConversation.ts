/**
 * 把一段对话打包成动态载荷。
 *
 * 两个地方都要做同一件事：
 *   - 对话页右上角「分享至动态」——数据在内存里（刚生成完，可能还没落库）
 *   - 个人中心「我的分享」按钮——数据在服务端，要先拉会话详情
 * 打包规则（标题取第一个问题、正文用纯文本兜底、detail 存 {version:2, turns}）
 * 必须一致，否则两处分享出来的动态在详情页会长得不一样。集中在这里一份。
 */
import { createDynamic } from '@/api/dynamic'
import type { DynamicItem } from '@/api/dynamic'
import { getConversation } from '@/api/chat'
import type { ChatMessage } from '@/api/chat'

/** 动态里存的一条依据文献 */
export interface ShareCitation {
  title: string
  quote: string
  /** 文档 id 与偏移量：详情页据此「点开原文并定位」 */
  id?: string
  startOffset?: number
  endOffset?: number
  matchedTerms?: string[]
  /** 来源等级（一级分类）：详情页据此显示来源标签；缺省视为官方 */
  sourceLevel?: string
  /** 来源机构：国家卫健委 / 人民日报 / 用户上传 … */
  sourceOrg?: string | null
}

/** 动态里存的一轮问答 */
export interface ShareTurn {
  question: string
  answer: Record<string, any> | null
  /** 纯文本兜底：detail 解析失败时详情页仍能显示内容 */
  plain: string
  citations: ShareCitation[]
}

/** 把后端返回的 citations 归一成 ShareCitation（兼容 v1 的 paper 嵌套写法） */
export function normalizeCitations(raw: any[] | undefined | null): ShareCitation[] {
  return (raw || []).map((c: any) => ({
    title: c?.title || c?.paper?.title || '',
    quote: c?.quote || '',
    id: c?.id,
    startOffset: c?.startOffset,
    endOffset: c?.endOffset,
    matchedTerms: c?.matchedTerms,
    sourceLevel: c?.sourceLevel,
    sourceOrg: c?.sourceOrg ?? null,
  }))
}

/**
 * 从服务端会话消息组装 turns：每个 user 消息 + 紧随其后的 assistant 消息算一轮。
 */
export function turnsFromMessages(messages: ChatMessage[] | undefined | null): ShareTurn[] {
  const out: ShareTurn[] = []
  let question = ''
  for (const m of messages || []) {
    if (m.role === 'user') {
      question = m.content || ''
      continue
    }
    if (m.role !== 'assistant') continue
    const d = (m.detail || {}) as Record<string, any>
    out.push({
      question,
      answer: d.answer || null,
      plain: m.content || '',
      citations: normalizeCitations(d.citations),
    })
    question = ''
  }
  return out
}

/**
 * 打包成 createDynamic 需要的三大件
 */
export function packDynamic(turns: ShareTurn[], title?: string) {
  return {
    // 标题优先用调用方给的（用户确认过的），其次取第一个问题
    title: (title || '').trim() || turns[0]?.question || '对话分享',
    // 正文纯文本兜底：详情页 detail 解析失败时仍能显示内容
    content: turns.map((t) => `${t.question}\n\n${t.plain || ''}`.trim()).join('\n\n———\n\n'),
    detail: { version: 2, turns },
  }
}

/**
 * 打包一张「一问一答」卡片：与 packDynamic **同形状**，只是 turns 里只有一轮。
 *
 * 刻意复用同一种载荷，是为了让 `DynamicDetailModal` 零改动就能渲染问答卡——
 * 它本来就按 turns 数组渲染，长度为 1 天然兼容。形状一旦分叉，详情弹窗就得写两套。
 */
export function packQaCard(turn: ShareTurn, title?: string) {
  return {
    title: (title || '').trim() || turn.question || '一问一答',
    content: `${turn.question}\n\n${turn.plain || ''}`.trim(),
    detail: { version: 2, turns: [turn] },
  }
}

/**
 * 直接发布一个已存在的会话（个人中心用）。
 * 后端按 (用户, 会话) 幂等，重复发布是**更新**已有动态而不是新增。
 */
export async function publishConversationById(
  conversationId: number,
  title?: string,
): Promise<DynamicItem> {
  const detail = await getConversation(conversationId)
  const turns = turnsFromMessages(detail.messages)
  if (!turns.length) {
    throw new Error('这段对话还没有可分享的内容')
  }
  const packed = packDynamic(turns, title || detail.conversation.title)
  return createDynamic({
    conversationId,
    // 整段对话没有单条消息来源，后端改用会话作为幂等键
    messageId: null,
    ...packed,
  })
}
