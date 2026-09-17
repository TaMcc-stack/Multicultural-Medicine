import type { GenerateAnswer } from '@/api/qa'
import { esc, mdToHtml, tableBodyHtml } from '@/utils/markdown'

/**
 * 把结构化回答渲染成 HTML（结论 / 文献数据说明 / 专家行动建议 / 适用边界 / 表格 / 图表）。
 *
 * 抽到 util 是因为**对话页与高级检索页都要用**——两份拷贝一定会长歪，
 * 而这段决定了答案长什么样，歪了就是两个页面同一份数据看起来不一样。
 * 纯函数：只依赖入参，不碰组件状态。
 *
 * @param focusLead 用户所问的民族。带上它，正文与表格里该民族的那几行会高亮，
 *                  其余民族保持原样作对比参考；不传则一切照旧（对话页泛问、后台回放）。
 */
export function structuredAnswerHtml(a: GenerateAnswer, focusLead?: string): string {
  const isNew = !!a.detailed || !!a.timeRegion || !!a.followUps
  let h = ''
  // 结论也要走 Markdown 渲染：提示词对「危险因素 / 症状 / 预防 / 饮食 / 遗传」这几个方面
  // 明确要求用无序列表作答，模型照做之后列表就落在 conclusion 里。
  // 之前这里只做 esc，于是「- 」和行尾那两个空格（Markdown 硬换行）被原样显示出来。
  if (a.conclusion) h += `<div class="answer-text">${mdToHtml(a.conclusion)}</div>`
  if (a.detailed) {
    h += `<div class="ans-block"><div class="ans-sec-title">文献数据说明</div><div class="ans-sec-body">${mdToHtml(a.detailed, { focusLead })}</div></div>`
  }
  if (a.actions) {
    h += `<div class="ans-block"><div class="ans-sec-title">专家行动建议</div><div class="ans-sec-body">${mdToHtml(a.actions, { focusLead })}</div></div>`
  }
  for (const s of a.sections || []) {
    const title = s.title ? `<div class="ans-sec-title">${esc(s.title)}</div>` : ''
    // 旧结构的分节正文同样可能是列表（历史数据回放会走到这里）
    h += `<div class="ans-sec">${title}<div class="ans-sec-body">${mdToHtml(s.content)}</div></div>`
  }
  if (a.sources || a.scope) {
    // 新「三件套」：证据来源 / 适用范围 / 使用边界 三张独立小卡片。
    // 适用范围或使用边界缺失时，不静默省略——写清「未明确说明」，这正是业务要的边界感。
    const cards: string[] = []
    if (a.sources && a.sources !== '—') {
      cards.push(`<div class="ans-card"><div class="ans-card-title">📚 证据来源</div><div class="ans-card-body">${mdToHtml(a.sources)}</div></div>`)
    }
    const scopeBody = a.scope && a.scope !== '—' ? a.scope : '⚠️ 该文献未明确说明适用范围，请谨慎参考'
    cards.push(`<div class="ans-card"><div class="ans-card-title">🎯 适用范围</div><div class="ans-card-body">${mdToHtml(scopeBody)}</div></div>`)
    const boundaryBody = a.cautions && a.cautions !== '—' ? a.cautions : '该文献未明确说明使用边界，建议结合个人实际情况咨询专业医生。'
    cards.push(`<div class="ans-card"><div class="ans-card-title">⚠️ 使用边界</div><div class="ans-card-body">${mdToHtml(boundaryBody)}</div></div>`)
    h += `<div class="ans-trio">${cards.join('')}</div>`
  } else if (isNew) {
    // 旧结构把适用边界拆成「适用人群 / 研究时间/地区 / 局限性与注意」三个字段；
    // 新结构是「文献来源与专家提醒」，含适用人群 / 研究时间地区 / 特别提醒。两种都要能渲染。
    const rows: string[] = []
    if (a.applicable && a.applicable !== '—') rows.push(`<div class="ans-scope-row"><b>适用人群</b>：${esc(a.applicable)}</div>`)
    if (a.timeRegion && !a.timeRegion.includes('未明确')) rows.push(`<div class="ans-scope-row"><b>研究时间/地区</b>：${esc(a.timeRegion)}</div>`)
    const remind = a.cautions && a.cautions !== '—' ? mdToHtml(a.cautions) : ''
    const body = rows.join('') + remind
    if (body) h += `<div class="ans-block"><div class="ans-sec-title">文献来源与专家提醒</div><div class="ans-sec-body">${body}</div></div>`
  } else {
    if (a.applicable) h += `<div class="ans-note">适用人群：${esc(a.applicable)}</div>`
    if (a.cautions) h += `<div class="ans-note warn">注意：${esc(a.cautions)}</div>`
  }
  if (a.format === 'table' && a.table) {
    const cols = a.table.columns || []
    const rows = a.table.rows || []
    const th = cols.map((c) => `<th>${esc(c)}</th>`).join('')
    const colCount = Math.max(cols.length, ...rows.map((r) => r.length))
    h += `<table class="lit-table ans-table"><thead><tr>${th}</tr></thead><tbody>${tableBodyHtml(rows, colCount, focusLead)}</tbody></table>`
  }
  if (a.format === 'chart' && a.chart && a.chart.data && a.chart.data.length) {
    const data = a.chart.data
    const max = Math.max(...data.map((d) => d.value || 0), 1)
    h += `<div class="ans-chart"><div class="ans-chart-title">${esc(a.chart.title || '')}</div>`
    for (const d of data) {
      const pct = Math.round((d.value / max) * 100)
      h += `<div class="ans-bar-row"><span class="ans-bar-label">${esc(d.label)}</span>`
      h += `<div class="ans-bar-track"><div class="ans-bar-fill" style="width:${pct}%"></div></div>`
      h += `<span class="ans-bar-val">${esc(String(d.value))}${esc(a.chart.unit || '')}</span></div>`
    }
    h += `</div>`
  }
  return h
}
