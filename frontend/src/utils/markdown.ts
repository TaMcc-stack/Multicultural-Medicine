/**
 * LLM 返回正文的 Markdown 轻量渲染。
 *
 * 渲染四种语法：**加粗**、管道表格、无序/有序列表、引用块——正好是系统提示词规定回答
 * 会用到的那几种。刻意不引 Markdown 库：本模块「先转义、再插标签」，结构上就免疫 XSS
 * （回答的素材可能来自用户上传的文档，这是条真实的攻击面）；换成 marked 那类默认放行
 * 原始 HTML 的库，必须再配一层 sanitize，配置写错就是漏洞。
 *
 * 抽出来共用——QaView 与动态详情弹窗都要渲染同一批回答，
 * 两处各写一份的话，表格渲染迟早会长歪（项目里 isClear 三份拷贝就吃过这个亏）。
 *
 * **安全约定**：本模块输出的是 HTML 字符串，调用方必须用 `v-html` 注入。
 * 所有文本都经过 `esc` 转义，且 `esc` 一定在插标签**之前**执行——
 * 顺序反过来就会把自己的标签也转义掉，不转义直接插就是 XSS。
 */

export function esc(s: unknown): string {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** Markdown 粗体 → <b>。必须在 esc 之后调用，只处理已转义的文本。 */
export function mdBold(s: string): string {
  return s.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>')
}

function splitMdRow(line: string): string[] {
  const cells = line.split('|')
  const first = cells[0] ?? ''
  const last = cells[cells.length - 1] ?? ''
  const start = first.trim() === '' ? 1 : 0
  const end = last.trim() === '' ? cells.length - 1 : cells.length
  return cells.slice(start, end).map((c) => c.trim())
}

/**
 * 识别一行的列表标记，返回「有序与否」和「剥掉标记后的正文」；不是列表行则返回 null。
 *
 * 容错四档，按可靠度从高到低判定：
 * 1. `1. / 1、` 后跟空白 —— 有序列表的标准写法。
 * 2. `- * • · －` 后跟空白 —— 无序列表的标准写法。
 * 3. `1. / 1、` 后**直接**跟字符 —— 中文输出里常省掉标记后的空格（「1、藏族人群…」）。
 *    限定 1~2 位数：不限定的话，行首的年份枚举（「2023、2024年数据表明…」）会被当成列表项。
 * 4. `- * • · －` 后**直接**跟字符 —— 同上，无序列表省空格（「-藏族人群…」）。
 *    这一档要求下一个字符既不是空白也不是数字：不这么卡的话，行首的负数（「-0.5」）
 *    会被吃成列表项 —— 而正文里的负数在本项目里是真会出现的（标化率、差值）。
 *    也**不把 `—` `–` 当标记**：中文正文用「——」起头是破折号，不是列表。
 *
 * 判别与剥离共用这一个函数，是为了避免「能认出来却剥不干净」——两处各写一条正则，
 * 迟早一条改了一条没改，列表就会带着 `-` 渲染出去。导出也是同一个理由：
 * 卡片预览的平坦化（DynamicView 的 flattenMarkdown）要剥的正是同一批标记。
 */
export function listMarker(line: string): { ordered: boolean; rest: string } | null {
  const t = line.trim()
  let m = /^(\d+)[.、]\s+/.exec(t)
  if (m) return { ordered: true, rest: t.slice(m[0].length) }
  m = /^[-*•·－]\s+/.exec(t)
  if (m) return { ordered: false, rest: t.slice(m[0].length) }
  m = /^\d{1,2}[.、](?=[^\s\d])/.exec(t)
  if (m) return { ordered: true, rest: t.slice(m[0].length) }
  m = /^[-*•·－](?=[^\s\d])/.exec(t)
  if (m) return { ordered: false, rest: t.slice(m[0].length) }
  return null
}

const QUOTE_RE = /^>\s?/

/**
 * 渲染选项。
 *
 * @param focusLead 需要高亮的行首关键词（民族名）。表格里行首含它的行会被标记成
 *                  「您关注的民族」那一行，其余行保持默认样式作对比参考。
 */
export interface MdOptions {
  focusLead?: string
}

/**
 * 渲染表格的正文行（不含表头），并把「用户关注的民族」那几行高亮出来，
 * 需要时在表格**上方**补一句提示语。返回的是可以直接塞进 `<tbody>` 的字符串。
 *
 * **只看行首第一格**。服务端 `_ensure_comparison_table` 也是按 `r[0]` 认对比表的民族
 * （见 `answer.py` 的 `_table_leads`），同一套约定；改成「任意一格命中」会因为来源文献
 * 标题里带民族名而误标——《新疆维吾尔族…研究》那行会被当成维吾尔族的数据。
 *
 * 提示语只在**确实混着多个民族**时给：整张表都是同一个民族时说这话反而让人以为有别的。
 * 抽成函数是因为两处要渲染表格——这里的管道表格，与 `structuredAnswerHtml` 的
 * `format: 'table'` 分支——同一套判定写两份必然漂移。
 */
export function tableBodyHtml(
  rows: string[][],
  colCount: number,
  focusLead?: string,
): string {
  const focus = focusLead?.trim()
  let hit = 0
  const body = rows
    .map((r) => {
      const isFocus = !!focus && (r[0] ?? '').includes(focus)
      if (isFocus) hit++
      // 命中的行在第一格挂一个可见的 🔍 标记。不复用 `title` 做唯一提示——那是悬停才有的，
      // 截图、打印、触屏上都不存在。标记只加在行首格，位置固定，不会串列。
      const lead = isFocus ? '<span class="ans-row-tag">🔍</span>' : ''
      const tds = Array.from(
        { length: colCount },
        (_, k) => `<td>${k === 0 ? lead : ''}${esc(r[k] ?? '')}</td>`,
      ).join('')
      return isFocus
        ? `<tr class="ans-row-focus" title="您关注的民族">${tds}</tr>`
        : `<tr>${tds}</tr>`
    })
    .join('')
  const hint =
    focus && hit > 0 && hit < rows.length
      ? `<div class="ans-table-hint">以下为您关注的 ${esc(focus)} 数据，其他民族数据作为对比参考。</div>`
      : ''
  return hint + body
}

/**
 * 把 Markdown 正文渲染为 HTML。
 *
 * 判定顺序是**引用块 → 列表 → 表格 → 段落**：列表项里带竖线是常见的（「- 白族 | 16.0%」），
 * 先判表格会把整段列表吃掉；反过来则不会误伤。
 */
export function mdToHtml(text: string, options?: MdOptions): string {
  if (!text) return ''
  const lines = text.split('\n')
  let out = ''
  let i = 0
  while (i < lines.length) {
    const line = lines[i] ?? ''
    const next = i + 1 < lines.length ? (lines[i + 1] ?? '') : ''
    const trimmed = line.trim()

    // 引用块：连续的 `>` 行合成一段（提示词里用来放来源说明与提醒语）
    if (QUOTE_RE.test(trimmed)) {
      const parts: string[] = []
      while (i < lines.length && QUOTE_RE.test((lines[i] ?? '').trim())) {
        parts.push((lines[i] ?? '').trim().replace(QUOTE_RE, ''))
        i++
      }
      out += `<blockquote class="ans-quote">${mdBold(esc(parts.join(' ')))}</blockquote>`
      continue
    }

    // 列表：连续的同类条目归成一个列表块，有序与无序不混块
    const first = listMarker(trimmed)
    if (first) {
      const ordered = first.ordered
      const items: string[] = []
      while (i < lines.length) {
        const mk = listMarker(lines[i] ?? '')
        if (mk && mk.ordered === ordered) {
          items.push(mk.rest)
          i++
          continue
        }
        // 换了另一种列表 → 列表到此为止
        if (mk) break
        // 普通正文行 → 列表到此为止
        if ((lines[i] ?? '').trim()) break
        // 空行：提示词要求「条目之间空行分隔」，所以空行夹在列表项中间是**正常输出**。
        // 只有后面还跟着同类条目时才跳过它，否则列表到此为止（末尾的空行不该被吞掉）。
        // 少了这一步，`- a\n\n- b\n\n- c` 会被切成三个单条目的 <ul>——
        // 每段各带一次上下边距，条目间距忽大忽小，看起来就像排版坏了。
        let j = i
        while (j < lines.length && !(lines[j] ?? '').trim()) j++
        const nk = j < lines.length ? listMarker(lines[j] ?? '') : null
        if (!nk || nk.ordered !== ordered) break
        i = j
      }
      const inner = items.map((it) => `<li>${mdBold(esc(it))}</li>`).join('')
      out += ordered ? `<ol class="ans-list">${inner}</ol>` : `<ul class="ans-list">${inner}</ul>`
      continue
    }

    // Markdown 表格块：当前行含 | 且下一行是分隔行（仅由 | - : 空白组成）
    if (line.includes('|') && next.includes('-') && /^[\s|:\-]+$/.test(next)) {
      const firstBar = line.indexOf('|')
      const pre = line.slice(0, firstBar).trim()
      if (pre) out += `<div>${esc(pre)}</div>`
      const header = splitMdRow(line.slice(firstBar))
      const rows: string[][] = []
      i += 2 // 跳过表头与分隔行
      while (i < lines.length && (lines[i] ?? '').includes('|')) {
        rows.push(splitMdRow(lines[i] ?? ''))
        i++
      }
      const colCount = Math.max(header.length, ...rows.map((r) => r.length))
      out += '<table class="ans-table"><thead><tr>'
        + header.map((c) => `<th>${esc(c)}</th>`).join('')
        + '</tr></thead><tbody>'
        + tableBodyHtml(rows, colCount, options?.focusLead)
        + '</tbody></table>'
      continue
    }
    if (line.trim()) out += `<div>${mdBold(esc(line))}</div>`
    i++
  }
  return out
}
