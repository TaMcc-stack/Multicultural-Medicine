<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { avatarUrlOf } from '@/api/auth'
import AppTopbar from '@/components/AppTopbar.vue'
import { listConversations } from '@/api/chat'
import type { Conversation } from '@/api/chat'
import { deleteDynamic, dynamicAuthor, getDynamic, listMyDynamics, listUserDynamics, timeAgo } from '@/api/dynamic'
import type { DynamicItem } from '@/api/dynamic'
import {
  addConversationFavorite,
  addFavorite,
  listConversationFavoriteIds,
  listConversationFavorites,
  listFavoriteIds,
  listFavorites,
  removeConversationFavorite,
  removeFavorite,
} from '@/api/favorite'
import DynamicDetailModal from '@/components/DynamicDetailModal.vue'
import {
  addSearchFavorite,
  listSearchFavoriteIds,
  listSearchFavorites,
  listSearchHistory,
  parseSnapshot,
  removeSearchFavorite,
  searchHistoryTitle,
} from '@/api/searchHistory'
import type { SearchHistoryItem } from '@/api/searchHistory'
import { publishQaCard } from '@/api/dynamic'
import { packQaCard, publishConversationById } from '@/utils/shareConversation'
import type { ShareCitation, ShareTurn } from '@/utils/shareConversation'
import { avatarInitial as avatarInitialChar, avatarStyleOf, AVATAR_PRESETS } from '@/utils/avatar'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/**
 * 访客模式：URL 带 `?userId=<别人>` 时进入，**只看得到对方的公开分享**。
 *
 * 对话与收藏都是私有数据，不对外展示。也不存在独立的「用户资料」接口——
 * 昵称和头像从 TA 的动态里取（动态 DTO 自带作者信息），所以访客看到的资料区是只读的。
 * 没带 userId（从导航栏进来）一律算看自己。
 */
const viewingUserId = computed(() => {
  const n = Number(route.query.userId)
  return Number.isFinite(n) && n > 0 ? n : null
})
const isSelf = computed(
  () => viewingUserId.value == null || viewingUserId.value === userStore.user?.id,
)

const userInfo = computed(() => userStore.user)

/** 访客资料：昵称与头像都取自 TA 的第一条动态 */
const guestProfile = computed(() => {
  const first = guestShares.value[0]
  return {
    nickname: first ? dynamicAuthor(first) : `用户${viewingUserId.value ?? ''}`,
    avatar: first?.avatar ?? null,
  }
})

const displayName = computed(() =>
  isSelf.value ? userStore.displayName || '用户' : guestProfile.value.nickname,
)
const username = computed(() => userStore.user?.username || '—')
const userId = computed(() => (isSelf.value ? (userStore.user?.id ?? '—') : (viewingUserId.value ?? '—')))
const bio = computed(() => (isSelf.value ? userStore.user?.bio || '' : ''))

/** 头像上的字：昵称首字（去掉首尾空白，空则回退成「用」） */
const avatarInitial = computed(() => avatarInitialChar(displayName.value))

const avatarStyle = computed(() =>
  avatarStyleOf(isSelf.value ? userStore.user?.avatar : guestProfile.value.avatar),
)

/** 自定义头像的地址；为空表示用的是「首字 + 配色」。访客不展示对方的上传头像 */
const avatarUrl = computed(() => (isSelf.value ? avatarUrlOf(userStore.user) : ''))

// ---------- 自定义头像上传 ----------
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const MAX_AVATAR_BYTES = 2 * 1024 * 1024

/**
 * 客户端先挡一道（类型 + 大小），给出即时反馈；
 * 服务端还会按文件头再校验一次——前端校验只是体验，不是防线。
 */
async function onPickAvatarFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 清空，保证「同一张图再选一次」也能触发 change
  if (!file) return
  if (!/^image\/(png|jpeg|webp)$/.test(file.type)) {
    ElMessage.warning('只支持 PNG / JPG / WebP 格式的图片')
    return
  }
  if (file.size > MAX_AVATAR_BYTES) {
    ElMessage.warning('图片不能超过 2MB')
    return
  }
  uploading.value = true
  try {
    await userStore.uploadAvatar(file)
    pickerOpen.value = false
    ElMessage.success('头像已更新')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '上传失败')
  } finally {
    uploading.value = false
  }
}

// ---------- 资料编辑 ----------
type EditField = 'nickname' | 'bio' | null
const editing = ref<EditField>(null)
const draftNickname = ref('')
const draftBio = ref('')
const saving = ref(false)
const pickerOpen = ref(false)

/** 昵称/简介的长度上限，与后端 AuthService 的校验保持一致 */
const NICKNAME_MAX = 20
const BIO_MAX = 200

function startEdit(field: Exclude<EditField, null>) {
  pickerOpen.value = false
  editing.value = field
  if (field === 'nickname') draftNickname.value = displayName.value
  else draftBio.value = bio.value
}

function cancelEdit() {
  editing.value = null
}

