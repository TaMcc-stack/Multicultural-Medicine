<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppTopbar from '@/components/AppTopbar.vue'
import { avatarUrlOf } from '@/api/auth'
import { timeAgo } from '@/api/dynamic'
import {
  createFeedback,
  deleteFeedback,
  feedbackAuthor,
  feedbackStatusCls,
  feedbackStatusText,
  likeFeedback,
  listFeedback,
  unlikeFeedback,
} from '@/api/feedback'
import type { FeedbackItem } from '@/api/feedback'
import { useUserStore } from '@/stores/user'
import { extractTags } from '@/utils/medicalVocab'
import { avatarInitial, avatarStyleOf } from '@/utils/avatar'

/**
 * 用户反馈留言板。
 *
 * **这是全站唯一不需要登录的业务页面**：未登录也能浏览与发布（路由 meta.public，
 * 接口在服务端的可选登录白名单里）。所以这里的每一处都要考虑「当前没有身份」的情形：
 *   · 发布 → 作者记为空，卡片显示「匿名用户」
 *   · 点赞 → 需要身份才能去重，未登录时引导去登录
 *   · 列表 → 匿名请求拿不到 liked，恒为 false
 *
 * 与「动态」的区别：动态是把自己的问答分享出去，这里是把**需求**说出去——
 * 后者直接喂给后台的需求分析，管理员据此决定补录什么文献。
 */
const router = useRouter()
const userStore = useUserStore()

/**
 * 管理员判定：与知识库页、需求分析页同一套写法（都标着 TODO：应改为基于角色的权限接口）。
 * 前端只用来决定「要不要显示删除按钮」——**真正的闸门在服务端**，越权请求会拿到 403。
 */
const isAdmin = computed(() => userStore.user?.id === 1)

const items = ref<FeedbackItem[]>([])
const loading = ref(true)
const draft = ref('')
const posting = ref(false)
/** 本次会话里刚发布的那条，用于在列表里高亮它（重拉列表后就没有了） */
const justPostedId = ref<number | null>(null)
/** 正在点赞/取消的那条，避免连点 */
const likeBusyId = ref<number | null>(null)
/** 正在删除的那条，避免连点 */
const deletingId = ref<number | null>(null)

const MAX_LEN = 500

/**
 * 能不能删这条：**本人或管理员**。
 *
 * 与后端的判据一致（{@code FeedbackService.delete}）。匿名帖的 `owner` 对所有人都为 false
 * ——服务端也是这么算的（user_id 为 null 时无人可称本人），所以匿名帖只有管理员能删。
 */
function canDelete(f: FeedbackItem): boolean {
  return f.owner || isAdmin.value
}

/**
 * 列表行：把自动标签一并算好。
 *
 * `extractTags` 每次调用都要把两份词表各排序一遍，放进模板里会变成
 * 「每次渲染 × 每行 × 两个绑定」的重复排序。这里随 items 变化算一次。
 */
const rows = computed(() =>
  items.value.map((f) => ({ f, tags: extractTags(f.content) })),
)

async function load() {
  loading.value = true
  try {
    items.value = await listFeedback(100)
  } catch {
    // 拦截器已统一提示；留言板拉不到就空着，不阻塞发布
    items.value = []
  } finally {
    loading.value = false
  }
}

async function publish() {
  const content = draft.value.trim()
  if (!content) {
    ElMessage.warning('先写点什么吧')
    return
  }
  posting.value = true
  try {
    const created = await createFeedback(content)
    // 本地插到最前，**不重拉列表**：重拉会让它按真实排序（0 赞）落回去，
    // 用户会以为没发出去。下次进页面时它因为 id 倒序也仍在 0 赞组的最前面。
    items.value = [created, ...items.value.filter((x) => x.id !== created.id)]
    justPostedId.value = created.id
    draft.value = ''
    ElMessage.success('已发布，感谢你的反馈')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败，请稍后重试')
  } finally {
    posting.value = false
  }
}

/**
 * 点赞 / 取消点赞。
 *
 * 未登录先引导登录：点赞要按用户去重，没有身份就没法保证「同一个人只点一次」。
 * 登录后**乐观更新**（先改本地数字再发请求），失败回滚——按点赞是高频动作，
 * 等一次往返再跳数字会显得迟钝。
 */
async function toggleLike(f: FeedbackItem) {
  if (!userStore.isLoggedIn) {
    ElMessage.info('登录后即可点赞')
    router.push({ name: 'login', query: { redirect: '/feedback' } })
    return
  }
  if (likeBusyId.value != null) return
  likeBusyId.value = f.id
  const before = { liked: f.liked, likeCount: f.likeCount }
  f.liked = !before.liked
  f.likeCount = before.likeCount + (f.liked ? 1 : -1)
  try {
    // 以服务端返回的为准（并发下别人也在点，本地的加减只是瞬时反馈）
    const updated = await (before.liked ? unlikeFeedback(f.id) : likeFeedback(f.id))
    Object.assign(f, updated)
  } catch {
    Object.assign(f, before)
  } finally {
    likeBusyId.value = null
  }
}

