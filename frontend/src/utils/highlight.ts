/**
 * 证据片段里的「命中词高亮」。
 *
 * 后台的知识检索区、前台对话页的「依据文献」都要把「这条证据为什么被检索到」
 * 显示出来——把这几个命中的关键词标出来即可。抽成一份，避免两处各写一遍
 * 后行为不一致（项目里 isClear 三份拷贝就吃过这个亏）。
 */

/** 构建 <mark> 标签前必须先转义，否则片段里的 HTML 会直接执行 */
function escapeForRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 把命中的关键词包进 `<mark>`。
 *
 * **入参 text 必须已经做过 HTML 转义**——本函数只负责插标签，不做转义。
 * 顺序反了（先插标签再转义）会把自己的标签也转义掉；先转义再插才算安全。
 *
 * 关键词本身也做一次 HTML 转义再匹配：转义后的正文里 `&` 会变成 `&amp;`，
 * 拿未转义的词去匹配就对不上了。
 */
export function highlightTerms(text: string, terms?: string[] | null): string {
  if (!text || !terms || !terms.length) return text

  // 长词优先：单趟替换里，「糖尿病」要抢在「糖尿」前面吃掉这 3 个字，
  // 否则会切成 <mark>糖尿</mark>病。
  const uniq = [...new Set(terms.filter(Boolean))]
    .map((t) => escapeForRegex(escapeHtml(t)))
    .sort((a, b) => b.length - a.length)
  if (!uniq.length) return text

  try {
    // 单趟全局替换：已插入的 <mark> 标签不会再被后续匹配切开
    return text.replace(new RegExp(uniq.join('|'), 'g'), (m) => `<mark>${m}</mark>`)
  } catch {
    return text
  }
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
