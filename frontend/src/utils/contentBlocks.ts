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

/**
 * 空格对齐的「伪表格」还原。
 *
 * <p>人工整理的 docx 里，一部分表格没做成 Word 表格、也没写 Markdown 管道符，
 * 而是靠多个空格对齐（例：`民族  高血压患病率` 换行 `傣族  27.8%`）。
 * 直接渲染就是「标题 + 一段段文字」，看起来像排乱了。这里只在**列数完全对得上**
 * 时还原成表格，宁可不认也不猜——表头列数与数据行列数不一致时（比如表头把
 * 两列写成了一个词）交回原样，由人工修源文件。</p>
 */
const SPACE_TABLE_HEADER_MAX_LEN = 24
const SPACE_TABLE_MAX_BLANK_GAP = 2

function splitByColumnGap(line: string): string[] {
  const t = (line || '').trim()
  if (!t || t.includes('|')) return []
  // 列表项即使内部有多个空格也还是列表，不能被当成空格对齐表格的表头/数据行
  if (/^([-*+]\s+|\d+\s*[.、]\s+)/.test(t)) return []
  const cells = t.split(/\s{2,}/).map((c) => c.trim()).filter(Boolean)
  return cells.length >= 2 ? cells : []
}

function isShortHeaderCell(cell: string): boolean {
  return !!cell && cell.length <= SPACE_TABLE_HEADER_MAX_LEN && !/[。！？；，、：]/.test(cell)
}

interface SpaceAlignedTable {
  header: string[]
  rows: string[][]
  next: number
}

/** 从 lines[start] 起匹配一张空格对齐的表；匹配不上返回 null，不消耗任何行 */
function matchSpaceAlignedTable(lines: string[], start: number): SpaceAlignedTable | null {
  const raw = (lines[start] ?? '').trim()
  if (!raw) return null
  const headerText = raw.replace(/^#{1,3}\s+/, '').trim()
  const header = splitByColumnGap(headerText)
  if (header.length < 2 || !header.every(isShortHeaderCell)) return null

  const rows: string[][] = []
  let i = start + 1
  let blankGap = 0
  while (i < lines.length && i <= start + 24) {
    const t = (lines[i] ?? '').trim()
    if (t === '') {
      blankGap++
      i++
      if (blankGap > SPACE_TABLE_MAX_BLANK_GAP) break
      continue
    }
    const cells = splitByColumnGap(t)
    if (cells.length !== header.length || !cells.some((c) => /\d/.test(c))) break
    rows.push(cells)
    blankGap = 0
    i++
  }
  return rows.length >= 2 ? { header, rows, next: i } : null
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
    // 空格对齐的伪表格（丢了管道符）优先还原：命中就整段变 table 块
    const spaceTable = matchSpaceAlignedTable(lines, i)
    if (spaceTable) {
      blocks.push({ type: 'table', rows: [spaceTable.header, ...spaceTable.rows] })
      i = spaceTable.next
      continue
    }
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
