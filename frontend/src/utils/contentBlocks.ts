/**
 * 知识库文档正文的分块解析：标题（# / ## / ###）、段落、Markdown 表格。
 *
 * <p>从 KnowledgeView 抽出来共用——「文献补录工作台」要预览一份整合文档
 * 在知识库阅读器里的排版效果，两处各自持有一份解析逻辑必然漂移
 * （本项目在锚点词、意图短名上已经吃过这种亏）。**改这里 = 同时改两处**。</p>
 *
 * <p>不做 v-html：块化之后每块都是文本插值，天然免疫 XSS。</p>
 */
export interface ContentBlock {
  type: 'h' | 'p' | 'table' | 'list'
  level?: number
  text?: string
  rows?: string[][]
  /** type === 'list' 时有效：有序列表（`1. `）还是无序列表（`- `） */
  ordered?: boolean
  /** type === 'list' 时的条目 */
  items?: string[]
}

export function parseContentBlocks(text: string): ContentBlock[] {
  const lines = (text || '').split('\n')
  const blocks: ContentBlock[] = []
  let i = 0
  const isTableLine = (s: string) => s.trim().startsWith('|')
  const isSepRow = (cells: string[]) => cells.every((c) => /^:?-+:?$/.test(c.trim()))
  // 列表项：无序用 `- ` / `* `，有序用 `1. ` / `1、`（整合资料的模板两种都会出现）
  const bulletOf = (s: string) => s.match(/^[-*]\s+(.*)$/)
  const orderedOf = (s: string) => s.match(/^\d+[.、]\s+(.*)$/)
  while (i < lines.length) {
    const trimmed = (lines[i] ?? '').trim()
    if (trimmed === '') { i++; continue }
    // 标题行（# / ## / ###）单独成块，绝不并入段落
    const heading = trimmed.match(/^(#{1,3})\s+(.*)$/)
    if (heading) {
      blocks.push({ type: 'h', level: heading[1]!.length, text: heading[2]!.trim() })
      i++
      continue
    }
    // 列表：连续的同类条目归成一个列表块（有序与无序不混块）
    const ordered = !!orderedOf(trimmed)
    if (ordered || bulletOf(trimmed)) {
      const items: string[] = []
      while (i < lines.length) {
        const t = (lines[i] ?? '').trim()
        const m = ordered ? orderedOf(t) : bulletOf(t)
        if (!m) break
        items.push(m[1]!.trim())
        i++
      }
      if (items.length) blocks.push({ type: 'list', ordered, items })
      continue
    }
    // 表格行连续收集成 table
    if (isTableLine(lines[i]!)) {
      const tbl: string[][] = []
      while (i < lines.length && isTableLine(lines[i]!)) {
        const line = lines[i]!.trim()
        const inner = line.replace(/^\|/, '').replace(/\|$/, '')
        const cells = inner.split('|').map((c) => c.trim())
        if (!isSepRow(cells) && !(cells.length === 1 && cells[0] === '')) tbl.push(cells)
        i++
      }
      if (tbl.length) {
        const cols = Math.max(...tbl.map((r) => r.length))
        const norm = tbl.map((r) => { while (r.length < cols) r.push(''); return r })
        blocks.push({ type: 'table', rows: norm })
      }
      continue
    }
    // 其余每个非空行 = 一个段落块（避免多行并成一段导致吞标题/串行）
    blocks.push({ type: 'p', text: (lines[i] ?? '').trim() })
    i++
  }
  return blocks
}