async function save(field: Exclude<EditField, null>) {
  // 昵称是「回车或失焦」都触发保存，两个事件会接连打进来；用 saving 挡掉第二次
  if (saving.value) return
  const nextNickname = field === 'nickname' ? draftNickname.value.trim() : displayName.value
  const nextBio = field === 'bio' ? draftBio.value.trim() : bio.value
  if (!nextNickname) {
    ElMessage.warning('昵称不能为空')
    return
  }
  if (nextNickname === displayName.value && nextBio === bio.value) {
    editing.value = null
    return
  }
  saving.value = true
  try {
    await userStore.saveProfile({
      nickname: nextNickname,
      avatar: userStore.user?.avatar || 'sage',
      bio: nextBio,
    })
    editing.value = null
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

/** 选头像：立即保存，不再多点一次「保存」 */
async function pickAvatar(key: string) {
  if (key === (userStore.user?.avatar || 'sage')) {
    pickerOpen.value = false
    return
  }
  saving.value = true
  try {
    await userStore.saveProfile({
      nickname: displayName.value,
      avatar: key,
      bio: bio.value,
    })
    pickerOpen.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 内容列表 ----------
type TabKey = 'chat' | 'shares' | 'favorites'
const tab = ref<TabKey>('chat')

const MY_TABS: { key: TabKey; label: string }[] = [
  { key: 'chat', label: '我的对话' },
  { key: 'shares', label: '我的分享' },
  { key: 'favorites', label: '我的收藏' },
]
/** 访客只有一个 Tab：对话和收藏是私有数据，不对外展示 */
const GUEST_TABS: { key: TabKey; label: string }[] = [{ key: 'shares', label: 'TA 的分享' }]
const tabs = computed(() => (isSelf.value ? MY_TABS : GUEST_TABS))

const conversations = ref<Conversation[]>([])
const loadingChats = ref(false)
/**
 * 「我的对话」下的两个子来源。
 *
 * 对话是自由问答留下的多轮记录，高级检索是按「民族 + 疾病 + 方面」点出来的结构化结果——
 * 两者的标题形状、能做的事都不同（对话能续聊，检索能一键跳回当时的结果），
 * 混在一个列表里用户分不清哪些能点什么，所以分开而不是只加个来源标签。
 * 两边都提供【收藏】【分享】——收藏各写各的私有书签表，分享各发各的动态类型。
 */
const chatSource = ref<'ai' | 'search'>('ai')
const searchHistory = ref<SearchHistoryItem[]>([])
const loadingSearchHistory = ref(false)
const searchBusyId = ref<number | null>(null)
/** 已收藏的会话 id —— 与「我的收藏」Tab 是同一份私有书签 */
const favConvIds = ref<Set<number>>(new Set())
/** 已收藏的检索记录 id —— 同上，第三种私有书签 */
const favSearchIds = ref<Set<number>>(new Set())
/** 已发布成动态的会话 id，用于按钮的「已分享」状态回显 */
const sharedConvIds = ref<Set<number>>(new Set())
/** 已发布成一问一答卡片的「民族|疾病|方面」键，用于检索记录「已分享」回显 */
const sharedQaKeys = ref<Set<string>>(new Set())
/** 正在操作的行，避免连点 */
const chatBusyId = ref<number | null>(null)
const shares = ref<DynamicItem[]>([])
const loadingShares = ref(false)
/** 访客看到的「TA 的分享」（与 shares 分开存：切进切出时两边状态不会互相污染） */
const guestShares = ref<DynamicItem[]>([])
const loadingGuest = ref(false)
/** 我收藏的对话（私有书签，来自对话页右上角的「收藏」） */
const favConversations = ref<Conversation[]>([])
/** 我收藏的动态（来自动态页对他人分享的收藏）——与上面是两回事，分开显示 */
const favorites = ref<DynamicItem[]>([])
/** 我收藏的检索记录（第三种私有书签，与上面两种都不同） */
const favSearchHistory = ref<SearchHistoryItem[]>([])
/** 我收藏过的动态 id，供「我的分享」里的收藏按钮回显 */
const favDynIds = ref<Set<number>>(new Set())
const loadingFavorites = ref(false)

/** 统计数字：列表已全部加载，前端算 length 即可，无需额外的计数接口 */
const stats = computed(() => {
  // 访客只显示公开的那一项——把对方有多少对话/收藏暴露出来是隐私问题
  if (!isSelf.value) {
    return [{ key: 'shares' as TabKey, label: 'TA 的分享', value: guestShares.value.length, loading: loadingGuest.value }]
  }
  return [
    // 「我的对话」下分对话与检索两个来源，统计数字也合起来算——与下面「我的收藏」同理，
    // 和该 Tab 里两个子来源的徽标之和保持一致，免得统计说 5 条、点进去却是 8 条
    {
      key: 'chat' as TabKey,
      label: '我的对话',
      value: conversations.value.length + searchHistory.value.length,
      loading: loadingChats.value || loadingSearchHistory.value,
    },
    { key: 'shares' as TabKey, label: '我的分享', value: shares.value.length, loading: loadingShares.value },
    // 「我的收藏」= 收藏的对话 + 收藏的动态 + 收藏的检索，三个列表都是收藏，合起来计数才符合直觉
    {
      key: 'favorites' as TabKey,
      label: '我的收藏',
      value: favConversations.value.length + favorites.value.length + favSearchHistory.value.length,
      loading: loadingFavorites.value,
    },
  ]
})

const detailVisible = ref(false)
const detail = ref<DynamicItem | null>(null)
const busyId = ref<number | null>(null)

async function loadChats() {
  loadingChats.value = true
  // 五个请求各自独立处理失败，不让一个的异常把其他状态也清掉
  const [convRes, favRes, mineRes, favSearchRes, favDynRes] = await Promise.allSettled([
    listConversations(),
    listConversationFavoriteIds(),
    listMyDynamics(100),
    listSearchFavoriteIds(),
    listFavoriteIds(),
  ])
  conversations.value = convRes.status === 'fulfilled' ? convRes.value : []
  favConvIds.value = new Set(favRes.status === 'fulfilled' ? favRes.value : [])
  const mine = mineRes.status === 'fulfilled' ? mineRes.value : []
  sharedConvIds.value = new Set(
    mine.map((d) => d.sourceConversationId).filter((x): x is number => x != null),
  )
  // 问答卡的「已分享」靠 qaKey 回显；对话类没有 qaKey，天然被滤掉，两套状态互不干扰
  sharedQaKeys.value = new Set(mine.map((d) => d.qaKey).filter((x): x is string => !!x))
  favSearchIds.value = new Set(favSearchRes.status === 'fulfilled' ? favSearchRes.value : [])
  favDynIds.value = new Set(favDynRes.status === 'fulfilled' ? favDynRes.value : [])
  loadingChats.value = false
}

/** 收藏 / 取消收藏这条对话（只写私有书签，不会公开发布） */
async function toggleChatFavorite(c: Conversation) {
  chatBusyId.value = c.id
  try {
    const next = new Set(favConvIds.value)
    if (next.has(c.id)) {
      await removeConversationFavorite(c.id)
      next.delete(c.id)
      ElMessage.success('已取消收藏')
    } else {
      await addConversationFavorite(c.id)
      next.add(c.id)
      ElMessage.success('已收藏，可在「我的收藏」查看')
    }
    favConvIds.value = next
    loadFavorites()   // 「我的收藏」Tab 跟着刷新，避免两处状态不一致
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    chatBusyId.value = null
  }
}

/** 把这整段对话分享到动态广场（后端按会话幂等，重复点是更新而非新增） */
async function shareChat(c: Conversation) {
  chatBusyId.value = c.id
  try {
    await publishConversationById(c.id, c.title)
    sharedConvIds.value = new Set([...sharedConvIds.value, c.id])
    ElMessage.success('已成功分享到动态')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '分享失败')
  } finally {
    chatBusyId.value = null
  }
}

/**
 * 收藏 / 取消收藏一条自己分享过的动态。
 *
 * 与「动态」页收藏别人的分享写的是同一张表（`user_favorite`），所以这里收藏完，
 * 「我的收藏」Tab 里就会以「收藏的动态」那一组出现——不需要再多一类收藏。
 */
async function toggleDynFavorite(d: DynamicItem) {
  busyId.value = d.id
  try {
    const next = new Set(favDynIds.value)
    const on = next.has(d.id)
    if (on) {
      await removeFavorite(d.id)
      next.delete(d.id)
    } else {
      await addFavorite(d.id)
      next.add(d.id)
    }
    favDynIds.value = next
    // 计数就地改：它显示在同一个条目上，不跟着动的话「已收藏但被收藏数没变」看着像没生效
    d.favoriteCount = Math.max(0, (d.favoriteCount ?? 0) + (on ? -1 : 1))
    ElMessage.success(on ? '已取消收藏' : '已收藏，可在「我的收藏」查看')
    loadFavorites()   // 「我的收藏」Tab 跟着刷新，避免两处状态不一致
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}

async function loadShares() {
  loadingShares.value = true
  try {
    shares.value = await listMyDynamics(100)
  } catch {
    /* 拦截器已统一提示 */
  } finally {
    loadingShares.value = false
  }
}

/** 访客模式：只拉对方的公开分享 */
async function loadGuest() {
  const uid = viewingUserId.value
  if (uid == null) return
  loadingGuest.value = true
  try {
    guestShares.value = await listUserDynamics(uid, 100)
  } catch {
    /* 拦截器已统一提示 */
    guestShares.value = []
  } finally {
    loadingGuest.value = false
  }
}

/** 「分享」Tab 的数据源：自己看自己的，访客看对方的 */
const sharesForView = computed(() => (isSelf.value ? shares.value : guestShares.value))
const loadingSharesForView = computed(() =>
  isSelf.value ? loadingShares.value : loadingGuest.value,
)

async function loadFavorites() {
  loadingFavorites.value = true
  // 三类收藏来自不同接口，各自独立处理失败，不让一个的异常把另外两个也清空
  const [convRes, dynRes, searchRes] = await Promise.allSettled([
    listConversationFavorites(),
    listFavorites(),
    listSearchFavorites(),
  ])
  favConversations.value = convRes.status === 'fulfilled' ? convRes.value : []
  favorites.value = dynRes.status === 'fulfilled' ? dynRes.value : []
  favSearchHistory.value = searchRes.status === 'fulfilled' ? searchRes.value : []
  loadingFavorites.value = false
}

/** 取消收藏一条检索记录（只删书签，不动检索记录本身） */
async function cancelSearchFavorite(h: SearchHistoryItem) {
  busyId.value = h.id
  try {
    await removeSearchFavorite(h.id)
    favSearchHistory.value = favSearchHistory.value.filter((x) => x.id !== h.id)
    favSearchIds.value = new Set([...favSearchIds.value].filter((id) => id !== h.id))
    ElMessage.success('已取消收藏')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}

/** 取消收藏一段对话（只删书签，不动会话本身） */
async function cancelConversationFavorite(c: Conversation) {
  busyId.value = c.id
  try {
    await removeConversationFavorite(c.id)
    favConversations.value = favConversations.value.filter((x) => x.id !== c.id)
    ElMessage.success('已取消收藏')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}

function openConversation(c: Conversation) {
  router.push({ name: 'chat', query: { conv: String(c.id) } })
}

/** 高级检索的历史（按最后一次检索时间倒序，服务端排好） */
async function loadSearchHistory() {
  loadingSearchHistory.value = true
  try {
    searchHistory.value = await listSearchHistory(50)
  } catch {
    // 拉不到只让这一个子 Tab 空着，不影响对话列表（两者是独立接口）
    searchHistory.value = []
  } finally {
    loadingSearchHistory.value = false
  }
}

/**
 * 查看一条检索记录的结果：跳到高级检索页并带上记录 id。
 *
 * 带 id 而不是在这里弹窗，是因为检索结果的呈现（答案正文、证据原文定位、未命中说明）
 * 整套都在高级检索页里，搬一份弹窗出来就是第二份实现。那边按 `?h=<id>` 还原快照。
 */
function openSearchHistory(h: SearchHistoryItem) {
  router.push({ name: 'advanced-search', query: { h: String(h.id) } })
}

/**
 * 收藏 / 取消收藏一条检索记录（只写私有书签，不会公开发布）。
 * 与「收藏对话」同一套语义，但写的是另一张表——见 `/api/search-history/{id}/favorite`。
 */
async function toggleSearchFavorite(h: SearchHistoryItem) {
  searchBusyId.value = h.id
  try {
    const next = new Set(favSearchIds.value)
    if (next.has(h.id)) {
      await removeSearchFavorite(h.id)
      next.delete(h.id)
      ElMessage.success('已取消收藏')
    } else {
      await addSearchFavorite(h.id)
      next.add(h.id)
      ElMessage.success('已收藏，可在「我的收藏」查看')
    }
    favSearchIds.value = next
    loadFavorites()   // 「我的收藏」Tab 跟着刷新，避免两处状态不一致
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    searchBusyId.value = null
  }
}

/** 问答卡的幂等键「民族|疾病|意图码」。与高级检索页发布时用的**逐字一致**——后端按它跨用户去重。 */
function qaKeyOf(h: SearchHistoryItem) {
  return `${h.ethnicity}|${h.disease}|${h.intent}`
}

/**
 * 哪些检索记录有可分享的答案（未命中的记录只存了一句原因，没有卡片可发）。
 *
 * 预先算成一个集合，而不是在模板里对每一行调一次 `parseSnapshot`：那是 JSON.parse，
 * 每次渲染 × 每行 × 三个绑定（disabled / title / 文案）会把大快照反复解析上百遍。
 */
const shareableSearchIds = computed(() => {
  const ids = new Set<number>()
  for (const h of searchHistory.value) {
    if (parseSnapshot(h)?.answer) ids.add(h.id)
  }
  return ids
})

/**
 * 把一条检索记录分享到「一问一答」社区。
 *
 * 从**存档的快照**重建那一轮问答，而不是重新检索一遍：重新检索既慢，答案又会因为模型的
 * 随机性与用户当时看到的那份不一样——分享出去的应该是他看过的那个。
 * 打包走与高级检索页同一个 `packQaCard`，否则同一条记录从两个入口发出去会长得不一样。
 *
 * 标题用 `searchHistoryTitle`（「白族 + 糖尿病 + 患病率」）而不是高级检索页那种
 * 「白族糖尿病的患病率」：那个问句要用服务端下发的 `intentPhrases` 拼，这里没有那份词表，
 * 为一句标题去拉一次词表不值当。卡片按 qaKey 去重，同一条记录只会有一张卡，
 * 标题以最后一次分享的入口为准。
 */
async function shareSearch(h: SearchHistoryItem) {
  const snap = parseSnapshot(h)
  if (!snap?.answer) return
  searchBusyId.value = h.id
  try {
    const citations: ShareCitation[] = (snap.evidence || []).map((e) => ({
      title: e.title,
      quote: e.fragment,
      id: e.id,
      startOffset: e.startOffset,
      endOffset: e.endOffset,
      matchedTerms: e.matchedTerms,
      sourceLevel: e.sourceLevel,
      sourceOrg: e.sourceOrg ?? null,
    }))
    const turn: ShareTurn = {
      question: searchHistoryTitle(h),
      answer: snap.answer as unknown as Record<string, unknown>,
      plain: snap.answer.conclusion || '',
      citations,
    }
    await publishQaCard({ qaKey: qaKeyOf(h), ...packQaCard(turn) })
    sharedQaKeys.value = new Set([...sharedQaKeys.value, qaKeyOf(h)])
    ElMessage.success('已分享到「一问一答」社区')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '分享失败，请稍后重试')
  } finally {
    searchBusyId.value = null
  }
}

async function viewDetail(d: DynamicItem) {
  try {
    detail.value = await getDynamic(d.id)
  } catch {
    detail.value = d
  }
  detailVisible.value = true
}

async function removeShare(d: DynamicItem) {
  try {
    await ElMessageBox.confirm('确定删除这条分享吗？删除后动态主页将不再展示。', '删除分享', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteDynamic(d.id)
    ElMessage.success('已删除')
    shares.value = shares.value.filter((x) => x.id !== d.id)
    if (detail.value && detail.value.id === d.id) detailVisible.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function cancelFavorite(d: DynamicItem) {
  busyId.value = d.id
  try {
    await removeFavorite(d.id)
    favorites.value = favorites.value.filter((x) => x.id !== d.id)
    if (detail.value && detail.value.id === d.id) detail.value.favorited = false
    ElMessage.success('已取消收藏')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}

function excerpt(d: DynamicItem): string {
  const t = (d.content || '').replace(/\s+/g, ' ').trim()
  return t.length <= 110 ? t : t.slice(0, 110) + '…'
}

function logout() {
  userStore.logout().then(() => {
    ElMessage.success('已退出登录')
    router.push({ name: 'home' })
  })
}

/** 点统计数字 = 切到对应 tab */
function gotoStat(key: TabKey) {
  tab.value = key
}

watch(tab, (v) => {
  // 访客只有「分享」一个 Tab，数据在挂载时已经拉好了
  if (!isSelf.value) return
  if (v === 'chat') {
    if (!conversations.value.length) loadChats()
    // 两个子来源的计数要显示在子 Tab 上，所以进 Tab 就得都有值
    if (!searchHistory.value.length) loadSearchHistory()
  }
  if (v === 'shares' && !shares.value.length) loadShares()
  if (v === 'favorites' && !favorites.value.length) loadFavorites()
})

onMounted(async () => {
  // 先等用户信息到位再判断「是自己还是访客」：user 还没加载时 userId 是 undefined，
  // 会让 isSelf 误判成 false，先按访客拉一次数据、再翻回自己——白跑一趟还闪一下
  await userStore.fetchUser()
  if (isSelf.value) {
    // 三个列表都要加载：统计数字依赖它们的长度
    loadChats()
    loadShares()
    loadFavorites()
    loadSearchHistory()
  } else {
    tab.value = 'shares'   // 访客只有这一个 Tab
    loadGuest()
  }
})

/**
 * 同一个组件内切换查看对象。
 *
 * ProfileView 不带 keep-alive，但「路由 name 相同、只有 query 变」时 Vue Router 会
 * 复用组件实例，onMounted 不会再跑——所以这里必须自己响应一次，
 * 否则从 A 的资料页跳到 B 的资料页会停在前一个人的数据上。
 */
watch(
  () => route.query.userId,
  () => {
    if (isSelf.value) {
      tab.value = 'chat'
      chatSource.value = 'ai'   // 换了个人，子来源也退回默认，别停在上一个人的检索记录上
      loadChats()
      loadShares()
      loadFavorites()
      loadSearchHistory()
    } else {
      tab.value = 'shares'
      guestShares.value = []
      loadGuest()
    }
  },
)
</script>

<template>
  <div class="page">
    <AppTopbar />

    <main class="main">
      <!-- ① 资料卡 -->
      <section class="card profile">
        <div class="profile-top">
          <div class="avatar-wrap">
            <button
              class="avatar"
              type="button"
              :class="{ readonly: !isSelf }"
              :style="avatarUrl ? undefined : avatarStyle"
              :disabled="!isSelf"
              @click="pickerOpen = !pickerOpen"
            >
              <img v-if="avatarUrl" class="avatar-img" :src="avatarUrl" :alt="displayName" />
              <span v-else class="avatar-text">{{ avatarInitial }}</span>
              <span v-if="isSelf" class="avatar-mask">更换</span>
            </button>
            <div v-if="pickerOpen" class="picker">
              <div class="picker-title">选择头像配色</div>
              <div class="picker-grid">
                <button
                  v-for="p in AVATAR_PRESETS"
                  :key="p.key"
                  class="swatch"
                  :class="{ on: !avatarUrl && (userStore.user?.avatar || 'sage') === p.key }"
                  type="button"
                  :title="p.label"
                  :style="{ background: `linear-gradient(150deg, ${p.from}, ${p.to})` }"
                  @click="pickAvatar(p.key)"
                />
              </div>
              <button class="btn btn-sm btn-soft btn-rect btn-block upload-btn"
                type="button" :disabled="uploading" @click="fileInput?.click()">
                {{ uploading ? '上传中…' : '上传自定义图片' }}
              </button>
              <p class="picker-hint">支持 PNG / JPG / WebP，不超过 2MB</p>
              <input
                ref="fileInput"
                class="file-input"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                @change="onPickAvatarFile"
              />
            </div>
          </div>

          <div class="profile-main">
            <!-- 昵称：点铅笔进入编辑，回车/失焦保存，Esc 取消 -->
            <div class="name-row">
              <template v-if="editing === 'nickname'">
                <input
                  v-model="draftNickname"
                  class="name-input"
                  :maxlength="NICKNAME_MAX"
                  autofocus
                  @keyup.enter="save('nickname')"
                  @keyup.esc="cancelEdit"
                  @blur="save('nickname')"
                />
                <span class="counter">{{ draftNickname.length }}/{{ NICKNAME_MAX }}</span>
              </template>
              <template v-else>
                <h1 class="name">{{ displayName }}</h1>
                <button v-if="isSelf" class="btn btn-sm btn-icon btn-outline icon-edit" type="button" title="修改昵称" @click="startEdit('nickname')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>
                </button>
              </template>
            </div>

            <div class="meta-row">
              <!-- 访客不知道对方的用户名（资料接口不存在），只显示 ID -->
              <template v-if="isSelf">
                <span class="meta">@{{ username }}</span>
                <span class="dot">·</span>
              </template>
              <span class="meta">用户 ID {{ userId }}</span>
            </div>

            <!-- 简介：点铅笔编辑，显式保存 -->
            <div class="bio">
              <template v-if="editing === 'bio'">
                <textarea
                  v-model="draftBio"
                  class="bio-input"
                  rows="3"
                  :maxlength="BIO_MAX"
                  placeholder="写点什么介绍自己，比如你关注的民族或健康话题"
                />
                <div class="bio-actions">
                  <span class="counter">{{ draftBio.length }}/{{ BIO_MAX }}</span>
                  <button class="btn btn-sm btn-outline" type="button" :disabled="saving" @click="cancelEdit">取消</button>
                  <button class="btn btn-sm btn-solid" type="button" :disabled="saving" @click="save('bio')">
                    {{ saving ? '保存中…' : '保存' }}
                  </button>
                </div>
              </template>
              <template v-else>
                <p v-if="bio" class="bio-text">{{ bio }}</p>
                <p v-else class="bio-empty">{{ isSelf ? '还没有个人简介' : 'TA 还没有填写简介' }}</p>
                <button v-if="isSelf" class="btn btn-sm btn-icon btn-outline icon-edit" type="button" title="修改简介" @click="startEdit('bio')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>
                </button>
              </template>
            </div>
          </div>
        </div>

        <!-- 统计：点一下切到对应列表 -->
        <div class="stats">
          <button v-for="s in stats" :key="s.key" class="stat" type="button" @click="gotoStat(s.key)">
            <span class="stat-num">{{ s.loading ? '—' : s.value }}</span>
            <span class="stat-label">{{ s.label }}</span>
          </button>
        </div>
      </section>

      <!-- ② 内容卡 -->
      <section class="card panel">
        <div class="tabs">
          <button
            v-for="t in tabs"
            :key="t.key"
            class="tab"
            :class="{ on: tab === t.key }"
            type="button"
            @click="tab = t.key"
          >{{ t.label }}</button>
        </div>

        <!-- 我的对话：两种来源分两个子 Tab -->
        <div v-if="tab === 'chat'" class="panel-body">
          <div class="subtabs" role="tablist">
            <button
              class="subtab"
              :class="{ on: chatSource === 'ai' }"
              type="button"
              role="tab"
              :aria-selected="chatSource === 'ai'"
              @click="chatSource = 'ai'"
            >🗣️ 智能对话<span class="subtab-num">{{ conversations.length }}</span></button>
            <button
              class="subtab"
              :class="{ on: chatSource === 'search' }"
              type="button"
              role="tab"
              :aria-selected="chatSource === 'search'"
              @click="chatSource = 'search'"
            >🔍 高级检索<span class="subtab-num">{{ searchHistory.length }}</span></button>
          </div>

          <!-- 来源一：智能对话（多轮问答留下的会话） -->
          <div v-if="chatSource === 'ai'" v-loading="loadingChats" class="sub-body">
            <div v-if="!conversations.length && !loadingChats" class="empty-state empty-state-sm">
              <p class="empty-state-title">还没有对话记录</p>
              <p class="empty-state-desc">去<button class="btn btn-link" type="button" @click="router.push({ name: 'chat' })">智能对话</button>问一个吧</p>
            </div>
            <div v-for="c in conversations" :key="c.id" class="item">
              <div class="item-main">
                <div class="item-title">{{ c.title }}</div>
                <div class="item-meta">{{ c.messageCount ?? 0 }} 条消息 · {{ timeAgo(c.updatedAt) }}</div>
              </div>
              <!-- 三个动作并列：管理（收藏）/ 分享 / 回顾（查看） -->
              <button
                class="btn btn-sm btn-outline"
                :class="{ 'is-on': favConvIds.has(c.id) }"
                type="button"
                :disabled="chatBusyId === c.id"
                :title="favConvIds.has(c.id) ? '取消收藏' : '收藏这段对话（仅自己可见）'"
                @click="toggleChatFavorite(c)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round">
                  <path d="M12 4l2.6 5.4 5.9.8-4.3 4.1 1 5.9-5.2-2.8-5.2 2.8 1-5.9L3.5 10.2l5.9-.8z" />
                </svg>
                {{ favConvIds.has(c.id) ? '已收藏' : '收藏' }}
              </button>
              <button
                class="btn btn-sm btn-outline btn-sage"
                :class="{ 'is-on': sharedConvIds.has(c.id) }"
                type="button"
                :disabled="chatBusyId === c.id"
                :title="sharedConvIds.has(c.id) ? '再次分享会更新已发布的动态' : '把整段对话分享到动态'"
                @click="shareChat(c)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7" /><path d="M12 15V3M8 7l4-4 4 4" />
                </svg>
                {{ sharedConvIds.has(c.id) ? '已分享' : '分享' }}
              </button>
              <button class="btn btn-sm btn-outline" type="button" @click="openConversation(c)">查看</button>
            </div>
          </div>

          <!-- 来源二：高级检索（「民族 + 疾病 + 方面」的记录，标题由槽位拼出） -->
          <div v-else v-loading="loadingSearchHistory" class="sub-body">
            <div v-if="!searchHistory.length && !loadingSearchHistory" class="empty-state empty-state-sm">
              <p class="empty-state-title">还没有检索记录</p>
              <p class="empty-state-desc">去<button class="btn btn-link" type="button" @click="router.push({ name: 'advanced-search' })">高级检索</button>按民族和疾病查一次吧</p>
            </div>
            <div v-for="h in searchHistory" :key="h.id" class="item">
              <div class="item-main">
                <div class="item-title">{{ searchHistoryTitle(h) }}</div>
                <div class="item-meta">检索于 {{ timeAgo(h.updatedAt) }}</div>
              </div>
              <!-- 三个动作与「智能对话」那个子 Tab 对齐：管理（收藏）/ 分享 / 回顾（查看） -->
              <button
                class="btn btn-sm btn-outline"
                :class="{ 'is-on': favSearchIds.has(h.id) }"
                type="button"
                :disabled="searchBusyId === h.id"
                :title="favSearchIds.has(h.id) ? '取消收藏' : '收藏这条检索记录（仅自己可见）'"
                @click="toggleSearchFavorite(h)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round">
                  <path d="M12 4l2.6 5.4 5.9.8-4.3 4.1 1 5.9-5.2-2.8-5.2 2.8 1-5.9L3.5 10.2l5.9-.8z" />
                </svg>
                {{ favSearchIds.has(h.id) ? '已收藏' : '收藏' }}
              </button>
              <button
                class="btn btn-sm btn-outline btn-sage"
                :class="{ 'is-on': sharedQaKeys.has(qaKeyOf(h)) }"
                type="button"
                :disabled="searchBusyId === h.id || !shareableSearchIds.has(h.id)"
                :title="!shareableSearchIds.has(h.id)
                  ? '这条记录未命中文献，没有可分享的答案'
                  : (sharedQaKeys.has(qaKeyOf(h)) ? '再次分享会刷新社区里那张卡' : '分享到「一问一答」社区')"
                @click="shareSearch(h)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7" /><path d="M12 15V3M8 7l4-4 4 4" />
                </svg>
                {{ sharedQaKeys.has(qaKeyOf(h)) ? '已分享' : '分享' }}
              </button>
              <button class="btn btn-sm btn-outline" type="button" @click="openSearchHistory(h)">查看</button>
            </div>
          </div>
        </div>

        <!-- 我的分享 -->
        <!-- 分享：自己看「我的分享」，访客看「TA 的分享」——同一套排版，数据源不同 -->
        <div v-else-if="tab === 'shares'" v-loading="loadingSharesForView" class="panel-body">
          <div v-if="!sharesForView.length && !loadingSharesForView" class="empty-state empty-state-sm">
            <p class="empty-state-title">{{ isSelf ? '还没有分享内容' : 'TA 还没有公开分享' }}</p>
            <p class="empty-state-desc">
              {{ isSelf ? '在智能对话里点右上角的「分享至动态」即可' : '这位用户还没有把问答分享到动态广场' }}
            </p>
          </div>
          <div v-for="d in sharesForView" :key="d.id" class="item">
            <div class="item-main">
              <div class="item-title wrap">{{ d.title || excerpt(d) }}</div>
              <div v-if="d.title" class="item-text">{{ excerpt(d) }}</div>
              <div class="item-meta">{{ timeAgo(d.createdAt) }} · 被收藏 {{ d.favoriteCount }}</div>
            </div>
            <!-- 收藏放在「查看」左边：先能收，再看。访客页面不给这个按钮——
                 收藏是自己的动作，「我的分享」才是自己的地盘。 -->
            <button
              v-if="isSelf"
              class="btn btn-sm btn-outline"
              :class="{ 'is-on': favDynIds.has(d.id) }"
              type="button"
              :disabled="busyId === d.id"
              :title="favDynIds.has(d.id) ? '取消收藏' : '收藏这条分享（会出现在「我的收藏」）'"
              @click="toggleDynFavorite(d)"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round">
                <path d="M12 4l2.6 5.4 5.9.8-4.3 4.1 1 5.9-5.2-2.8-5.2 2.8 1-5.9L3.5 10.2l5.9-.8z" />
              </svg>
              {{ favDynIds.has(d.id) ? '已收藏' : '收藏' }}
            </button>
            <button class="btn btn-sm btn-outline" type="button" @click="viewDetail(d)">查看</button>
            <!-- 只有自己的动态能删（后端也按 owner 校验，这里只是不显示无效按钮） -->
            <button v-if="d.owner" class="btn btn-sm btn-outline btn-danger" type="button" @click="removeShare(d)">删除</button>
          </div>
        </div>

        <!-- 我的收藏：三类收藏分开显示，语义不同不能混在一起 -->
        <div v-else v-loading="loadingFavorites" class="panel-body">
          <div
            v-if="!favConversations.length && !favorites.length && !favSearchHistory.length && !loadingFavorites"
            class="empty-state empty-state-sm"
          >
            <p class="empty-state-title">还没有收藏内容</p>
            <p class="empty-state-desc">
              在智能对话里点右上角的「收藏」可以收藏整段对话，在<button class="btn btn-link" type="button" @click="router.push({ name: 'dynamics' })">动态</button>页可以收藏别人的分享，「我的对话」里也能收藏检索记录
            </p>
          </div>

          <!-- ① 收藏的对话（私有书签，不公开） -->
          <template v-if="favConversations.length">
            <div class="group-label">收藏的对话 · {{ favConversations.length }}</div>
            <div v-for="c in favConversations" :key="'c' + c.id" class="item">
              <div class="item-main">
                <div class="item-title wrap">{{ c.title }}</div>
                <div class="item-meta">{{ c.messageCount ?? 0 }} 条消息 · {{ timeAgo(c.updatedAt) }}</div>
              </div>
              <button class="btn btn-sm btn-outline" type="button" @click="openConversation(c)">继续</button>
              <button class="btn btn-sm btn-outline btn-danger" type="button" :disabled="busyId === c.id" @click="cancelConversationFavorite(c)">取消收藏</button>
            </div>
          </template>

          <!-- ② 收藏的动态（收藏的是别人公开分享的内容） -->
          <template v-if="favorites.length">
            <div class="group-label">收藏的动态 · {{ favorites.length }}</div>
            <div v-for="d in favorites" :key="'d' + d.id" class="item">
              <div class="item-main">
                <div class="item-title wrap">{{ d.title || excerpt(d) }}</div>
                <div v-if="d.title" class="item-text">{{ excerpt(d) }}</div>
                <div class="item-meta">{{ dynamicAuthor(d) }} · {{ timeAgo(d.createdAt) }}</div>
              </div>
              <button class="btn btn-sm btn-outline" type="button" @click="viewDetail(d)">查看</button>
              <button class="btn btn-sm btn-outline btn-danger" type="button" :disabled="busyId === d.id" @click="cancelFavorite(d)">取消收藏</button>
            </div>
          </template>

          <!-- ③ 收藏的检索（从「我的对话 - 高级检索」收藏来的记录） -->
          <template v-if="favSearchHistory.length">
            <div class="group-label">收藏的检索 · {{ favSearchHistory.length }}</div>
            <div v-for="h in favSearchHistory" :key="'h' + h.id" class="item">
              <div class="item-main">
                <div class="item-title">{{ searchHistoryTitle(h) }}</div>
                <div class="item-meta">检索于 {{ timeAgo(h.updatedAt) }}</div>
              </div>
              <button class="btn btn-sm btn-outline" type="button" @click="openSearchHistory(h)">查看</button>
              <button class="btn btn-sm btn-outline btn-danger" type="button" :disabled="busyId === h.id" @click="cancelSearchFavorite(h)">取消收藏</button>
            </div>
          </template>
        </div>

        <div class="panel-foot">
          <span>本产品仅提供健康与用药知识科普，不构成诊断、治疗或用药建议；若有健康问题，请及时就医。</span>
          <!-- 访客看到的是「返回」，不是「退出登录」——他没在做登录态管理 -->
          <button
            v-if="isSelf"
            class="btn btn-link quit"
            type="button"
            @click="logout"
          >退出登录</button>
          <button
            v-else
            class="btn btn-link quit"
            type="button"
            @click="router.push({ name: 'dynamics' })"
          >返回动态</button>
        </div>
      </section>
    </main>

    <!-- 详情：与动态页共用同一个对话式弹窗，避免两处渲染各写一套 -->
    <DynamicDetailModal v-model="detailVisible" :dynamic="detail" />
  </div>
</template>

<style scoped>
.page { min-height: 100vh; display: flex; flex-direction: column; background: var(--bg); }

/* 用 flex 纵向排列而不是 grid：
   原先的 `display: grid; place-items: start center` 会让 grid 的默认
   align-content: stretch 把多余高度摊到各行上，两张卡被撑开一大截。 */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  padding: 28px 24px 60px;
}
.card {
  width: 100%; max-width: 760px;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--r-md); box-shadow: var(--shadow-sm);
}
.profile { padding: 28px 32px 0; }

/* ---------- 资料区 ---------- */
.profile-top { display: flex; gap: 22px; align-items: flex-start; }
.avatar-wrap { position: relative; flex: none; }
.avatar {
  position: relative; width: 84px; height: 84px; border-radius: 50%;
  border: 0; cursor: pointer; padding: 0; overflow: hidden;
  display: grid; place-items: center; box-shadow: var(--shadow-sm);
}
.avatar-text {
  font-family: var(--serif); font-size: 34px; font-weight: 700; color: #fff;
  user-select: none;
}
.avatar-img { width: 100%; height: 100%; object-fit: cover; display: block; }
.avatar-mask {
  position: absolute; inset: auto 0 0 0; height: 26px;
  display: grid; place-items: center;
  background: rgba(0, 0, 0, 0.42); color: #fff; font-size: 11.5px;
  opacity: 0; transition: opacity 0.15s;
}
.avatar:hover .avatar-mask { opacity: 1; }
/* 访客看到的头像是只读的：点不动，也没有悬停遮罩 */
.avatar.readonly { cursor: default; }

/* 配色选择器 */
.picker {
  position: absolute; top: 92px; left: 0; z-index: 20;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--r-md); box-shadow: var(--shadow-md); padding: 12px 14px;
}
.picker-title { font-size: 12px; color: var(--ink-3); margin-bottom: 9px; }
.picker-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.swatch {
  width: 30px; height: 30px; border-radius: 50%; border: 2px solid transparent;
  cursor: pointer; padding: 0; transition: 0.15s;
}
.swatch:hover { transform: scale(1.1); }
.swatch.on { border-color: var(--ink); }

.upload-btn { margin-top: 11px; }
.picker-hint { font-size: 11px; color: var(--ink-3); margin-top: 7px; text-align: center; line-height: 1.5; }
/* 原生 file input 藏起来，由上面的按钮触发 */
.file-input { display: none; }

.profile-main { flex: 1; min-width: 0; }
.name-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.name { font-family: var(--serif); font-size: 23px; font-weight: 700; line-height: 1.3; }
.name-input {
  font-family: var(--serif); font-size: 20px; font-weight: 700; color: var(--ink);
  border: 1px solid var(--clay); border-radius: var(--r-sm);
  padding: 4px 10px; outline: none; background: var(--bg);
  width: 15rem; max-width: 100%;
}
/* 按钮样式统一来自 main.css 的 .btn 分级，这里只留布局 */
.icon-edit { place-items: center; }

.meta-row { display: flex; align-items: center; gap: 7px; margin-top: 5px; font-size: 12.5px; color: var(--ink-3); }
.dot { opacity: 0.5; }

.bio { margin-top: 12px; display: flex; align-items: flex-start; gap: 8px; }
.bio-text { flex: 1; font-size: 14px; color: var(--ink-2); line-height: 1.75; white-space: pre-wrap; }
.bio-empty { flex: 1; font-size: 13.5px; color: var(--ink-3); font-style: italic; }
.bio-input {
  flex: 1; font-family: var(--sans); font-size: 14px; color: var(--ink);
  line-height: 1.75; border: 1px solid var(--clay); border-radius: var(--r-sm);
  padding: 8px 12px; outline: none; background: var(--bg); resize: vertical;
}
.bio-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.counter { font-size: 11.5px; color: var(--ink-3); font-variant-numeric: tabular-nums; }

/* ---------- 统计 ---------- */
.stats { display: flex; margin-top: 22px; border-top: 1px solid var(--line); }
.stat {
  flex: 1; border: 0; background: transparent; cursor: pointer;
  padding: 16px 0; display: flex; flex-direction: column; align-items: center; gap: 3px;
  transition: background 0.15s;
}
.stat:hover { background: var(--bg); }
.stat + .stat { border-left: 1px solid var(--line); }
.stat-num { font-family: var(--serif); font-size: 21px; font-weight: 700; color: var(--ink); font-variant-numeric: tabular-nums; }
.stat-label { font-size: 12.5px; color: var(--ink-3); }

/* ---------- 内容区 ---------- */
/* 不能设 overflow: hidden —— 它会让 sticky 的参考系变成这个元素自身（而它不滚动），
   结果就是 tab 吸顶完全失效。圆角改由 tabs / panel-foot 各自承担。 */
.panel { padding: 0; }
/* 吸顶：顶栏高 60px，滚到内容区时 tab 停在它下面（top:0 会被顶栏盖住） */
.tabs {
  display: flex; border-bottom: 1px solid var(--line);
  position: sticky; top: 60px; z-index: 10; background: var(--surface);
  border-radius: var(--r-md) var(--r-md) 0 0;
}
.tab {
  flex: 1; border: 0; background: transparent; cursor: pointer;
  font-family: var(--sans); font-size: 14px; color: var(--ink-3);
  padding: 15px 0; transition: 0.15s; border-bottom: 2px solid transparent;
}
.tab:first-child { border-radius: var(--r-md) 0 0 0; }
.tab:last-child { border-radius: 0 var(--r-md) 0 0; }
.tab:hover { color: var(--ink-2); }
.tab.on { color: var(--clay-deep); font-weight: 600; border-bottom-color: var(--clay); }

.panel-body { padding: 20px 20px; min-height: 120px; }

/* 「我的对话」下的来源子 Tab（分段控件）。
   刻意做得比顶层 .tab 轻：顶层 Tab 是三个页面，这两个只是同一个列表的两种来源 */
.subtabs {
  display: inline-flex; gap: 2px; padding: 3px;
  background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-md);
  margin-bottom: 16px;
}
.subtab {
  display: inline-flex; align-items: center; gap: 6px;
  border: 0; background: transparent; cursor: pointer;
  font-family: var(--sans); font-size: 13px; color: var(--ink-3);
  padding: 6px 13px; border-radius: calc(var(--r-md) - 3px); transition: 0.15s;
}
.subtab:hover { color: var(--ink-2); }
.subtab.on { background: var(--surface); color: var(--clay-deep); font-weight: 600; box-shadow: var(--shadow-sm); }
/* 计数徽标：两个来源各有多少条一眼可见，也顺带说明「切过去不是空的」 */
.subtab-num {
  font-size: 11px; font-variant-numeric: tabular-nums; line-height: 16px;
  padding: 0 6px; border-radius: 999px;
  color: var(--ink-3); background: var(--surface); border: 1px solid var(--line);
}
.subtab.on .subtab-num { color: var(--clay-deep); background: var(--clay-soft); border-color: var(--accent-line); }
/* 加载遮罩挂在这里而不是 .panel-body —— 否则切子 Tab 时整块（含子 Tab 本身）都会被遮住 */
.sub-body { min-height: 60px; }

.item {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px; border-radius: var(--r-md);
  border: 1px solid transparent; transition: 0.15s;
}
/* 收藏分两组时的组标题 */
.group-label {
  font-size: 12px; font-weight: 600; color: var(--ink-3);
  letter-spacing: 0.04em; padding: 4px 16px 8px;
}
.group-label:not(:first-child) { margin-top: 14px; border-top: 1px solid var(--line-2); padding-top: 16px; }
.item + .item { margin-top: 4px; }
.item:hover { background: var(--bg); border-color: var(--line-strong); }
.item-main { flex: 1; min-width: 0; }
.item-title { font-size: 14px; color: var(--ink); line-height: 1.5; }
.item-title.wrap {
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2;
  -webkit-box-orient: vertical; overflow: hidden;
}
.item-text {
  font-size: 12.5px; color: var(--ink-2); line-height: 1.65; margin-top: 3px;
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2;
  -webkit-box-orient: vertical; overflow: hidden;
}
.item-meta { font-size: 12px; color: var(--ink-3); margin-top: 4px; }

.panel-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap;
  border-top: 1px solid var(--line); padding: 14px 20px;
  font-size: 12px; color: var(--ink-3); background: var(--bg);
  border-radius: 0 0 var(--r-md) var(--r-md);
}
.quit { color: var(--ink-2); }
.quit:hover { color: var(--danger); text-decoration: underline; }

/* ---------- 详情弹窗 ---------- */
.detail-meta { font-size: 12.5px; color: var(--ink-3); margin-bottom: 10px; }
.detail-text { font-size: 14px; color: var(--ink-2); line-height: 1.85; white-space: pre-wrap; }

@media (max-width: 560px) {
  .profile { padding: 22px 20px 0; }
  .profile-top { flex-direction: column; align-items: center; text-align: center; }
  .name-row, .meta-row { justify-content: center; }
  .bio { justify-content: center; }
  .picker { left: 50%; transform: translateX(-50%); }
}
</style>
