<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import AppTopbar from '@/components/AppTopbar.vue'
import {
  deleteDynamic,
  dynamicAuthor,
  getDynamic,
  listDynamics,
  timeAgo,
} from '@/api/dynamic'
import type { DynamicItem, DynamicKind } from '@/api/dynamic'
import { addFavorite, removeFavorite } from '@/api/favorite'
import { extractTags } from '@/utils/medicalVocab'
import { intentLabel } from '@/api/qa'
import { esc, listMarker } from '@/utils/markdown'
import DynamicDetailModal from '@/components/DynamicDetailModal.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/** 每页条数；滚到底自动取下一页 */
const PAGE_SIZE = 20

/**
 * 两个社区页签：
 *   dialogue 多轮对话分享（原有内容）
 *   qa       一问一答卡片（高级检索页分享出来的）
 *
 * 进来时若带了 `?tab=qa`（高级检索页分享成功后的「去社区看看」），直接落在问答页签。
 */
const TABS: { key: DynamicKind; label: string; sub: string }[] = [
  { key: 'dialogue', label: '多轮对话', sub: '来自智能对话的分享 · 可收藏、可溯源' },
  { key: 'qa', label: '一问一答', sub: '按「民族 × 疾病」沉淀的基础问答 · 每张卡只留最新一份' },
]
const tab = ref<DynamicKind>(route.query.tab === 'qa' ? 'qa' : 'dialogue')

const loading = ref(false)
const loadingMore = ref(false)
const hasMore = ref(true)
const list = ref<DynamicItem[]>([])
const detailVisible = ref(false)
const detail = ref<DynamicItem | null>(null)
const busyId = ref<number | null>(null)
const keyword = ref('')
/** 触底哨兵：进入视口就加载下一页 */
const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/**
 * 卡片视图模型。
 *
 * 后端不返回 `tags` 和文献来源，只有 title / content / detailJson——
 * 展示需要的那几项在这里一次性算好，模板里就不用反复 JSON.parse detailJson。
 */
interface DynamicCard {
  id: number
  title: string
  /** 摘要：优先取回答的核心结论，退回正文 */
  excerpt: string
  tags: string[]
  /** 依据的文献名（去重），如 ['数据资料'] */
  sources: string[]
  author: string
  time: string
  owner: boolean
  favorited: boolean
  favoriteCount: number
  raw: DynamicItem
}

/** 从 detailJson 里抽出「核心结论」与「依据文献」 */
function readDetail(d: DynamicItem): { conclusion: string; sources: string[] } {
  if (!d.detailJson) return { conclusion: '', sources: [] }
  let obj: any
  try {
    obj = JSON.parse(d.detailJson)
  } catch {
    return { conclusion: '', sources: [] }
  }
  const sources: string[] = []
  const push = (t?: string) => {
    if (t && !sources.includes(t)) sources.push(t)
  }
  let conclusion = ''
  // v2：整段对话；v1：单条回答（改版前分享的动态仍在库里）
  const turns: any[] = Array.isArray(obj?.turns) && obj.turns.length ? obj.turns : [obj]
  for (const t of turns) {
    for (const c of t?.citations || []) push(c?.title || c?.paper?.title)
    if (!conclusion) conclusion = t?.answer?.conclusion || t?.plain || ''
  }
  return { conclusion, sources }
}

function toCard(d: DynamicItem): DynamicCard {
  const { conclusion, sources } = readDetail(d)
  return {
    id: d.id,
    title: d.title || '（无标题）',
    excerpt: (conclusion || d.content || '').replace(/\s+/g, ' ').trim(),
    tags: extractTags(d.title),
    sources,
    author: dynamicAuthor(d),
    time: timeAgo(d.createdAt),
    owner: !!d.owner,
    favorited: !!d.favorited,
    favoriteCount: d.favoriteCount || 0,
    raw: d,
  }
}

const cards = computed<DynamicCard[]>(() => list.value.map(toCard))

// ---------- 一问一答卡 ----------

/** 问答卡视图模型：从规范键里拆出「民族 / 疾病 / 方面」，比从标题里猜标签准 */
interface QaCard {
  id: number
  ethnicity: string
  disease: string
  /** 该卡回答的是哪个方面（患病情况 / 危险因素…）；规范键缺失时为空 */
  intent: string
  /** 问题：打包时存的就是合成问句 */
  question: string
  conclusion: string
  sources: string[]
  author: string
  time: string
  owner: boolean
  favorited: boolean
  favoriteCount: number
  raw: DynamicItem
}

