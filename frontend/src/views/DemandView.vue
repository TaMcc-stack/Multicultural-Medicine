<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppTopbar from '@/components/AppTopbar.vue'
import GapSourceTags from '@/components/GapSourceTags.vue'
import { gapTitle, listGaps, resolveGap } from '@/api/gap'
import type { GapItem } from '@/api/gap'
import {
  FEEDBACK_STATUS_ORDER,
  feedbackAuthor,
  feedbackStatusCls,
  feedbackStatusText,
  feedbackToGap,
  listFeedback,
  setFeedbackStatus,
} from '@/api/feedback'
import type { FeedbackItem, FeedbackStatus } from '@/api/feedback'
import { timeAgo } from '@/api/dynamic'
import { extractSlots, extractTags } from '@/utils/medicalVocab'
import { useUserStore } from '@/stores/user'

/**
 * 后台 · 需求分析
 *
 * 「缺什么」有两个来源，分别回答两个问题：
 *   · 知识缺口榜（前三个榜）—— **系统量出来的 + 用户主动说的**：
 *       高级检索未命中会自动登记（三个槽位来自用户选的下拉，本身就是结构化需求）；
 *       智能对话未命中则要用户点了【反馈此问题】才登记（那里的槽位是 NLU 猜出来的，
 *       猜错会污染榜单，所以不自动登记）。两者写同一张表，靠来源徽标区分。
 *   · 用户反馈榜（第四块）—— **留言板里的一句话**，是另一种形状的需求，
 *       与「民族 × 疾病 × 方面」对不齐，所以不混进榜里，单独一块。
 *
 * 三个缺口榜的排序各不相同：热门榜按被问次数（大家在关心什么）、未解决榜按反馈人数
 * （还欠着什么，也就是「多少人想要」）、已解决历史按最后一次被问时间。
 */
const router = useRouter()
const userStore = useUserStore()

/** 与知识库页同一套判定（那里也标着 TODO：应改为基于角色的权限接口，当前先沿用） */
const isAdmin = computed(() => userStore.user?.id === 1)

const hotGaps = ref<GapItem[]>([])
const openGaps = ref<GapItem[]>([])
/** 已解决历史：补录入库结掉的 + 管理员人工标记的，默认折叠 */
const resolvedGaps = ref<GapItem[]>([])
const resolvedOpen = ref(false)
/** 正在标记的记录 id，避免连点 */
const resolvingId = ref<number | null>(null)
const loading = ref(true)
/** 非管理员访问时服务端会 403，这里给一句人话而不是空列表 */
const denied = ref(false)

// ---------- 用户反馈（第四块） ----------
const feedbacks = ref<FeedbackItem[]>([])
/** 正在改状态 / 转缺口的那条，避免连点 */
const feedbackBusyId = ref<number | null>(null)
const gapBusyId = ref<number | null>(null)

/**
 * 反馈行：把自动识别出的民族/疾病一并算好。
 *
 * 它同时服务两件事：显示 `#民族 #疾病` 标签，以及「转为知识缺口」要传的三个槽位。
 * 识别复用前端的词表（`extractTags`）而**不在 Java 侧再写一份**——这个项目已经因为
 * 「同一份词表多处维护、互不一致」吃过亏。
 */
const feedbackRows = computed(() =>
  feedbacks.value.map((f) => {
    const { ethnicity, disease } = extractSlots(f.content)
    return {
      f,
      tags: extractTags(f.content),
      ethnicity,
      disease,
      convertible: !!ethnicity && !!disease,
    }
  }),
)

async function load() {
  if (!isAdmin.value) {
    denied.value = true
    loading.value = false
    return
  }
  loading.value = true
  // 四个榜各自独立处理失败，不让一个的异常把其他也清空
  const [hot, open, resolved, fb] = await Promise.allSettled([
    listGaps('ask', 50),
    listGaps('feedback', 50),
    listGaps('resolved', 50),
    listFeedback(100),
  ])
  hotGaps.value = hot.status === 'fulfilled' ? hot.value : []
  openGaps.value = open.status === 'fulfilled' ? open.value : []
  resolvedGaps.value = resolved.status === 'fulfilled' ? resolved.value : []
  feedbacks.value = fb.status === 'fulfilled' ? fb.value : []
  loading.value = false
}

