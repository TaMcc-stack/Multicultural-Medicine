<script setup lang="ts">
/**
 * 知识缺口的来源徽标。
 *
 * 两个来源**不互斥**：同一「民族 + 疾病 + 方面」既可能被高级检索记过、又被用户在
 * 智能对话里反馈过，那时两个徽标一起显示——只显示一个就等于把另一个入口的事实丢掉了。
 *
 * 两个都为空的情形只有一种：留言板反馈被管理员转成缺口（GapOrigin.BOARD）。它既不是
 * 检索未命中、也不是对话里点出来的，所以既不能标成检索也不能标成对话；这里给一个中性徽标，
 * 因为来源栏空着比多一个中性徽标更让人以为「这条没有来源」。
 *
 * 抽成组件而不是在各榜单里各写一遍 v-if：三处列表都要用，判据写三份必然漂移
 * （这个项目已经因为「同一逻辑多份拷贝」吃过亏）。
 */
import type { GapItem } from '@/api/gap'

defineProps<{ gap: GapItem }>()
</script>

<template>
  <span v-if="gap.fromChat" class="src-tag chat" title="用户在智能对话里点过「反馈此问题」">
    🗣️ 智能对话
  </span>
  <span v-if="gap.fromSearch" class="src-tag search" title="高级检索未命中时自动登记的">
    🔍 高级检索
  </span>
  <span v-if="!gap.fromChat && !gap.fromSearch" class="src-tag board" title="留言板反馈被转为知识缺口">
    💬 用户反馈
  </span>
</template>

<style scoped>
.src-tag {
  flex: none;
  font-size: 11px;
  line-height: 1.6;
  border: 1px solid var(--line);
  border-radius: var(--r-full);
  padding: 1px 9px;
  white-space: nowrap;
}
/* 对话来源用陶土色：它是用户主动表达的需求，比自动登记的检索未命中更值得注意 */
.src-tag.chat {
  color: var(--clay-deep);
  background: var(--clay-soft);
  border-color: var(--accent-line);
}
.src-tag.search {
  color: var(--ink-3);
  background: var(--bg);
}
.src-tag.board {
  color: var(--sage-deep);
  background: var(--sage-soft);
  border-color: var(--sage-line);
}
</style>