function toQaCard(d: DynamicItem): QaCard {
  const { conclusion, sources } = readDetail(d)
  // 规范键「民族|疾病|意图」是发布时写死的，比从标题做实体抽取可靠；
  // 老数据若没有这个键，退回用标题提标签，至少不让标签栏空着
  const parts = (d.qaKey || '').split('|')
  const fallback = extractTags(d.title)
  return {
    id: d.id,
    ethnicity: parts[0] || fallback[0] || '',
    disease: parts[1] || fallback[1] || '',
    intent: parts[2] ? intentLabel(parts[2]) : '',
    question: d.title || '（无标题）',
    conclusion: (conclusion || d.content || '').replace(/\s+/g, ' ').trim(),
    sources,
    author: dynamicAuthor(d),
    time: timeAgo(d.createdAt),
    owner: !!d.owner,
    favorited: !!d.favorited,
    favoriteCount: d.favoriteCount || 0,
    raw: d,
  }
}

const qaCards = computed<QaCard[]>(() => list.value.map(toQaCard))

/**
 * 突出核心答案里的数值（16.0% 这类）。
 *
 * 必须在**转义之前**切分，不能对 `esc()` 的结果跑正则：esc 会把单引号变成 `&#39;`，
 * 其中的 `39` 会被当成数字加粗，把实体拆坏。这里用捕获组 split，奇数下标就是数字段，
 * 各自转义后拼回去。
 */
function emphasizeNumbers(raw: string): string {
  return String(raw || '')
    .split(/(\d+(?:\.\d+)?%?)/)
    .map((seg, i) => (i % 2 === 1 ? `<b class="num">${esc(seg)}</b>` : esc(seg)))
    .join('')
}

/**
 * 卡片预览用的平坦化：去掉 Markdown 的列表标记与标题井号，换行并成空格。
 *
 * 为什么不直接上 `mdToHtml`（弹窗里就是那么做的）：`.qa-a` 靠 `-webkit-line-clamp: 4`
 * 做四行截断，而 line-clamp 只对**行内内容**生效——塞进块级 `div` / `ul` 之后截断失效，
 * 卡片会被一段一段撑高，瀑布流就散了。卡片只负责给个概览，完整渲染留给点开后的弹窗。
 *
 * 标记的识别复用 `listMarker`，不在这里再写一条正则：两处判定一旦漂移，
 * 就会出现「这里剥掉了、那里没剥掉」的残留。
 */
