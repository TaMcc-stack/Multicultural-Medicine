<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { CandidateEvidence, EvidenceStatus } from '@/api/qa'

const props = defineProps<{
  fullText: string
  evidence: CandidateEvidence[]
  status?: EvidenceStatus
  /** 需要定位并高亮的证据下标（后台点「查看原文」时传入） */
  focusIndex?: number | null
}>()

const textRef = ref<HTMLDivElement | null>(null)
const activeIndex = ref<number | null>(null)

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 在全文里定位片段；偏移缺失时回退前缀匹配（片段可能被清洗改写）。 */
function findOffset(fragment: string): [number, number] {
  const text = props.fullText
  if (!text || !fragment) return [-1, -1]
  let idx = text.indexOf(fragment)
  if (idx !== -1) return [idx, idx + fragment.length]
  const prefix = fragment.length >= 20 ? fragment.slice(0, 20) : fragment
  idx = text.indexOf(prefix)
  if (idx !== -1) return [idx, Math.min(idx + fragment.length, text.length)]
  return [-1, -1]
}

/** 全文按「高亮片段」切三段渲染，避免手动改 DOM 与 Vue 的 vdom 打架。 */
const renderedHtml = computed(() => {
  const text = props.fullText || ''
  const i = activeIndex.value
  if (i == null) return escapeHtml(text)
  const ev = props.evidence[i]
  if (!ev) return escapeHtml(text)

  let start = ev.startOffset ?? -1
  let end = ev.endOffset ?? -1
  if (start == null || start < 0 || end == null || end < 0) {
    [start, end] = findOffset(ev.fragment)
  }
  if (start < 0 || end <= start) return escapeHtml(text)

  return escapeHtml(text.slice(0, start))
    + `<span class="dev-highlight">${escapeHtml(text.slice(start, end))}</span>`
    + escapeHtml(text.slice(end))
})

watch(activeIndex, () => {
  nextTick(() => {
    const mark = textRef.value?.querySelector('.dev-highlight')
    if (mark) mark.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
})

// 打开弹窗时直接定位到触发它的那条证据
watch(
  () => props.focusIndex,
  (i) => {
    if (i != null && i >= 0) activeIndex.value = i
  },
  { immediate: true },
)
</script>

<template>
  <div class="dev-root">
    <!-- 证据索引：点哪条，全文就跳到哪条 -->
    <div v-if="evidence.length" class="dev-index">
      <button
        v-for="(ev, i) in evidence"
        :key="i"
        class="dev-chip"
        :class="{ active: activeIndex === i }"
        type="button"
        @click="activeIndex = i"
      >
        <span v-if="ev.topic" class="dev-badge">{{ ev.topic }}</span>
        <span v-if="ev.page" class="dev-page">第 {{ ev.page }} 页</span>
        <span class="dev-chip-text">{{ ev.fragment }}</span>
      </button>
    </div>
    <div v-else class="empty-state empty-state-sm">
      <p class="empty-state-title">没有可直接引用的证据</p>
      <p class="empty-state-desc">这份资料里没有能回答当前问题的片段</p>
    </div>

    <div ref="textRef" class="dev-text" v-html="renderedHtml"></div>
  </div>
</template>

<style scoped>
.dev-root {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.dev-index {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-height: 132px;
  overflow: auto;
}
.dev-chip {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  max-width: 320px;
  text-align: left;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px 12px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color 0.18s, box-shadow 0.18s;
}
.dev-chip:hover { border-color: var(--line-strong); }
.dev-chip.active {
  border-color: var(--accent-line);
  box-shadow: 0 0 0 3px rgba(200, 169, 126, 0.14);
}
.dev-chip-text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.6;
}
.dev-badge {
  font-size: 11px;
  font-weight: 500;
  color: var(--amber);
  background: var(--amber-soft);
  padding: 2px 8px;
  border-radius: 999px;
}
.dev-page { font-size: 11px; color: var(--ink-3); }
.dev-text {
  font-size: 14.5px;
  line-height: 2;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 20px 22px;
  max-height: 58vh;
  overflow: auto;
}

/* 高亮动画：闪两下 */
:deep(.dev-highlight) {
  background: rgba(255, 214, 102, 0.55);
  border-radius: 3px;
  padding: 1px 2px;
  animation: dev-flash 1.1s ease-in-out 2;
}
@keyframes dev-flash {
  0%, 100% { background: rgba(255, 214, 102, 0.55); }
  50% { background: rgba(255, 214, 102, 0); }
}
</style>
