/**
 * 列表按时间分组的共享逻辑（对话侧栏 / 高级检索侧栏 / 将来任何时间线列表）。
 *
 * 抽出来的原因：这段逻辑本来只长在对话侧栏里，高级检索的历史栏要「和智能对话的
 * 历史记录样式保持一致」，照抄就是第二份。本项目已经因为「同一逻辑多份拷贝」吃过亏
 * （isClear 三份、extractGeneratedKey 三份），纯函数搬迁的代价又极低，所以直接共用。
 */

/**
 * 把后端时间戳（ISO 字符串或毫秒数）统一成 Date；非法值返回 null。
 *
 * 兼容带空格的写法（`2026-09-13 12:34:56`）：Chrome 能宽容解析，但这是引擎的额外开恩，
 * 不是规范——显式把空格换成 `T` 才不依赖实现。顺带也让 Safari 这类更严格的引擎能跑。
 */
export function toDate(ts: number | string | null | undefined): Date | null {
  if (ts == null || ts === '') return null
  const normalized = typeof ts === 'number' ? ts : ts.replace(' ', 'T')
  const d = new Date(normalized)
  return Number.isNaN(d.getTime()) ? null : d
}

/** 某时间戳当天 00:00 的毫秒值（用于按「自然日」而非 24 小时窗口算天数差） */
export function startOfDay(ts: number | string | null | undefined): number {
  const d = toDate(ts) ?? new Date()
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

/**
 * 时间线分组标签（参考主流 IM 的列表逻辑）。
 *
 * 按**自然日**算差值，不是按 24 小时窗口——否则「今天凌晨 1 点问的」在当天
 * 下午 2 点就会被算成「昨天」。
 */
export function groupLabelOf(ts: number | string | null | undefined): string {
  const d = toDate(ts)
  if (!d) return '更早'
  const diffDays = Math.round((startOfDay(Date.now()) - startOfDay(ts)) / 86400000)
  if (diffDays <= 0) return '今天'
  if (diffDays === 1) return '昨天'
  if (diffDays <= 7) return '7天内'
  if (diffDays <= 30) return '30天内'
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

export interface TimeGroup<T> {
  label: string
  items: T[]
}

/**
 * 把列表切成 [分组标题, 该组条目] 的形式。
 *
 * 要求入参**已按时间倒序**——同一分组才是连续的，顺序扫一遍即可；乱序输入会切出
 * 重复的分组标题（而不是报错），所以排序是调用方的责任。
 */
export function groupByTime<T>(items: T[], timeOf: (item: T) => number | string | null | undefined): TimeGroup<T>[] {
  const out: TimeGroup<T>[] = []
  for (const item of items) {
    const label = groupLabelOf(timeOf(item))
    const last = out[out.length - 1]
    if (last && last.label === label) last.items.push(item)
    else out.push({ label, items: [item] })
  }
  return out
}