function flattenMarkdown(raw: string): string {
  return String(raw || '')
    .split('\n')
    .map((l) => {
      const t = l.trim().replace(/^#+\s*/, '')
      const mk = listMarker(t)
      return (mk ? mk.rest : t).trim()
    })
    .filter(Boolean)
    .join(' ')
}

/** 搜索关键词（小写）；两个页签共用，客户端过滤，只作用于已加载的页 */
const kw = computed(() => keyword.value.trim().toLowerCase())

const visibleDialogue = computed<DynamicCard[]>(() => {
  const all = cards.value
  const k = kw.value
  if (!k) return all
  return all.filter(
    (c) =>
      c.title.toLowerCase().includes(k) ||
      c.excerpt.toLowerCase().includes(k) ||
      c.tags.some((t) => t.toLowerCase().includes(k)) ||
      c.author.toLowerCase().includes(k),
  )
})

const visibleQa = computed<QaCard[]>(() => {
  const all = qaCards.value
  const k = kw.value
  if (!k) return all
  return all.filter(
    (c) =>
      c.question.toLowerCase().includes(k) ||
      c.conclusion.toLowerCase().includes(k) ||
      c.ethnicity.includes(k) ||
      c.disease.includes(k) ||
      // 方面（患病情况 / 危险因素…）：标签栏不再显示它，但按它搜仍然要能命中
      c.intent.includes(k) ||
      c.author.toLowerCase().includes(k),
  )
})

/** 当前页签有没有内容（空状态据此显示） */
const isEmpty = computed(() => (tab.value === 'qa' ? visibleQa.value : visibleDialogue.value).length === 0)

/**
 * 双列瀑布流：按下标交替分到左右两列。
 *
 * 用「交替分列」而不是 CSS `columns`：`columns` 是**先填满左列再填右列**，
 * 视觉顺序会变成「第 1 条在左上、第 2 条在它下面」，与信息流「左一条右一条」的
 * 阅读顺序不符。交替分列不需要测量高度，两列也会自然接近等高。
 */
function splitColumns<T>(items: T[]): [T[], T[]] {
  const left: T[] = []
  const right: T[] = []
  items.forEach((c, i) => (i % 2 === 0 ? left : right).push(c))
  return [left, right]
}

const columns = computed(() => splitColumns(visibleDialogue.value))
const qaColumns = computed(() => splitColumns(visibleQa.value))

/**
 * 拉取动态。reset=true 取第一页（覆盖），否则用最后一条的 id 作游标取下一页（追加）。
 * 用游标而不是页码：翻页途中若有人发了新动态，页码分页会漏条目或重复，游标不会。
 *
 * 按当前页签的 kind 过滤；**两个页签的游标各自独立**，所以切页签要重置后重新拉第一页。
 */
async function load(reset = true) {
  if (reset) {
    loading.value = true
  } else {
    // 必须把 loading 也算进去：首屏加载还没返回时 list 是空的，
    // 此时触底加载会拿到 before=null 又拉一遍第一页，追加后同一页出现两次
    // （列表短时哨兵本来就在首屏可见，这个竞态必然发生）。
    if (loading.value || loadingMore.value || !hasMore.value) return
    loadingMore.value = true
  }
  const kind = tab.value
  try {
    const before = reset ? null : (list.value[list.value.length - 1]?.id ?? null)
    const page = await listDynamics(PAGE_SIZE, before, kind)
    // 请求期间用户可能已经切了页签：那时这页数据属于上一个页签，丢掉，
    // 否则会把「多轮对话」的卡片塞进「一问一答」列表里
    if (kind !== tab.value) return
    if (reset) {
      list.value = page
    } else {
      // 追加时按 id 去重：即使上游出现意外重复，界面上也不会出现两张一样的卡
      const seen = new Set(list.value.map((d) => d.id))
      list.value = [...list.value, ...page.filter((d) => !seen.has(d.id))]
    }
    hasMore.value = page.length === PAGE_SIZE
  } catch {
    /* 拦截器已统一提示；失败时停止继续加载，避免滚动到底反复请求 */
    hasMore.value = false
  } finally {
    loading.value = false
    loadingMore.value = false
    // 重新观察哨兵：首屏没填满视口时它会一直可见，而 IntersectionObserver 只在
    // 「相交状态变化」时回调——不重新观察就不会再触发，用户也就翻不了下一页。
    if (hasMore.value) reobserve()
  }
}

/** 切页签：清空列表、重置游标、重新拉第一页（两个页签的内容互不相干） */
function switchTab(k: DynamicKind) {
  if (k === tab.value) return
  tab.value = k
  keyword.value = ''
  list.value = []
  hasMore.value = true
  // 地址栏跟着走，刷新或分享链接后仍停在同一页签
  router.replace({ name: 'dynamics', query: k === 'qa' ? { tab: 'qa' } : {} })
  load(true)
}

/** 强制让哨兵重新触发一次相交判定 */
function reobserve() {
  nextTick(() => {
    const el = sentinel.value
    if (!el || !observer) return
    observer.unobserve(el)
    observer.observe(el)
  })
}

async function viewDetail(d: DynamicItem) {
  try {
    detail.value = await getDynamic(d.id)
  } catch {
    detail.value = d
  }
  detailVisible.value = true
}

/**
 * 点作者名 → 进 TA 的个人主页。
 *
 * 自己的动态也带上自己的 userId 走同一条路径，由资料页判断「是不是我」，
 * 这里不做分支——判断逻辑只留一处，免得两边迟早不一致。
 */
function goAuthor(d: DynamicItem) {
  router.push({ name: 'profile', query: { userId: String(d.userId) } })
}

async function toggleFavorite(d: DynamicItem) {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录后再收藏')
    router.push({ name: 'login', query: { redirect: '/dynamics' } })
    return
  }
  busyId.value = d.id
  try {
    if (d.favorited) {
      await removeFavorite(d.id)
      d.favorited = false
      d.favoriteCount = Math.max(0, (d.favoriteCount || 1) - 1)
      ElMessage.success('已取消收藏')
    } else {
      await addFavorite(d.id)
      d.favorited = true
      d.favoriteCount = (d.favoriteCount || 0) + 1
      ElMessage.success('收藏成功，可在个人中心查看')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}

async function removeDynamic(d: DynamicItem) {  try {
    await ElMessageBox.confirm('确定删除这条动态吗？删除后其他用户将无法看到。', '删除动态', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteDynamic(d.id)
    ElMessage.success('动态已删除')
    list.value = list.value.filter((x) => x.id !== d.id)
    if (detail.value && detail.value.id === d.id) detailVisible.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(() => {
  userStore.fetchUser()
  load(true)
  // 哨兵始终渲染，所以挂载后就能观测；提前 200px 触发，滚到底前就取下一页
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting) load(false)
    },
    { rootMargin: '200px' },
  )
  nextTick(() => {
    if (sentinel.value) observer?.observe(sentinel.value)
  })
})

onUnmounted(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <div class="page">
    <AppTopbar />

    <main class="main">
      <div class="head">
        <div class="head-left">
          <h1 class="title">动态</h1>
          <p class="sub">{{ TABS.find((t) => t.key === tab)?.sub }}</p>
        </div>
        <div class="head-right">
          <label class="search">
            <svg class="search-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
              <circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" />
            </svg>
            <input v-model="keyword" type="text" placeholder="搜索标题或标签" />
            <button v-if="keyword" class="search-clear" type="button" title="清空" @click="keyword = ''">×</button>
          </label>
          <button class="btn btn-ghost" type="button" @click="router.push({ name: 'profile' })">个人中心</button>
        </div>
      </div>

      <!-- 社区分栏：多轮对话分享 / 一问一答卡片。写成原生 button 而非 el-tabs，
           与知识库页的页签同一套写法，样式也能直接复用 -->
      <div class="tabs">
        <button
          v-for="t in TABS"
          :key="t.key"
          class="tab"
          :class="{ on: tab === t.key }"
          type="button"
          @click="switchTab(t.key)"
        >{{ t.label }}</button>
      </div>

      <div v-loading="loading" class="feed">
        <!-- 空状态（结构来自 main.css 的 .empty-state） -->
        <div v-if="isEmpty && !loading" class="empty-state">
          <div class="empty-state-art">
            <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="4" width="18" height="16" rx="3" /><path d="M7 9h10M7 13h6" />
            </svg>
          </div>
          <p class="empty-state-title">
            {{ keyword ? '没有找到匹配的动态' : tab === 'qa' ? '还没有一问一答卡片' : '还没有人分享' }}
          </p>
          <p class="empty-state-desc">
            <template v-if="keyword">换个关键词试试，或清空搜索看看全部内容</template>
            <template v-else-if="tab === 'qa'">
              在高级检索页选好「民族 + 疾病 + 方面」，检索后点「分享到一问一答社区」
            </template>
            <template v-else>在智能对话里点右上角的「分享至动态」，把你的问答分享出来</template>
          </p>
          <div v-if="!keyword" class="empty-state-actions">
            <button
              class="btn btn-solid"
              type="button"
              @click="router.push({ name: tab === 'qa' ? 'advanced-search' : 'chat' })"
            >{{ tab === 'qa' ? '去高级检索' : '去发布第一条' }}</button>
          </div>
        </div>

        <!-- ① 多轮对话：双列瀑布流 -->
        <div v-else-if="tab === 'dialogue'" class="masonry">
          <div v-for="(col, ci) in columns" :key="ci" class="col">
            <article
              v-for="c in col"
              :key="c.id"
              class="card"
              @click="viewDetail(c.raw)"
            >
              <!-- 标签：从标题里提取，提不出来就整栏隐藏 -->
              <div v-if="c.tags.length" class="tags">
                <span v-for="t in c.tags" :key="t" class="tag">#{{ t }}</span>
              </div>

              <h2 class="card-title">{{ c.title }}</h2>

              <div class="card-foot">
                <span class="src">
                  <template v-if="c.sources.length">依据《{{ c.sources[0] }}》</template>
                  <template v-else>{{ c.time }}</template>
                </span>
                <span class="read">阅读全文
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M9 6l6 6-6 6" />
                  </svg>
                </span>
              </div>

              <!-- 作者与操作：悬停才显形，平时不抢内容 -->
              <div class="card-actions" @click.stop>
                <button
                  class="author"
                  type="button"
                  :title="`查看「${c.author}」的主页`"
                  @click="goAuthor(c.raw)"
                >{{ c.author }}<span v-if="c.owner" class="mine">我</span></button>
                <span class="acts">
                  <button
                    class="btn btn-sm btn-outline"
                    :class="{ 'is-on': c.favorited }"
                    type="button"
                    :disabled="busyId === c.id"
                    @click="toggleFavorite(c.raw)"
                  >{{ c.favorited ? '已收藏' : '收藏' }}<span v-if="c.favoriteCount" class="cnt">{{ c.favoriteCount }}</span></button>
                  <button v-if="c.owner" class="btn btn-sm btn-outline btn-danger" type="button" @click="removeDynamic(c.raw)">删除</button>
                </span>
              </div>
            </article>
          </div>
        </div>

        <!-- ② 一问一答：同一套瀑布流，卡片换成「标签 + 问题 + 核心答案 + 依据」 -->
        <div v-else class="masonry">
          <div v-for="(col, ci) in qaColumns" :key="ci" class="col">
            <article
              v-for="c in col"
              :key="c.id"
              class="card qa-card"
              @click="viewDetail(c.raw)"
            >
              <!-- 标签栏只放「民族 + 疾病」：这两个是检索这张卡的入口。
                   方面（患病情况 / 危险因素…）不做标签——它已经写在问题里了，
                   再挂一个同义的标签就是同一句话说两遍 -->
              <div class="tags qa-tags">
                <span v-if="c.ethnicity" class="tag">#{{ c.ethnicity }}</span>
                <span v-if="c.disease" class="tag">#{{ c.disease }}</span>
              </div>

              <h2 class="qa-q">{{ c.question }}</h2>

              <!-- 核心答案独立成块并加底色，成为整张卡的视觉落点。
                   只放核心结论——「文献数据说明」「专家行动建议」是完整回答的内容，
                   留给点开后的弹窗，卡片上铺开就没有「一眼看到结论」可言了。
                   内容是 esc 转义后拼的自有标签，无注入风险（见 emphasizeNumbers 注释） -->
              <div v-if="c.conclusion" class="qa-answer">
                <span class="qa-answer-label">核心答案</span>
                <p class="qa-a" v-html="emphasizeNumbers(flattenMarkdown(c.conclusion))"></p>
              </div>

              <div class="card-foot">
                <span class="src">
                  <template v-if="c.sources.length">依据文献《{{ c.sources.join('》《') }}》</template>
                  <template v-else>{{ c.time }}</template>
                </span>
                <span class="read">查看全文
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M9 6l6 6-6 6" />
                  </svg>
                </span>
              </div>

              <div class="card-actions" @click.stop>
                <button
                  class="author"
                  type="button"
                  :title="`查看「${c.author}」的主页`"
                  @click="goAuthor(c.raw)"
                >{{ c.author }}<span v-if="c.owner" class="mine">我</span></button>
                <span class="acts">
                  <button
                    class="btn btn-sm btn-outline"
                    :class="{ 'is-on': c.favorited }"
                    type="button"
                    :disabled="busyId === c.id"
                    @click="toggleFavorite(c.raw)"
                  >{{ c.favorited ? '已收藏' : '收藏' }}<span v-if="c.favoriteCount" class="cnt">{{ c.favoriteCount }}</span></button>
                  <button v-if="c.owner" class="btn btn-sm btn-outline btn-danger" type="button" @click="removeDynamic(c.raw)">删除</button>
                </span>
              </div>
            </article>
          </div>
        </div>

        <!-- 触底哨兵：始终渲染，进入视口就取下一页 -->
        <div ref="sentinel" class="sentinel">
          <span v-if="loadingMore">正在加载…</span>
          <span v-else-if="list.length && !hasMore">— 没有更多了 —</span>
        </div>
      </div>
    </main>

    <!-- 详情：对话式弹窗（组件见 components/DynamicDetailModal.vue） -->
    <DynamicDetailModal v-model="detailVisible" :dynamic="detail" />
  </div>
</template>

<style scoped>
.page { min-height: 100vh; display: flex; flex-direction: column; background: var(--bg); }

.main {
  flex: 1;
  width: 100%;
  max-width: 1080px;
  margin: 0 auto;
  padding: 26px 20px 60px;
}

/* ---------- 页头 ---------- */
.head {
  display: flex; align-items: flex-end; justify-content: space-between;
  gap: 16px; flex-wrap: wrap; margin-bottom: 22px;
}
.title { font-family: var(--serif); font-size: 24px; font-weight: 700; }
.sub { margin-top: 4px; font-size: 13px; color: var(--ink-3); }
.head-right { display: flex; align-items: center; gap: 10px; }

.search {
  display: flex; align-items: center; gap: 8px;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 999px; padding: 7px 14px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.search:focus-within { border-color: var(--clay); box-shadow: 0 0 0 3px var(--clay-soft); }
.search-icon { color: var(--ink-3); flex: none; }
.search input {
  border: 0; outline: none; background: transparent;
  font-family: var(--sans); font-size: 13.5px; color: var(--ink);
  width: 160px;
}
.search input::placeholder { color: var(--ink-3); }
.search-clear {
  border: 0; background: transparent; cursor: pointer; padding: 0 2px;
  font-size: 16px; line-height: 1; color: var(--ink-3);
}
.search-clear:hover { color: var(--ink); }

/* 「个人中心」等按钮的样式来自 main.css 的 .btn 分级 */

/* ---------- 页签（写法与样式对齐知识库页的 .tabs / .tab） ---------- */
.tabs {
  display: flex; gap: 4px; margin-bottom: 20px;
  border-bottom: 1px solid var(--line);
}
.tab {
  border: 0; background: transparent; cursor: pointer;
  font-family: var(--sans); font-size: 14px; color: var(--ink-3);
  padding: 10px 16px; border-bottom: 2px solid transparent;
  transition: 0.15s;
}
.tab:hover { color: var(--ink-2); }
.tab.on { color: var(--clay-deep); font-weight: 600; border-bottom-color: var(--clay); }

/* ---------- 双列瀑布流 ---------- */
.masonry { display: flex; gap: var(--sp-5); align-items: flex-start; }
.col { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: var(--sp-5); }

.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: 20px 20px 18px;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}
.card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-md);
  border-color: var(--line-strong);
}

.tags { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 9px; }
.tag {
  font-size: 11.5px; line-height: 1.6;
  color: var(--sage-deep); background: var(--sage-soft);
  border-radius: 999px; padding: 1px 9px;
}

/* 极简卡片：只有标签 + 标题 + 底部信息，不展示正文摘要 */
.card-title {
  font-family: var(--serif); font-size: 15.5px; font-weight: 700;
  line-height: 1.5; color: var(--ink);
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2;
  -webkit-box-orient: vertical; overflow: hidden;
}

.card-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--line-2);
  font-size: 12px; color: var(--ink-3);
}
.src { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.read {
  flex: none; display: inline-flex; align-items: center; gap: 2px;
  color: var(--clay); font-weight: 500;
}
.card:hover .read { color: var(--clay-deep); }

/* 作者与操作：平时收起，悬停才展开，避免卡片信息过载 */
/* 作者与操作：**常驻显示**。
   此前是靠 hover 才展开（max-height 0 → 40px），触摸屏用户和不用鼠标的人
   根本发现不了「收藏」——而这是动态页最核心的操作。改成常驻后，
   卡片右下角始终能看到收藏按钮，也不必再为它做展开动画。 */
.card-actions {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  margin-top: 10px;
}
/* 作者名可点：跳到 TA 的个人主页。
   自己发的动态也走同一条路径（带上自己的 userId），由资料页判断「是不是我」——
   判断逻辑只放在资料页一处，这里不用分支。 */
.author {
  border: 0; background: none; padding: 0;
  font-family: var(--sans); font-size: 12px; color: var(--ink-3);
  cursor: pointer; text-align: left;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  transition: color 0.15s;
}
.author:hover { color: var(--clay-deep); text-decoration: underline; }
.mine {
  margin-left: 5px; font-size: 10.5px; color: var(--clay-deep);
  background: var(--clay-soft); border-radius: 999px; padding: 0 6px;
}
.acts { flex: none; display: flex; gap: 6px; }
.cnt { margin-left: 3px; opacity: 0.75; }

/* 空状态的结构与排版来自 main.css 的 .empty-state，这里不再重复定义 */

/* ---------- 一问一答卡：做成「知识卡片」 ----------
   与对话分享卡共用 .card 骨架，但要有自己的辨识度——两者原先只是换了换内容区，
   并排看几乎分不出哪张是对话摘要、哪张是一条可检索的结论。这里给问答卡一套独立的
   视觉语言：左侧主色竖条（对话卡没有）+ 答案独立成块加底色 + 数值放大。
   整张卡读起来是「条目 → 问题 → 结论 → 出处」，而不是一段对话的摘要。 */
.qa-card {
  /* 竖条用 inset 阴影画，不用 border-left：border 会算进盒宽，
     与 .card 原有的 padding 叠在一起会让两种卡的内容左边界差 3px */
  box-shadow: inset 3px 0 0 var(--clay), var(--shadow-sm);
  padding-left: 22px;
}
/* 悬停时保留竖条（.card:hover 的阴影会把它盖掉，所以这里必须重写一遍）。
   与 .card:hover 同特异性，靠源码顺序取胜——本块在它之后 */
.qa-card:hover {
  box-shadow: inset 3px 0 0 var(--clay-deep), var(--shadow-md);
}

/* 标签栏：同为 #标签，但换成描边样式（对话卡是实底绿丸）。
   同一页里两种标签样式并存，本身就在提示「这是两类内容」 */
.qa-tags { margin-bottom: 10px; }
.qa-tags .tag {
  color: var(--clay-deep);
  background: transparent;
  border: 1px solid var(--accent-line);
  font-weight: 500;
}

.qa-q {
  font-family: var(--serif); font-size: 15.5px; font-weight: 700;
  line-height: 1.5; color: var(--ink);
  /* 最多两行：问题就是这张卡的标题，铺开三四行会把答案挤到看不见的地方 */
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2;
  -webkit-box-orient: vertical; overflow: hidden;
}

/* 核心答案：独立成块 + 底色，与卡片的白色底分开，视线自然落在这里——
   这张卡要回答的就是这一句 */
.qa-answer {
  margin-top: 10px;
  background: var(--clay-soft);
  border-radius: var(--r-sm);
  padding: 9px 13px 11px;
}
.qa-answer-label {
  display: block;
  font-size: 10.5px; letter-spacing: 0.08em;
  color: var(--clay-deep); opacity: 0.8;
  margin-bottom: 3px;
}
.qa-a {
  font-size: 14px; line-height: 1.75; color: var(--ink);
  display: -webkit-box; -webkit-line-clamp: 4; line-clamp: 4;
  -webkit-box-orient: vertical; overflow: hidden;
}
/* 核心数值：放大到比正文大一号并压上主色，扫一眼就能抓到「16.0%」。
   tabular-nums 让并排的数字等宽，否则「16.0%」和「9%」看起来会一宽一窄 */
.qa-a .num {
  font-size: 1.35em; font-weight: 700; color: var(--clay-deep);
  font-variant-numeric: tabular-nums; line-height: 1.2;
}

/* 依据文献是知识卡的一等公民（没有出处的结论在这类产品里没有说服力），
   所以比对话卡那行浅灰摘要深一档 */
.qa-card .card-foot .src { color: var(--ink-2); }

/* 触底哨兵：给个最小高度才有观测意义 */
.sentinel {
  min-height: 48px; display: grid; place-items: center;
  font-size: 12.5px; color: var(--ink-3); padding: 16px 0 0;
}

/* ---------- 窄屏：瀑布流降为单列 ---------- */
@media (max-width: 720px) {
  .masonry { gap: 14px; }
  .col { gap: 14px; }
  .search input { width: 110px; }
}
@media (max-width: 520px) {
  .main { padding: 20px 16px 50px; }
  /* 单列：把两列内容按原顺序合并回来，避免「先看完全部左列再看右列」 */
  .masonry { display: block; }
  .col { display: contents; }
  .card { margin-bottom: 14px; }
}
@media (prefers-reduced-motion: reduce) {
  .card { transition: none; }
  .card:hover { transform: none; }
}
</style>
