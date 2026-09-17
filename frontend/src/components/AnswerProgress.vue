<script setup lang="ts">
/**
 * 等待回答期间的分步进度提示。
 *
 * 智能对话（QaView）与高级检索（AdvancedSearchView）**共用这一个组件**——
 * 需求明确要求两个入口样式一致，而这个项目已经吃过「同一份逻辑抄几处然后漂移」的亏
 * （BackendView 里那份抄走的 structuredAnswerHtml 就是活证据）。所以三步文案也写死在
 * 这里，不开放覆盖 prop：一旦允许某一边传自己的文案，两边就会开始分叉。
 *
 * **进度由真实事件驱动，不是定时器**：调用方在真实的 await 边界上改 `step`
 * （理解 = /understand 返回、检索 = /evidence-pool 返回、组织 = /generate 发出）。
 * 之前这里是 2 秒一跳的 setInterval，会说谎——实测「检索」那步有时要 8 秒，
 * 定时器第 3 秒就跳到「组织回答」了。
 */
import { computed, onUnmounted, ref, watch } from 'vue'

/** 三步文案。图标 + 文字一起改，别只改一边。 */
const STEPS = [
  { icon: '🔍', text: '正在理解您的问题…' },
  { icon: '📚', text: '正在检索知识库…' },
  { icon: '✍️', text: '正在组织通俗回答…' },
] as const

/** 最后一步超过这个时长就补一句安抚文案（大模型偶尔会慢） */
const SLOW_HINT_MS = 10_000

const props = defineProps<{
  /** 当前进行到第几步：0=理解 1=检索 2=组织。已完成步骤显示灰色打勾 */
  step: number
  /** 失败态：整块替换为失败提示，不再显示步骤 */
  failed?: boolean
}>()

const stepIndex = computed(() => Math.max(0, Math.min(props.step, STEPS.length - 1)))

/** index 相对当前进度的位置，决定三种行态 */
function stateOf(i: number): 'done' | 'active' | 'todo' {
  if (i < stepIndex.value) return 'done'
  if (i === stepIndex.value) return 'active'
  return 'todo'
}

// ── 「正在处理复杂数据」提示 ──────────────────────────────────────────────
// 只在最后一步计时：前两步本来就快，给它们加提示反而制造焦虑。
const showSlowHint = ref(false)
let slowTimer: ReturnType<typeof setTimeout> | null = null

function clearSlowTimer() {
  if (slowTimer) {
    clearTimeout(slowTimer)
    slowTimer = null
  }
}

watch(
  () => [stepIndex.value, props.failed] as const,
  () => {
    clearSlowTimer()
    showSlowHint.value = false
    if (props.failed) return
    if (stepIndex.value < STEPS.length - 1) return
    slowTimer = setTimeout(() => {
      showSlowHint.value = true
    }, SLOW_HINT_MS)
  },
  { immediate: true },
)

// 组件卸载时必须清掉，否则回答到达、进度块消失后定时器还在跑
onUnmounted(clearSlowTimer)
</script>

<template>
  <div class="ans-progress" role="status" aria-live="polite">
    <!-- 失败态：整块替换 -->
    <div v-if="failed" class="ap-failed">
      <span class="ap-failed-icon" aria-hidden="true">❌</span>
      <div class="ap-failed-body">
        <div class="ap-failed-title">生成失败，请重试</div>
        <div class="ap-failed-hint">可以换个问法，或点击上方的追问继续。</div>
      </div>
    </div>

    <!-- 三步进度 -->
    <template v-else>
      <ol class="ap-steps">
        <li v-for="(s, i) in STEPS" :key="s.text" class="ap-step" :class="`is-${stateOf(i)}`">
          <span class="ap-mark" aria-hidden="true">
            <template v-if="stateOf(i) === 'done'">✓</template>
            <template v-else>{{ s.icon }}</template>
          </span>
          <span class="ap-text" :class="{ 'shimmer-text': stateOf(i) === 'active' }">
            {{ s.text }}
          </span>
          <span v-if="stateOf(i) === 'active'" class="ap-dots" aria-hidden="true">
            <i></i><i></i><i></i>
          </span>
        </li>
      </ol>

      <p v-if="showSlowHint" class="ap-slow">正在处理复杂数据，请稍候…</p>
    </template>
  </div>
</template>

<style scoped>
.ans-progress {
  font-family: var(--sans);
  font-size: 13.5px;
  line-height: 1.7;
  padding: 0 4px 4px;
}

/* ── 三步列表 ───────────────────────────────────────────── */
.ap-steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ap-step {
  display: flex;
  align-items: center;
  gap: 8px;
  transition: color 0.2s ease, opacity 0.2s ease;
}
/* 图标占位固定宽度：打勾和 emoji 宽度不同，不固定会左右抖动 */
.ap-mark {
  flex: none;
  width: 18px;
  text-align: center;
  font-size: 13px;
}

/* 已完成：灰色打勾，文字退到次要色 */
.ap-step.is-done { color: var(--ink-3); }
.ap-step.is-done .ap-mark { color: var(--sage); font-weight: 700; }

/* 进行中：主色 + 微光（.shimmer-text 是全局类，已自带 animation） */
.ap-step.is-active { color: var(--clay); font-weight: 500; }

/* 未开始：压暗，不抢注意力 */
.ap-step.is-todo { color: var(--ink-3); opacity: 0.5; }

.ap-text { white-space: nowrap; }

/* 三点跳动：延迟错开做出依次抬起的节奏（typing-bounce 是全局 keyframes） */
.ap-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: none;
  margin-left: 2px;
}
.ap-dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--clay);
  animation: typing-bounce 1.2s infinite;
}
.ap-dots i:nth-child(2) { animation-delay: 0.15s; }
.ap-dots i:nth-child(3) { animation-delay: 0.3s; }

/* ── 慢速提示 ───────────────────────────────────────────── */
.ap-slow {
  margin: 4px 0 0 26px;
  color: var(--amber);
  font-size: 12.5px;
}

/* ── 失败态 ─────────────────────────────────────────────── */
.ap-failed {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: var(--danger);
}
.ap-failed-icon { flex: none; font-size: 13px; line-height: 1.7; }
.ap-failed-title { font-weight: 600; }
.ap-failed-hint {
  color: var(--ink-3);
  font-size: 12.5px;
  font-weight: 400;
}

/* 动效敏感者：微光和三点跳动都停掉，静态文字仍然表达清楚进度 */
@media (prefers-reduced-motion: reduce) {
  .ap-dots i { animation: none; opacity: 0.7; }
  .ap-step { transition: none; }
}
</style>
