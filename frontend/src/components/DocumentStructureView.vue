<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  buildDocumentOutline,
  type DocumentOutline,
  type OutlineNode,
} from '@/utils/documentOutline'
import type { ContentBlock } from '@/utils/contentBlocks'

const props = defineProps<{
  title: string
  blocks: ContentBlock[]
  initialSectionPath?: string[]
  rowKey?: string
  highlightFields?: string[]
  anchorText?: string
  /** 可选的返回按钮文案；不传时保持 KnowledgeView 原来的单关闭按钮。 */
  backLabel?: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const overlayRef = ref<HTMLElement | null>(null)
const previewRef = ref<HTMLElement | null>(null)
const selectedId = ref<string | null>(null)
const tocOpen = ref(false)
let previousBodyOverflow = ''
let initialSelectionApplied = false

const outline = computed<DocumentOutline>(() => buildDocumentOutline(props.blocks))
const selectedNode = computed<OutlineNode | null>(
  () => outline.value.flat.find((node) => node.id === selectedId.value) ?? null,
)
const sectionPathKey = computed(() => (props.initialSectionPath || []).join('\u0000'))

function requestClose() {
  emit('close')
}

function scrollPreviewTop() {
  if (previewRef.value) previewRef.value.scrollTop = 0
}

function selectNode(node: OutlineNode) {
  selectedId.value = node.id
  tocOpen.value = false
  void nextTick(() => {
    scrollPreviewTop()
    clearDeepLinkHighlights()
  })
}

function normalizeMatchText(value: string | null | undefined): string {
  return (value || '')
    .replace(/\s+/g, '')
    // 「1、、心血管…」这类重复标点：后端路径与目录标题两边都归一，避免因标点不一致匹配失败
    .replace(/[、.．]{2,}/g, '、')
    .trim()
}

function titleMatches(title: string | undefined, wanted: string): boolean {
  const normalizedTitle = normalizeMatchText(title)
  const normalizedWanted = normalizeMatchText(wanted)
  if (!normalizedTitle || !normalizedWanted) return false
  return (
    normalizedTitle === normalizedWanted
    || normalizedTitle.includes(normalizedWanted)
    || normalizedWanted.includes(normalizedTitle)
  )
}

function hasMatchingContext(node: OutlineNode, pathBeforeTarget: string[]): boolean {
  if (!pathBeforeTarget.length) return true
  const context = node.context.map(normalizeMatchText)
  let contextIndex = context.length - 1
  let pathIndex = pathBeforeTarget.length - 1
  while (contextIndex >= 0 && pathIndex >= 0) {
    if (!titleMatches(context[contextIndex], pathBeforeTarget[pathIndex]!)) break
    contextIndex--
    pathIndex--
  }
  return pathIndex < 0
}

function findNodeBySectionPath(
  nodes: OutlineNode[],
  sectionPath: string[] | undefined,
): OutlineNode | null {
  const path = (sectionPath || []).map((part) => part.trim()).filter(Boolean)
  if (!path.length) return null

  // 契约要求从最后一级标题开始找；找到同名节点时优先匹配它的上级 context。
  for (let index = path.length - 1; index >= 0; index--) {
    const wanted = path[index]!
    const candidates = nodes.filter((node) => titleMatches(node.title, wanted))
    if (!candidates.length) continue
    return candidates.find((node) => hasMatchingContext(node, path.slice(0, index))) ?? candidates[0]!
  }
  return null
}

function clearDeepLinkHighlights() {
  const root = previewRef.value
  if (!root) return
  root
    .querySelectorAll('.is-target-row, .is-highlight-column, .is-anchor-hit')
    .forEach((element) => {
      element.classList.remove('is-target-row', 'is-highlight-column', 'is-anchor-hit')
    })
}

function fieldMatches(header: string, field: string): boolean {
  return titleMatches(header, field)
}

function applyDeepLinkHighlights() {
  const root = previewRef.value
  if (!root) return
  clearDeepLinkHighlights()

  const rowKey = (props.rowKey || '').trim()
  const highlightFields = (props.highlightFields || []).map((field) => field.trim()).filter(Boolean)
  const fieldIndexesByTable = new Map<HTMLTableElement, number[]>()

  root.querySelectorAll<HTMLTableElement>('.doc-table').forEach((table) => {
    const headers = Array.from(table.querySelectorAll<HTMLTableCellElement>('thead th'))
    const matchedIndexes: number[] = []
    headers.forEach((header, index) => {
      const text = header.textContent || ''
      if (highlightFields.some((field) => fieldMatches(text, field))) matchedIndexes.push(index)
    })
    if (matchedIndexes.length) fieldIndexesByTable.set(table, matchedIndexes)
  })

  fieldIndexesByTable.forEach((indexes, table) => {
    indexes.forEach((index) => {
      const header = table.querySelectorAll<HTMLTableCellElement>('thead th')[index]
      header?.classList.add('is-highlight-column')
      table.querySelectorAll<HTMLTableRowElement>('tbody tr').forEach((row) => {
        row.children[index]?.classList.add('is-highlight-column')
      })
    })
  })

  let target: HTMLElement | null = null
  if (rowKey) {
    const normalizedRowKey = normalizeMatchText(rowKey)
    const rows = Array.from(root.querySelectorAll<HTMLTableRowElement>('tbody tr[data-row-key]'))
    const matchedRow = rows.find(
      (row) => normalizeMatchText(row.dataset.rowKey) === normalizedRowKey,
    )
    if (matchedRow) {
      matchedRow.classList.add('is-target-row')
      target = matchedRow
    }
  }

  const anchorText = (props.anchorText || '').trim()
  if (!target && anchorText) {
    const normalizedAnchor = normalizeMatchText(anchorText)
    const candidates = Array.from(
      root.querySelectorAll<HTMLElement>('.doc-p, .doc-list li, .doc-table th, .doc-table td'),
    )
    target = candidates.find((element) =>
      normalizeMatchText(element.textContent).includes(normalizedAnchor),
    ) ?? null
    target?.classList.add('is-anchor-hit')
  }

  if (target) {
    window.requestAnimationFrame(() => {
      target?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    })
  }
}

watch(
  [outline, sectionPathKey],
  ([next, nextSectionPathKey], previous) => {
    const pathChanged = !!previous && previous[1] !== nextSectionPathKey
    if (!next.flat.length) {
      selectedId.value = null
      return
    }
    if (
      pathChanged
      || !initialSelectionApplied
      || !next.flat.some((node) => node.id === selectedId.value)
    ) {
      selectedId.value = (
        findNodeBySectionPath(next.flat, props.initialSectionPath) ?? next.flat[0] ?? null
      )?.id ?? null
      initialSelectionApplied = true
    }
    void nextTick(() => {
      scrollPreviewTop()
      applyDeepLinkHighlights()
    })
  },
  { immediate: true },
)

onMounted(() => {
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  overlayRef.value?.focus()
})

onBeforeUnmount(() => {
  document.body.style.overflow = previousBodyOverflow
})
</script>

<template>
  <div
    ref="overlayRef"
    class="structure-overlay"
    role="presentation"
    tabindex="-1"
    @keydown.esc="requestClose"
  >
    <div class="structure-backdrop" aria-hidden="true" @click="requestClose"></div>

    <section
      class="structure-panel"
      role="dialog"
      aria-modal="true"
      :aria-label="`资料结构：${title}`"
    >
      <header class="panel-head">
        <div class="panel-head-main">
          <button
            v-if="backLabel"
            class="btn btn-sm btn-outline panel-back"
            type="button"
            @click="requestClose"
          >{{ backLabel }}</button>
          <h2 class="panel-title">《{{ title }}》</h2>
        </div>
        <button
          class="btn btn-icon btn-ghost close-btn"
          type="button"
          aria-label="关闭资料结构"
          @click="requestClose"
        >
          &times;
        </button>
      </header>

      <div class="panel-body">
        <nav class="toc" aria-label="资料目录">
          <div class="toc-heading">资料结构</div>

          <button
            class="toc-toggle"
            type="button"
            :aria-expanded="tocOpen"
            @click="tocOpen = !tocOpen"
          >
            <span class="toc-toggle-label">{{ tocOpen ? '收起目录' : '展开目录' }}</span>
            <span class="toc-current">{{ selectedNode?.title ?? '暂无章节' }}</span>
          </button>

          <div class="toc-scroll" :class="{ 'is-open': tocOpen }">
            <div v-if="outline.flat.length" class="toc-list">
              <button
                v-for="node in outline.flat"
                :key="node.id"
                class="toc-item"
                :class="{
                  'is-level-1': node.level === 1,
                  'is-selected': selectedId === node.id,
                }"
                :style="{
                  paddingLeft: `${10 + Math.min(Math.max(node.level, 1), 3) * 14}px`,
                }"
                type="button"
                :title="node.title"
                :aria-current="selectedId === node.id ? 'location' : undefined"
                @click="selectNode(node)"
              >
                <span class="toc-title">{{ node.title }}</span>
              </button>
            </div>
            <p v-else class="toc-empty">暂无可展示目录</p>
          </div>
        </nav>

        <main ref="previewRef" class="preview">
          <template v-if="selectedNode">
            <div v-if="selectedNode.context.length" class="preview-context" aria-label="当前位置">
              <span
                v-for="(part, index) in selectedNode.context"
                :key="`${index}-${part}`"
                class="context-part"
              >
                {{ part }}
              </span>
            </div>

            <article class="preview-content">
              <!-- 章节只有子节点、没有自身正文（例如按（1）（2）自动拆分后）时的兜底提示 -->
              <p v-if="!selectedNode.blocks.length" class="doc-p empty-hint">
                {{
                  selectedNode.children.length
                    ? `本章节包含 ${selectedNode.children.length} 个子节，请从左侧目录选择查看。`
                    : '本章节暂无正文内容。'
                }}
              </p>
              <template v-for="(block, blockIndex) in selectedNode.blocks" :key="blockIndex">
                <h2 v-if="block.type === 'h' && block.level === 1" class="doc-h1">
                  {{ block.text }}
                </h2>
                <h3 v-else-if="block.type === 'h' && block.level === 2" class="doc-h2">
                  {{ block.text }}
                </h3>
                <h4 v-else-if="block.type === 'h' && block.level === 3" class="doc-h3">
                  {{ block.text }}
                </h4>
                <p v-else-if="block.type === 'p'" class="doc-p">{{ block.text }}</p>
                <div v-else-if="block.type === 'table'" class="doc-table-wrap">
                  <table class="doc-table">
                    <thead v-if="block.rows && block.rows.length">
                      <tr>
                        <th
                          v-for="(cell, cellIndex) in block.rows[0]"
                          :key="cellIndex"
                          :data-col-key="cell"
                          :data-col-index="cellIndex"
                        >
                          {{ cell }}
                        </th>
                      </tr>
                    </thead>
                    <tbody v-if="block.rows && block.rows.length > 1">
                      <tr
                        v-for="(row, rowIndex) in block.rows.slice(1)"
                        :key="rowIndex"
                        :data-row-key="row[0]"
                      >
                        <td
                          v-for="(cell, cellIndex) in row"
                          :key="cellIndex"
                          :data-col-index="cellIndex"
                        >
                          {{ cell }}
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <ol
                  v-else-if="block.type === 'list' && block.ordered"
                  class="doc-list"
                >
                  <li v-for="(item, itemIndex) in block.items || []" :key="itemIndex">
                    {{ item }}
                  </li>
                </ol>
                <ul v-else-if="block.type === 'list'" class="doc-list">
                  <li v-for="(item, itemIndex) in block.items || []" :key="itemIndex">
                    {{ item }}
                  </li>
                </ul>
              </template>
            </article>
          </template>

          <div v-else class="preview-empty">
            <strong>暂无可展示的资料结构</strong>
            <p>当前资料没有可识别的标题或正文块。</p>
          </div>
        </main>
      </div>
    </section>
  </div>