/** 改一条反馈的状态。就地替换那一行，不重拉整榜——重拉会让列表闪一下。 */
async function changeStatus(f: FeedbackItem, status: FeedbackStatus) {
  if (feedbackBusyId.value != null || f.status === status) return
  feedbackBusyId.value = f.id
  try {
    const updated = await setFeedbackStatus(f.id, status)
    feedbacks.value = feedbacks.value.map((x) => (x.id === f.id ? updated : x))
    ElMessage.success(`已标记为「${feedbackStatusText(status)}」`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '标记失败，请稍后重试')
  } finally {
    feedbackBusyId.value = null
  }
}

/**
 * 一键转为知识缺口。
 *
 * 三个槽位：民族与疾病来自上面的自动识别，**方面统一用兜底档 `all`（患病情况）**——
 * 反馈是一句话，说不出用户想问哪个方面。转完这条反馈的状态会被服务端置为「处理中」。
 * `touchAsk` 在服务端是幂等的，重复转同一条不会建出第二个缺口。
 */
async function convertToGap(row: { f: FeedbackItem; ethnicity: string; disease: string; convertible: boolean }) {
  if (!row.convertible || gapBusyId.value != null) return
  gapBusyId.value = row.f.id
  try {
    const updated = await feedbackToGap(row.f.id, {
      ethnicity: row.ethnicity,
      disease: row.disease,
      intent: 'all',
    })
    feedbacks.value = feedbacks.value.map((x) => (x.id === row.f.id ? updated : x))
    ElMessage.success(`已转为知识缺口：${row.ethnicity} · ${row.disease} · 患病情况`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '转换失败，请稍后重试')
  } finally {
    gapBusyId.value = null
  }
}

/**
 * 标记一条缺口为「已解决」。
 *
 * 与「补录文献 → 审核通过后自动结掉」是两条路：这条用于管理员判定「不用补了」。
 * 标记后本地直接把它从未解决榜摘掉，不必重拉整个榜单——重拉会让列表闪一下。
 */
async function markResolved(g: GapItem) {
  if (resolvingId.value) return
  resolvingId.value = g.id
  try {
    const updated = await resolveGap(g.id)
    openGaps.value = openGaps.value.filter((x) => x.id !== g.id)
    resolvedGaps.value = [updated, ...resolvedGaps.value.filter((x) => x.id !== g.id)]
    ElMessage.success('已标记为解决，可在下方「已解决历史」里回看')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '标记失败，请稍后重试')
  } finally {
    resolvingId.value = null
  }
}

/** 去补充：带上缺口 id，工作台据此显示在补哪个缺口并预填关键词 */
function goSupply(gapId: number) {
  router.push({ name: 'supply', query: { gap: String(gapId) } })
}

