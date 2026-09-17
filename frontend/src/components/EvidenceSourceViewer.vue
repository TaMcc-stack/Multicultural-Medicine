<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DocEvidenceViewer from '@/components/DocEvidenceViewer.vue'
import { fetchDocFullText } from '@/api/qa'
import type { CandidateEvidence } from '@/api/qa'
import { getKbDoc, kbPageImageUrl } from '@/api/kb'
import type { KbPartition } from '@/api/kb'

/**
 * 溯源查看器：按知识库分区决定「展示什么」。
 *
 * - **原始文献库（raw）**：展示 PDF 原页图，并定位到证据所在的那一页。
 *   原文的版式、图表、矢量图都在图里，比提取出来的纯文本更可靠；
 *   而且用户核对数据时，看的本来就是论文里那张表。
 * - **整合资料库（integrated）**：展示文档正文并高亮命中的片段——
 *   这类资料本身就是为检索提炼的，看片段就够，没必要翻整篇。
 *
 * 抽成组件而不是在两处各写一遍：问答页与后台分析工作台的溯源弹窗是同一件事，
 * 抄两份迟早只改一处（本项目已经因为重复吃过多次亏）。
 */
const props = defineProps<{
  /** 知识库文档 id（证据条目上的 `id`） */
  docId: string
  title: string
  /** 分区；缺省按整合资料处理（老数据没有这一项） */
  partition?: KbPartition
  /** 证据所在页码，PDF 定位用 */
  page?: number | null
  /** 同一份文献的命中片段，交给文本视图做定位与高亮 */
  evidence: CandidateEvidence[]
  focusIndex?: number | null
  /**
   * 已知的正文。常规模式下证据池已经把全文带在候选资料里了，传进来就省一次请求；
   * 追溯模式（落库快照）没有全文，留空由组件自己按 doc id 取。
   */
  preloadedText?: string
}>()

const loading = ref(true)
const error = ref('')
const fullText = ref('')
/** PDF 总页数；0 = 不是 PDF 或拿不到页数，此时退回文本视图 */
const pageCount = ref(0)
const currentPage = ref(1)

const isPdf = computed(() => props.partition === 'raw' && pageCount.value > 0)

function pageSrc(n: number): string {
  return kbPageImageUrl(props.docId, n)
}

/** 翻页钳在 [1, pageCount] 内，避免越界请求 */
function gotoPage(n: number) {
  currentPage.value = Math.min(Math.max(1, n), Math.max(1, pageCount.value))
}

async function load() {
  if (!props.docId) {
    error.value = '这条证据没有可定位的原文'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  fullText.value = ''
  pageCount.value = 0
  currentPage.value = props.page && props.page > 0 ? props.page : 1
  try {
    if (props.partition === 'raw') {
      // 原始文献要的是「翻到第几页」，所以先问页数；拿不到页数就退回文本视图
      const doc = await getKbDoc(props.docId)
      pageCount.value = doc.pageCount || 0
    }
    if (pageCount.value === 0) {
      // 正文已在手（常规模式下候选资料自带全文）就不必再请求一次
      if (props.preloadedText) {
        fullText.value = props.preloadedText
      } else {
        const d = await fetchDocFullText(props.docId)
        fullText.value = d.full_text || ''
      }
    }
  } catch {
    error.value = '加载原文失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

// 只在**换了一份文献**时重新拉；同文档内切换片段（focusIndex 变）不该重新请求
watch(() => props.docId, load, { immediate: true })
</script>

<template>
  <div class="esv">
    <div v-if="loading" class="esv-hint">正在加载原文…</div>
    <div v-else-if="error" class="esv-hint">{{ error }}</div>

    <!-- 原始文献：PDF 原页图 + 翻页定位 -->
    <template v-else-if="isPdf">
      <div class="esv-bar">
        <span class="esv-tag">原始文献 · PDF 原页</span>
        <div class="esv-pager">
          <button class="esv-btn" type="button" :disabled="currentPage <= 1" @click="gotoPage(currentPage - 1)">‹ 上一页</button>
          <span class="esv-page-no">第 {{ currentPage }} / {{ pageCount }} 页</span>
          <button class="esv-btn" type="button" :disabled="currentPage >= pageCount" @click="gotoPage(currentPage + 1)">下一页 ›</button>
        </div>
      </div>
      <div class="esv-img-wrap">
        <img :src="pageSrc(currentPage)" :alt="`第 ${currentPage} 页`" />
      </div>
      <p class="esv-note">
        《{{ title }}》为原始论文，此处展示 PDF 原页；数据以原文版式为准。
      </p>
    </template>

    <!-- 整合资料：正文 + 命中片段高亮（与原先的行为一致） -->
    <DocEvidenceViewer
      v-else
      :full-text="fullText"
      :evidence="evidence"
      :focus-index="focusIndex ?? null"
    />
  </div>
</template>

<style scoped>
.esv { min-height: 120px; }
.esv-hint { padding: 28px 4px; font-size: 13.5px; color: var(--ink-3); }

.esv-bar {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; flex-wrap: wrap; margin-bottom: 10px;
}
.esv-tag {
  font-size: 12px; padding: 3px 11px; border-radius: 999px;
  color: var(--amber); background: var(--amber-soft); border: 1px solid var(--amber-line);
}
.esv-pager { display: flex; align-items: center; gap: 10px; }
.esv-btn {
  border: 1px solid var(--line); background: var(--surface); cursor: pointer;
  font-family: var(--sans); font-size: 12.5px; color: var(--ink-2);
  padding: 4px 10px; border-radius: var(--r-sm); transition: 0.15s;
}
.esv-btn:hover:not(:disabled) { border-color: var(--clay); color: var(--clay-deep); }
.esv-btn:disabled { opacity: 0.45; cursor: default; }
.esv-page-no { font-size: 12.5px; color: var(--ink-3); font-variant-numeric: tabular-nums; }

/* 原页图按容器宽度撑满，长页面自然纵向滚动（弹窗自己滚） */
.esv-img-wrap { background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 8px; }
.esv-img-wrap img { width: 100%; display: block; border-radius: var(--r-xs); }
.esv-note { font-size: 12px; color: var(--ink-3); margin-top: 10px; line-height: 1.8; }
</style>