</template>

<style scoped>
.structure-overlay {
  position: fixed;
  inset: 0;
  z-index: 210;
  display: grid;
  place-items: center;
  padding: 24px;
  overflow: hidden;
  isolation: isolate;
}

.structure-backdrop {
  position: absolute;
  inset: 0;
  z-index: 0;
  background: rgba(43, 36, 29, 0.52);
}

.structure-panel {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  width: 100%;
  max-width: 1320px;
  height: calc(100vh - 48px);
  height: calc(100dvh - 48px);
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-lg);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 22px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}

.panel-head-main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.panel-back {
  flex: none;
}

.panel-title {
  min-width: 0;
  overflow: hidden;
  color: var(--ink);
  font-family: var(--serif);
  font-size: 20px;
  font-weight: 700;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.close-btn {
  flex: none;
  font-size: 20px;
}

.panel-body {
  display: grid;
  grid-template-columns: minmax(280px, 340px) minmax(0, 1fr);
  min-height: 0;
  background: var(--surface);
}

.toc {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg);
  border-right: 1px solid var(--line);
}

.toc-heading {
  padding: 16px 18px 10px;
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.toc-toggle {
  display: none;
}

.toc-scroll {
  flex: 1;
  min-height: 0;
  padding: 0 10px 18px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.toc-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.toc-item {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  padding: 8px 10px 8px 12px;
  color: var(--ink-2);
  font-family: var(--sans);
  font-size: 13.5px;
  line-height: 1.5;
  text-align: left;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--r-xs);
  cursor: pointer;
  transition: background-color 0.15s, border-color 0.15s, color 0.15s;
}

.toc-item:hover {
  color: var(--ink);
  background: var(--bg-deep);
}

.toc-item.is-level-1 {
  color: var(--ink);
  font-weight: 700;
}

.toc-item.is-selected {
  color: var(--clay-deep);
  background: var(--clay-soft);
  border-color: var(--clay);
}

.toc-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toc-empty {
  padding: 18px 10px;
  color: var(--ink-3);
  font-size: 13px;
  line-height: 1.8;
}

.preview {
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: var(--bg);
  scrollbar-gutter: stable;
}

.preview-context {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 18px 40px;
  color: var(--ink-3);
  font-size: 13px;
  line-height: 1.6;
  background: rgba(251, 246, 239, 0.96);
  border-bottom: 1px solid var(--line);
  backdrop-filter: blur(8px);
}

.context-part {
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
}

.context-part + .context-part::before {
  color: var(--line-strong);
  content: '/ ';
}

.preview-content {
  max-width: 860px;
  margin: 0 auto;
  padding: 36px 40px 64px;
  color: var(--ink);
  font-family: var(--serif);
  font-size: 16px;
  line-height: 2;
}

.doc-h1,
.doc-h2,
.doc-h3,
.doc-p,
.doc-list {
  overflow-wrap: anywhere;
}

.doc-h1 {
  margin: 26px 0 10px;
  padding-bottom: 6px;
  color: var(--ink);
  font-size: 1.28em;
  font-weight: 700;
  border-bottom: 1px solid var(--line);
}

.doc-h1:first-child {
  margin-top: 0;
}

.doc-h2 {
  margin: 20px 0 8px;
  color: var(--ink);
  font-size: 1.14em;
  font-weight: 700;
}

.doc-h3 {
  margin: 14px 0 6px;
  color: var(--clay-deep);
  font-size: 1.02em;
  font-weight: 600;
}

.doc-p {
  margin: 0 0 14px;
}

.empty-hint {
  color: var(--ink-3);
}

.doc-table-wrap {
  margin: 18px 0;
  overflow-x: auto;
}

.doc-table {
  width: 100%;
  min-width: 560px;
  border-collapse: collapse;
  color: var(--ink);
  font-family: var(--sans);
  font-size: 14px;
  line-height: 1.7;
}

.doc-table th,
.doc-table td {
  padding: 8px 12px;
  text-align: left;
  vertical-align: top;
  border: 1px solid var(--line);
}

.doc-table th {
  color: var(--clay-deep);
  font-weight: 600;
  background: var(--clay-soft);
}

.doc-table tbody tr:nth-child(even) td {
  background: var(--bg);
}

.doc-table td.is-highlight-column {
  background: var(--clay-soft);
}

.doc-table th.is-highlight-column {
  box-shadow: inset 0 -2px 0 var(--clay);
}

.doc-table tbody tr.is-target-row td {
  background: var(--amber-soft);
}

.doc-table tbody tr.is-target-row td:first-child {
  box-shadow: inset 4px 0 0 var(--clay);
}

/* 定位到目标后闪两下，给用户一个「跳到这里了」的视觉反馈。
   动画结束后回到上面的静态高亮，目标不会丢。 */
@keyframes anchor-flash {
  0%, 100% { background-color: var(--amber-soft); }
  50% { background-color: var(--amber-line); }
}

.doc-table td.is-highlight-column,
.doc-table tbody tr.is-target-row td,
.doc-p.is-anchor-hit,
.doc-list li.is-anchor-hit,
.doc-table th.is-anchor-hit,
.doc-table td.is-anchor-hit {
  animation: anchor-flash 0.55s ease-in-out 2;
}

.doc-p.is-anchor-hit,
.doc-list li.is-anchor-hit,
.doc-table th.is-anchor-hit,
.doc-table td.is-anchor-hit {
  background: var(--amber-soft);
  box-shadow: 0 0 0 2px var(--amber-line);
}

.doc-list {
  margin: 0 0 16px;
  padding-left: 22px;
}

.doc-list li {
  margin-bottom: 6px;
  line-height: 1.85;
}

.doc-list li::marker {
  color: var(--clay-deep);
}

.preview-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 48px 24px;
  color: var(--ink-3);
  text-align: center;
}