/** 已补充的可以跳去看补进来的那份资料 */
function goDoc(g: GapItem) {
  router.push({ name: 'kb', hash: `#${g.filledDocId}` })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <AppTopbar />

    <main class="main">
      <div class="work">
        <div class="page-head">
          <div>
            <h1 class="page-title">后台 · 需求分析</h1>
            <p class="page-sub">高级检索未命中的「民族 × 疾病 × 方面」组合，按需求强度排序</p>
          </div>
        </div>

        <div v-if="denied" class="card">
          <div class="empty-state">
            <p class="empty-state-title">仅管理员可访问</p>
            <p class="empty-state-desc">
              需求分析是运营视角的统计，不对外开放。请用管理员账号登录后查看。
            </p>
          </div>
        </div>

        <template v-else>
          <!-- ① 热门话题：按被问次数 -->
          <section class="card rank" v-loading="loading">
            <div class="rank-head">
              <h2 class="rank-title">热门话题排行</h2>
              <span class="rank-sub">按被问次数 · 含已补充</span>
            </div>
            <div v-if="!hotGaps.length && !loading" class="empty-state empty-state-sm">
              <p class="empty-state-title">还没有缺口记录</p>
              <p class="empty-state-desc">
                用户在<button class="btn btn-link" type="button" @click="router.push({ name: 'advanced-search' })">高级检索</button>里选了知识库没有的组合时，就会记到这里。
              </p>
            </div>
            <ol class="rank-list">
              <li v-for="(g, i) in hotGaps" :key="g.id" class="rank-item">
                <span class="rank-no" :class="{ top: i < 3 }">{{ i + 1 }}</span>
                <span class="rank-name" :title="g.originalQuery || ''">{{ gapTitle(g) }}</span>
                <GapSourceTags :gap="g" />
                <span v-if="g.status === 'filled'" class="pill filled">已补充</span>
                <span class="rank-time">{{ timeAgo(g.lastAskedAt) }}</span>
                <span class="rank-num">{{ g.askCount }}<em>次</em></span>
              </li>
            </ol>
          </section>

          <!-- ② 未解决话题：按反馈人数 -->
          <section class="card rank" v-loading="loading">
            <div class="rank-head">
              <h2 class="rank-title">未解决话题排行</h2>
              <span class="rank-sub">按反馈人数 · 只含待补充 · 含两个来源</span>
            </div>
            <div v-if="!openGaps.length && !loading" class="empty-state empty-state-sm">
              <p class="empty-state-title">没有待补充的缺口</p>
              <p class="empty-state-desc">所有被问过的缺失组合都已经补上资料了。</p>
            </div>
            <ol class="rank-list">
              <li v-for="(g, i) in openGaps" :key="g.id" class="rank-item">
                <span class="rank-no" :class="{ top: i < 3 }">{{ i + 1 }}</span>
                <!-- 这一榜两行显示：来源不同，光看三个槽位看不出「谁在问、问的什么」 -->
                <span class="rank-name rank-name-col">
                  <span>{{ gapTitle(g) }}</span>
                  <em v-if="g.originalQuery" class="rank-orig">{{ g.originalQuery }}</em>
                </span>
                <GapSourceTags :gap="g" />
                <span class="pill open">待补充</span>
                <span class="rank-num">{{ g.feedbackCount }}<em>人反馈</em></span>
                <button class="btn btn-sm btn-soft" type="button" @click="goSupply(g.id)">去补充</button>
                <!-- 补不了就收口：标记后移出本榜，进下方「已解决历史」。不关联任何文档 -->
                <button
                  class="btn btn-sm btn-outline"
                  type="button"
                  :disabled="resolvingId === g.id"
                  title="判定这条不用补了，移入已解决历史"
                  @click="markResolved(g)"
                >{{ resolvingId === g.id ? '处理中…' : '标记已解决' }}</button>
              </li>
            </ol>
          </section>

          <!-- ③ 已解决历史：补录入库结掉的 + 管理员人工标记的。默认折叠——它是存档，不是待办 -->
          <section class="card rank">
            <button class="resolved-head" type="button" @click="resolvedOpen = !resolvedOpen">
              <span class="resolved-caret" :class="{ open: resolvedOpen }">▸</span>
              <span class="rank-title">已解决历史</span>
              <span class="rank-sub">{{ resolvedGaps.length }} 条 · 补录入库的与人工标记的都在这里</span>
            </button>
            <div v-show="resolvedOpen">
              <div v-if="!resolvedGaps.length" class="empty-state empty-state-sm">
                <p class="empty-state-title">还没有已解决的缺口</p>
                <p class="empty-state-desc">补录的文献审核通过后，或在上方点「标记已解决」，都会归到这里。</p>
              </div>
              <ol v-else class="rank-list">
                <li v-for="g in resolvedGaps" :key="g.id" class="rank-item">
                  <span class="rank-name" :title="g.originalQuery || ''">{{ gapTitle(g) }}</span>
                  <GapSourceTags :gap="g" />
                  <span v-if="g.status === 'filled'" class="pill filled">已补充</span>
                  <span v-else class="pill resolved">已标记</span>
                  <span class="rank-time">被问 {{ g.askCount }} 次</span>
                  <button
                    v-if="g.filledDocId"
                    class="btn btn-sm btn-link"
                    type="button"
                    @click="goDoc(g)"
                  >看看补了什么</button>
                </li>
              </ol>
            </div>
          </section>

          <!-- ④ 用户反馈：与上面三个榜不同，它是**用户自己说出来的**需求，不是系统量出来的。
               按点赞数排序——「有多少人想要」才是优先级，一个人的多次点击不算（服务端按用户去重）。 -->
          <section class="card rank" v-loading="loading">
            <div class="rank-head">
              <h2 class="rank-title">用户反馈</h2>
              <span class="rank-sub">按点赞数 · 由用户在留言板发布</span>
              <button
                class="btn btn-sm btn-link head-link"
                type="button"
                @click="router.push({ name: 'feedback' })"
              >去留言板看看 ›</button>
            </div>
            <div v-if="!feedbacks.length && !loading" class="empty-state empty-state-sm">
              <p class="empty-state-title">还没有用户反馈</p>
              <p class="empty-state-desc">
                用户在<button class="btn btn-link" type="button" @click="router.push({ name: 'feedback' })">用户反馈</button>里说出的需求会汇总到这里。
              </p>
            </div>

            <div v-for="row in feedbackRows" :key="row.f.id" class="fb-row">
              <div class="fb-main">
                <div class="fb-line">
                  <span class="fb-author">{{ feedbackAuthor(row.f) }}</span>
                  <span class="pill" :class="feedbackStatusCls(row.f.status)">{{ feedbackStatusText(row.f.status) }}</span>
                  <span class="fb-time">{{ timeAgo(row.f.createdAt) }}</span>
                  <span class="rank-num">{{ row.f.likeCount }}<em>赞</em></span>
                </div>
                <p class="fb-content">{{ row.f.content }}</p>
                <!-- 自动识别出的民族/疾病：也是「转为知识缺口」要用的三个槽位中的两个 -->
                <div v-if="row.tags.length" class="fb-tags">
                  <span v-for="t in row.tags" :key="t" class="tag">#{{ t }}</span>
                </div>
              </div>

              <div class="fb-ops">
                <!-- 状态切换：三个按钮一排，当前状态高亮 -->
                <div class="fb-status">
                  <button
                    v-for="s in FEEDBACK_STATUS_ORDER"
                    :key="s"
                    class="btn btn-sm"
                    :class="row.f.status === s ? 'btn-soft' : 'btn-outline'"
                    type="button"
                    :disabled="feedbackBusyId === row.f.id"
                    @click="changeStatus(row.f, s)"
                  >{{ feedbackStatusText(s) }}</button>
                </div>

                <!-- 一键转缺口：识别不出民族或疾病时禁用，并把原因说清楚 -->
                <button
                  v-if="!row.f.gapId"
                  class="btn btn-sm btn-outline btn-sage"
                  type="button"
                  :disabled="!row.convertible || gapBusyId === row.f.id"
                  :title="row.convertible
                    ? `转为知识缺口：${row.ethnicity} · ${row.disease} · 患病情况`
                    : '这条反馈里认不出民族或疾病，无法定位到具体缺口'"
                  @click="convertToGap(row)"
                >{{ gapBusyId === row.f.id ? '转换中…' : '转为知识缺口' }}</button>
                <button
                  v-else
                  class="btn btn-sm btn-link"
                  type="button"
                  title="这条反馈已经转成缺口了，点进去补充文献"
                  @click="goSupply(row.f.gapId)"
                >已转缺口 · 去补充 ›</button>
              </div>
            </div>
          </section>

          <p class="foot-hint">
            补录的资料会进入<button class="btn btn-link" type="button" @click="router.push({ name: 'kb' })">知识库</button>的「待审核」队列，审核通过后该缺口自动标记为已补充。
          </p>
        </template>
      </div>
    </main>

    <footer class="footer">
      <div class="wrap">
        <span class="foot-brand">多民族特色医学智能体</span>
        <span class="foot-note">产品概念原型 · 演示数据</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; display: flex; flex-direction: column; }
