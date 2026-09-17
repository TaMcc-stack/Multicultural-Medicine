<script setup lang="ts">
/**
 * 来源等级徽章：图标 + 一级分类名（+ 可选来源机构）。
 *
 * 图标走 `v-html` 内联 SVG 路径——内容是 `utils/sourceLevel.ts` 里的**常量字符串**，
 * 不含任何用户输入，因此没有注入风险。（本项目的 XSS 约定见 utils/markdown.ts：
 * 凡是拼接用户数据的地方都必须先转义；这里是常量，不适用。）
 */
import { computed } from 'vue'
import { sourceMetaOf } from '@/utils/sourceLevel'

const props = withDefaults(
  defineProps<{
    level?: string | null
    org?: string | null
    /** sm = 证据行 / 列表里的小标签；md = 一级分类标题旁 */
    size?: 'sm' | 'md'
    /** 是否附带来源机构名（如「国家卫健委」） */
    showOrg?: boolean
  }>(),
  { size: 'sm', showOrg: false },
)

const meta = computed(() => sourceMetaOf(props.level))
</script>

<template>
  <span
    class="src-badge"
    :class="size"
    :style="{ color: meta.color, background: meta.soft, borderColor: meta.line }"
  >
    <svg
      class="src-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      v-html="meta.icon"
    />
    <span>{{ meta.label }}</span>
    <span v-if="showOrg && org" class="src-org">· {{ org }}</span>
  </span>
</template>

<style scoped>
.src-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  line-height: 1.6;
  border: 1px solid;
  border-radius: var(--r-full);
  padding: 1px 9px;
  white-space: nowrap;
}
.src-badge.md {
  font-size: 12.5px;
  padding: 3px 11px;
  gap: 5px;
}
.src-icon {
  flex: none;
  width: 12px;
  height: 12px;
}
.src-badge.md .src-icon {
  width: 14px;
  height: 14px;
}
.src-org {
  opacity: 0.82;
}
</style>