.preview-empty strong {
  color: var(--ink-2);
  font-size: 15px;
  font-weight: 600;
}

.preview-empty p {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.8;
}

@media (max-width: 899px) {
  .structure-overlay {
    padding: 12px;
  }

  .structure-panel {
    height: calc(100vh - 24px);
    height: calc(100dvh - 24px);
    border-radius: var(--r-md);
  }

  .panel-head {
    padding: 14px 16px;
  }

  .panel-title {
    font-size: 17px;
  }

  .panel-head-main {
    flex-wrap: wrap;
    gap: 6px 10px;
  }

  .panel-body {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
  }

  .toc {
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }

  .toc-heading {
    display: none;
  }

  .toc-toggle {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    min-width: 0;
    padding: 12px 14px;
    color: var(--ink-2);
    font-family: var(--sans);
    font-size: 13px;
    text-align: left;
    background: var(--bg);
    border: 0;
    cursor: pointer;
  }

  .toc-toggle-label {
    flex: none;
    color: var(--clay-deep);
    font-weight: 700;
  }

  .toc-current {
    min-width: 0;
    overflow: hidden;
    color: var(--ink-3);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .toc-scroll {
    display: none;
    flex: none;
    max-height: 36vh;
    padding: 0 10px 12px;
  }

  .toc-scroll.is-open {
    display: block;
  }

  .preview-context {
    padding: 14px 18px;
  }

  .preview-content {
    padding: 26px 18px 48px;
    font-size: 15px;
  }
}

@media (max-width: 520px) {
  .panel-head {
    gap: 10px;
  }

  .doc-table {
    min-width: 500px;
  }
}
</style>