.main { flex: 1; padding: 32px 24px 64px; }
.work { max-width: 880px; margin: 0 auto; display: flex; flex-direction: column; gap: 18px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
.page-title { font-family: var(--serif); font-weight: 700; font-size: 24px; letter-spacing: 0.5px; }
.page-sub { color: var(--ink-2); font-size: 14.5px; margin-top: 6px; }

.card {
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--r-md); box-shadow: var(--shadow-sm);
}
.rank { padding: 18px 20px 14px; }
.rank-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.rank-title { font-family: var(--serif); font-size: 17px; font-weight: 700; color: var(--ink); }
.rank-sub { font-size: 12px; color: var(--ink-3); }

.rank-list { list-style: none; margin: 0; padding: 0; }
.rank-item {
  display: flex; align-items: center; gap: 10px;
  padding: 11px 12px; border-radius: var(--r-sm);
  border: 1px solid transparent; transition: 0.15s;
}
.rank-item + .rank-item { margin-top: 2px; }
.rank-item:hover { background: var(--bg); border-color: var(--line-strong); }
/* 前三名给个暖色序号，扫一眼能看出头部 */
.rank-no {
  flex: none; width: 22px; height: 22px; border-radius: 50%;
  display: grid; place-items: center;
  font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums;
  color: var(--ink-3); background: var(--bg); border: 1px solid var(--line);
}
.rank-no.top { color: var(--clay-deep); background: var(--clay-soft); border-color: var(--accent-line); }
.rank-name { flex: 1; min-width: 0; font-size: 14px; color: var(--ink); }
/* 未解决榜两行显示：上面是三个槽位，下面是用户的原始提问。
   同一个 .rank-name 在别的榜仍是单行——那边加了 `:title` 悬停看原话即可，
   行高不同反而会让三个榜看起来是三种东西。 */