/** 自定义头像地址；匿名帖没有用户 id，直接返回空串走「首字 + 配色」 */
function avatarUrl(f: FeedbackItem): string {
  return f.userId == null ? '' : avatarUrlOf({ id: f.userId, avatar: f.avatar })
}

/**
 * 删除一条反馈（本人或管理员）。
 *
 * 二次确认不能省：留言板没有回收站，删掉就真没了——被删的那条连带点赞记录一起清掉。
 */
async function remove(f: FeedbackItem) {
  try {
    await ElMessageBox.confirm('确定要删除这条反馈吗？', '删除反馈', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return // 用户点了取消，什么都不做
  }
  deletingId.value = f.id
  try {
    await deleteFeedback(f.id)
    items.value = items.value.filter((x) => x.id !== f.id)
    // 删掉的正好是刚发的那条，高亮标记一并清掉，否则它会挂在一个不存在的 id 上
    if (justPostedId.value === f.id) justPostedId.value = null
    ElMessage.success('已删除')
  } catch (e) {
    // 失败提示由 axios 拦截器统一弹出（服务端会说清是「无权限」还是「不存在」），
    // 这里只在它没给出任何文案时兜底一句——同一个失败弹两条提示比少弹一条更糟
    if (!(e instanceof Error) || !e.message) ElMessage.error('删除失败，请重试')
  } finally {
    deletingId.value = null
  }
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
            <h1 class="page-title">用户反馈</h1>
            <p class="page-sub">
              说出你想了解的民族健康问题，我们会优先补充。提到具体的民族和疾病，更容易被收录。
            </p>
          </div>
        </div>

        <!-- 发布区 -->
        <section class="card compose">
          <textarea
            v-model="draft"
            class="compose-input"
            rows="3"
            :maxlength="MAX_LEN"
            placeholder="说出你想了解的民族健康问题，我们会优先补充"
            @keydown.ctrl.enter="publish"
          ></textarea>
          <div class="compose-foot">
            <span class="compose-hint">
              {{ draft.length }}/{{ MAX_LEN }} · Ctrl + Enter 快速发布
              <template v-if="!userStore.isLoggedIn">· 未登录也可以发布，将以「匿名用户」显示</template>
            </span>
            <button
              class="btn btn-solid"
              type="button"
              :disabled="posting || !draft.trim()"
              @click="publish"
            >{{ posting ? '发布中…' : '发布' }}</button>
          </div>
        </section>

        <!-- 列表：按点赞数倒序 -->
        <section class="card board" v-loading="loading">
          <div class="board-head">
            <h2 class="board-title">大家想了解的</h2>
            <span class="board-sub">按点赞数排序 · {{ items.length }} 条</span>
          </div>

          <div v-if="!items.length && !loading" class="empty-state empty-state-sm">
            <p class="empty-state-title">还没有人反馈</p>
            <p class="empty-state-desc">成为第一个说出需求的人吧。</p>
          </div>

          <article
            v-for="row in rows"
            :key="row.f.id"
            class="fb"
            :class="{ just: row.f.id === justPostedId }"
          >
            <div class="fb-head">
              <span class="fb-avatar" :style="avatarStyleOf(row.f.avatar)">
                <img v-if="avatarUrl(row.f)" :src="avatarUrl(row.f)" :alt="feedbackAuthor(row.f)" />
                <span v-else>{{ avatarInitial(feedbackAuthor(row.f)) }}</span>
              </span>
              <span class="fb-author">{{ feedbackAuthor(row.f) }}</span>
              <span v-if="row.f.id === justPostedId" class="fb-new">我发布的</span>
              <span class="spacer"></span>
              <span class="pill" :class="feedbackStatusCls(row.f.status)">
                {{ feedbackStatusText(row.f.status) }}
              </span>
              <span class="fb-time">{{ timeAgo(row.f.createdAt) }}</span>
            </div>

            <!-- 用户原话，保留换行 -->
            <p class="fb-content">{{ row.f.content }}</p>

            <!-- 自动识别的标签：识别不出民族/疾病时整行不渲染 -->
            <div v-if="row.tags.length" class="fb-tags">
              <span v-for="t in row.tags" :key="t" class="tag">#{{ t }}</span>
            </div>

            <div class="fb-foot">
              <button
                class="like"
                :class="{ on: row.f.liked }"
                type="button"
                :disabled="likeBusyId === row.f.id"
                :title="row.f.liked ? '取消点赞' : '点赞，让这个需求排到更前面'"
                @click="toggleLike(row.f)"
              >
                <span class="like-icon">👍</span>
                <span class="like-text">{{ row.f.liked ? '已点赞' : '点赞' }}</span>
                <b class="like-num">{{ row.f.likeCount }}</b>
              </button>

              <!-- 只有本人与管理员看得到。判据与服务端一致（owner || 管理员） -->
              <button
                v-if="canDelete(row.f)"
                class="del"
                type="button"
                :disabled="deletingId === row.f.id"
                :title="row.f.owner ? '删除我发布的这条反馈' : '删除这条反馈（管理员）'"
                @click="remove(row.f)"
              >{{ deletingId === row.f.id ? '删除中…' : '删除' }}</button>
            </div>
          </article>
        </section>
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
.work { max-width: 780px; margin: 0 auto; display: flex; flex-direction: column; gap: 18px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
.page-title { font-family: var(--serif); font-weight: 700; font-size: 24px; letter-spacing: 0.5px; }
.page-sub { color: var(--ink-2); font-size: 14.5px; margin-top: 6px; line-height: 1.7; }

.card {
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--r-md); box-shadow: var(--shadow-sm);
}

/* ---------- 发布区 ---------- */
.compose { padding: 16px 18px 14px; }
.compose-input {
  width: 100%; box-sizing: border-box; resize: vertical; min-height: 76px;
  font-family: var(--sans); font-size: 14.5px; line-height: 1.8; color: var(--ink);
  background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-sm);
  padding: 11px 13px; transition: 0.15s;
}
.compose-input:hover { border-color: var(--line-strong); }
.compose-input:focus { outline: none; border-color: var(--clay); background: var(--surface); }
.compose-foot {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; flex-wrap: wrap; margin-top: 10px;
}
.compose-hint { font-size: 12.5px; color: var(--ink-3); }

/* ---------- 列表 ---------- */
.board { padding: 18px 20px 12px; }
.board-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.board-title { font-family: var(--serif); font-size: 17px; font-weight: 700; color: var(--ink); }
.board-sub { font-size: 12px; color: var(--ink-3); }

.fb {
  padding: 14px 14px 12px; border-radius: var(--r-sm);
  border: 1px solid transparent; transition: 0.15s;
}
.fb + .fb { margin-top: 4px; }
.fb:hover { background: var(--bg); border-color: var(--line-strong); }
/* 刚发布的那条：加一圈暖色边，让用户确认「发出去了」 */
.fb.just { background: var(--clay-soft); border-color: var(--accent-line); }

.fb-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.fb-avatar {
  flex: none; width: 30px; height: 30px; border-radius: 50%;
  display: grid; place-items: center; overflow: hidden;
  color: #fff; font-size: 13px; font-weight: 600;
}
.fb-avatar img { width: 100%; height: 100%; object-fit: cover; }
.fb-author { font-size: 13.5px; font-weight: 600; color: var(--ink); }
.fb-new {
  font-size: 11px; color: var(--clay-deep); background: var(--surface);
  border: 1px solid var(--accent-line); border-radius: 999px; padding: 1px 8px;
}
.spacer { flex: 1; }
.fb-time { font-size: 12px; color: var(--ink-3); flex: none; }

.fb-content {
  margin: 9px 0 0; font-size: 14.5px; line-height: 1.85; color: var(--ink);
  /* 用户原话，保留换行 */
  white-space: pre-wrap; word-break: break-word;
}

.fb-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.tag {
  font-size: 11.5px; color: var(--clay-deep); background: var(--clay-soft);
  border: 1px solid var(--accent-line); border-radius: 999px; padding: 2px 9px;
}

.fb-foot { margin-top: 10px; display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.like {
  display: inline-flex; align-items: center; gap: 6px;
  font-family: var(--sans); font-size: 13px; color: var(--ink-2);
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 999px; padding: 5px 14px; cursor: pointer; transition: 0.15s;
}
.like:hover:not(:disabled) { border-color: var(--clay); color: var(--clay-deep); }
.like:disabled { cursor: default; opacity: 0.6; }
.like.on { background: var(--clay-soft); border-color: var(--accent-line); color: var(--clay-deep); }
.like-icon { font-size: 13px; }
.like-num { font-variant-numeric: tabular-nums; }

/* 删除：靠右、低饱和，别跟点赞抢眼——它是低频且不可逆的动作 */
.del {
  flex: none; font-family: var(--sans); font-size: 12.5px;
  color: var(--ink-3); background: none; border: 0;
  padding: 4px 6px; border-radius: var(--r-sm); cursor: pointer; transition: 0.15s;
}
.del:hover:not(:disabled) { color: var(--danger); background: var(--danger-soft); }
.del:disabled { cursor: default; opacity: 0.6; }

/* 状态标签：与 DemandView 里那三个是同一套（两处都是 scoped，各写一份） */
.pill {
  flex: none; font-size: 11.5px; padding: 2px 9px; border-radius: 999px;
  border: 1px solid var(--line);
}
.pill.pending { color: var(--amber); background: var(--amber-soft); border-color: var(--amber-line); }
.pill.processing { color: var(--clay-deep); background: var(--clay-soft); border-color: var(--accent-line); }
.pill.done { color: var(--sage-deep); background: var(--sage-soft); border-color: var(--sage-line); }

.footer { border-top: 1px solid var(--line); background: var(--surface); }
.footer .wrap { height: 76px; display: flex; align-items: center; justify-content: space-between; }
.foot-brand { font-family: var(--serif); font-weight: 700; font-size: 15px; letter-spacing: 0.5px; color: var(--ink-2); }
.foot-note { font-size: 12.5px; color: var(--ink-3); }

@media (max-width: 560px) {
  .main { padding: 22px 16px 48px; }
  .board { padding: 14px 12px 8px; }
}
</style>