.rank-name-col { display: flex; flex-direction: column; gap: 3px; }
.rank-orig {
  font-family: var(--sans);
  font-size: 12px;
  font-style: normal;
  color: var(--ink-3);
  line-height: 1.6;
  /* 长问题压到一行，免得把整条撑高 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rank-time { font-size: 12px; color: var(--ink-3); flex: none; }
.rank-num {
  flex: none; font-family: var(--serif); font-size: 16px; font-weight: 700;
  color: var(--ink); font-variant-numeric: tabular-nums;
}
.rank-num em { font-family: var(--sans); font-size: 11.5px; font-weight: 400; font-style: normal; color: var(--ink-3); margin-left: 2px; }

.pill {
  flex: none; font-size: 11.5px; padding: 2px 9px; border-radius: 999px;
  border: 1px solid var(--line);
}
.pill.open { color: var(--amber); background: var(--amber-soft); border-color: var(--amber-line); }
.pill.filled { color: var(--sage-deep); background: var(--sage-soft); border-color: var(--sage-line); }
.pill.resolved { color: var(--ink-3); background: var(--bg); border-color: var(--line); }
/* 反馈的三种状态。与 FeedbackView 里的那三个是同一套配色，但两处都是 scoped，各写一份 */
.pill.pending { color: var(--amber); background: var(--amber-soft); border-color: var(--amber-line); }
.pill.processing { color: var(--clay-deep); background: var(--clay-soft); border-color: var(--accent-line); }
.pill.done { color: var(--sage-deep); background: var(--sage-soft); border-color: var(--sage-line); }

/* ---------- ④ 用户反馈 ---------- */
.head-link { margin-left: auto; }
.fb-row {
  display: flex; align-items: flex-start; gap: 14px;
  padding: 12px; border-radius: var(--r-sm);
  border: 1px solid transparent; transition: 0.15s;
}
.fb-row + .fb-row { margin-top: 2px; }
.fb-row:hover { background: var(--bg); border-color: var(--line-strong); }
.fb-main { flex: 1; min-width: 0; }
.fb-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.fb-author { font-size: 13.5px; font-weight: 600; color: var(--ink); }
.fb-time { font-size: 12px; color: var(--ink-3); }
.fb-content {
  margin: 6px 0 0; font-size: 13.5px; line-height: 1.75; color: var(--ink-2);
  /* 用户原话保留换行，但后台是表格行，压到三行以内免得一条占满屏 */
  white-space: pre-wrap; word-break: break-word;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.fb-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 7px; }
.tag {
  font-size: 11px; color: var(--clay-deep); background: var(--clay-soft);
  border: 1px solid var(--accent-line); border-radius: 999px; padding: 1px 8px;
}
/* 操作区竖排在右侧：状态一排 + 转缺口一行，各自成组 */
.fb-ops { flex: none; display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.fb-status { display: flex; gap: 4px; flex-wrap: wrap; justify-content: flex-end; }

/* 已解决历史的折叠头：整行可点，不带卡片内的额外边距 */
.resolved-head {
  display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap;
  width: 100%; border: 0; background: transparent; cursor: pointer;
  font-family: var(--sans); text-align: left; padding: 0;
}
.resolved-caret { font-size: 12px; color: var(--ink-3); transition: transform 0.18s; }
.resolved-caret.open { transform: rotate(90deg); }
.resolved-head .rank-title { margin: 0; }

.foot-hint { font-size: 12.5px; color: var(--ink-3); line-height: 1.8; }

.footer { border-top: 1px solid var(--line); background: var(--surface); }
.footer .wrap { height: 76px; display: flex; align-items: center; justify-content: space-between; }
.foot-brand { font-family: var(--serif); font-weight: 700; font-size: 15px; letter-spacing: 0.5px; color: var(--ink-2); }
.foot-note { font-size: 12.5px; color: var(--ink-3); }

@media (max-width: 560px) {
  .main { padding: 22px 16px 48px; }
  .rank { padding: 14px 12px 10px; }
}
</style>
