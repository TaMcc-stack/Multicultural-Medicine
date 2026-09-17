<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppTopbar from '@/components/AppTopbar.vue'
import SourceBadge from '@/components/SourceBadge.vue'
import { useUserStore } from '@/stores/user'
import { fetchPapers, type Paper } from '@/api/qa'
import {
  deleteKbDoc,
  getKbDoc,
  getKbDocStatus,
  kbFileUrl,
  kbPageImageUrl,
  listKbDocs,
  reviewKbDoc,
  updateKbDocMeta,
  uploadKbDoc,
  type KbDoc,
  type KbPartition,
  type ReviewStatus,
  type SourceLevel,
} from '@/api/kb'
import { SOURCE_META, SOURCE_ORDER } from '@/utils/sourceLevel'
import { parseContentBlocks, type ContentBlock } from '@/utils/contentBlocks'

// ============================================================
// 知识库：内容分类分组 / 拖拽入库 / 添加资料 /
//         上传进度 / 查看全文 / 两段式删除 / 待审核队列
// 数据来源：内置文献(papers.json) 只读 + 用户文档(/api/kb 服务端持久化)
// ============================================================

interface LiteratureItem {
  id: string
  title: string
  category?: string
  content?: string
  excerpt?: string
  ethnic?: string
  disease?: string
  paper?: boolean // 内置文献（只读）
  kb?: boolean // 用户上传文档
  kbId?: string // 服务端文档 id
  size?: number
  ext?: string
  pageCount?: number | null
  originalName?: string
  uploadedAt?: number
  // 自动识别的文献元数据（有就显示，没有就不写）
  author?: string | null
  source?: string | null
  publishYear?: number | null
  summary?: string | null
  articleSummary?: string | null
  // 结构化证据（JSON 字符串，含 LLM 提取的证据片段）
  evidenceJson?: string | null
  // 后台处理状态 / 进度 / 阶段（用户上传文档异步解析时）
  status?: string
  progress?: number
  stage?: string | null
  // 来源维度（一级分类）：内部文献一律 official；上传文档由后端裁决
  sourceLevel?: SourceLevel
  sourceOrg?: string | null
  reviewStatus?: ReviewStatus
  userId?: number | null
  /** 知识库分区：integrated 整合资料库（优先检索）/ raw 原始文献库（降级检索） */
  partition?: KbPartition
}

// ---------- 数据 ----------
const papers = ref<Paper[]>([])
const userDocs = ref<KbDoc[]>([])

function paperContent(p: Paper): string {
  const lines: string[] = []
  const src = [p.journal, p.year, p.volume].filter(Boolean).join(' · ')
  if (src) lines.push(`【出处】${src}`)
  if (p.authors) lines.push(`【作者】${p.authors}`)
  const ed = [p.ethnicity, p.disease].filter(Boolean).join(' · ')
  if (ed) lines.push(`【民族/疾病】${ed}`)
  const st = [p.studyType, p.studyYear].filter(Boolean).join(' · ')
  if (st) lines.push(`【研究类型】${st}`)
  if (p.population) lines.push(`【研究人群】${p.population}`)
  if (p.findings) lines.push(`【主要结论】${p.findings}`)
  const evs = p.evidences || []
  if (evs.length) {
    lines.push('【证据片段】')
    for (const e of evs) {
      const tag = [e.topic, e.ethnicity].filter(Boolean).join('·')
      lines.push(`- [${tag}] ${e.content}`)
    }
  }
  if (p.limitation) lines.push(`【局限性】${p.limitation}`)
  if (p.doi) lines.push(`DOI: ${p.doi}`)
  return lines.join('\n')
}

const paperItems = computed<LiteratureItem[]>(() =>
  papers.value.map((p) => ({
    id: p.id,
    title: p.title,
    category: '民族疾病类',
    content: paperContent(p),
    ethnic: p.ethnicity,
    disease: p.disease,
    paper: true,
    // 内置文献是随项目发布的受控资料，天生就是官方等级
    sourceLevel: 'official',
    reviewStatus: 'approved',
    // 内置文献都是整篇论文，属于原始文献库
    partition: 'raw',
  })),
)

const userItems = computed<LiteratureItem[]>(() =>
  userDocs.value.map((d) => ({
    id: d.id,
    title: d.title,
    category: d.category,
    kb: true,
    kbId: d.id,
    size: d.size,
    ext: d.ext,
    pageCount: d.pageCount,
    originalName: d.originalName,
    uploadedAt: d.uploadedAt,
    author: d.author ?? null,
    source: d.source ?? null,
    publishYear: d.publishYear ?? null,
    summary: d.summary ?? null,
    articleSummary: d.articleSummary ?? null,
    evidenceJson: d.evidenceJson ?? null,
    status: d.status ?? 'done',
    progress: d.progress ?? 100,
    stage: d.stage ?? null,
    // 来源维度由后端裁决；历史数据没有这些字段 → 回退 official/approved
    sourceLevel: d.sourceLevel ?? 'official',
    sourceOrg: d.sourceOrg ?? null,
    reviewStatus: d.reviewStatus ?? 'approved',
    userId: d.userId ?? null,
    // 分区由后端按扩展名判定（pdf → 原始文献库）；老数据可能缺这一项，兜成整合资料库
    partition: d.partition ?? 'integrated',
    // 卡片展示：优先文章总结；其次摘要；都没有则展示文件名/格式等基本信息
    content:
      (d.articleSummary || d.summary) ||
      `「${d.originalName}」· ${(d.ext || '').toUpperCase()} · ${fmtSize(d.size)}${
        d.pageCount ? ' · ' + d.pageCount + ' 页' : ''
      }`,
  })),
)

async function loadAll() {
  await Promise.all([loadPapers(), loadUserDocs()])
}
async function loadPapers() {
  try {
    papers.value = await fetchPapers()
  } catch {
    papers.value = []
  }
}
async function loadUserDocs() {
  try {
    userDocs.value = await listKbDocs()
    // 若存在仍在后台处理的文档（如上传后离开页面再回来），恢复进度轮询
    userDocs.value
      .filter((d) => d.status === 'processing')
      .forEach((d) => startPolling(d.id))
  } catch {
    userDocs.value = []
  }
}

/**
 * 一个内容分类书架（按 category 归拢的一批资料）。
 *
 * 分类不写死、也不由前端判定——沿用后端 MetadataExtractor 的归类结果：数据里出现新的
 * category，这里自然就多出一张小卡片。这正好实现「遇到新主题自动新建分类」的要求，
 * 而且判定规则只有一份（后端关键词表），前端不需要跟着改。
 */
interface Shelf {
  category: string
  items: LiteratureItem[]
}

/**
 * 页签：整合资料库 / 原始文献库 / 待审核。
 *
 * 默认进**整合资料库**——它是检索时优先用的那一档，也是日常维护最常看的一栏；
 * 原始文献库是完整 PDF，多数时候只在核对原文时才进来。
 */
type TabKey = KbPartition | 'pending'
const tab = ref<TabKey>('integrated')

/** 正式入库、且属于某个分区的条目（两个页签共用同一套过滤，免得两处判据漂移） */
const shelvedItems = computed<LiteratureItem[]>(() => [
  ...paperItems.value,
  ...userItems.value.filter((l) => l.reviewStatus !== 'pending'),
])

/**
 * 当前页签下的条目。
 *
 * **待审核的不算**——任务要求「只有通过审核的文档才正式归入对应的二级分类」，
 * 它们只出现在「待审核」页签里。若混进来，未审核内容会看起来像是已入库的资料。
 */
const allItems = computed<LiteratureItem[]>(() =>
  tab.value === 'pending'
    ? []
    : shelvedItems.value.filter((l) => (l.partition ?? 'integrated') === tab.value),
)

/** 两个分区的条数，给页签上的角标用 */
const partitionCounts = computed<Record<KbPartition, number>>(() => ({
  integrated: shelvedItems.value.filter((l) => (l.partition ?? 'integrated') === 'integrated').length,
  raw: shelvedItems.value.filter((l) => (l.partition ?? 'integrated') === 'raw').length,
}))

/** 待审核队列：官方账号看到全部，普通用户只看到自己那份（后端已按身份过滤） */
const pendingItems = computed(() => userItems.value.filter((l) => l.reviewStatus === 'pending'))

/**
 * 内容分类分组（第二级）。
 *
 * 之前外面还套了一层「一级分类（来源）」：官方 / 网页抓取两组，每组下面再按内容分类。
 * 来源分类现在只剩「官方权威资料」一档，那层分组标题与折叠箭头就没有存在意义了
 * ——没有第二个组可区分，折叠它也只是把自己藏起来。所以列表直接平铺。
 * 来源等级并没有因此丢失：每张卡片上仍带着来源徽标。
 */
const shelves = computed<Shelf[]>(() => {
  const byCat = new Map<string, LiteratureItem[]>()
  for (const it of allItems.value) {
    const cat = it.category || '未分类'
    const list = byCat.get(cat)
    if (list) list.push(it)
    else byCat.set(cat, [it])
  }
  return [...byCat.entries()]
    .map(([category, items]) => ({ category, items }))
    .sort((a, b) => b.items.length - a.items.length)
})

// 页签与 `allItems` 的声明在上方（`allItems` 依赖 `tab`，两处必须挨着）

/**
 * 管理员判定（演示用途）：与后端 `app.kb.official-user-ids` 默认白名单一致（demo 账号 id=1）。
 * 只有管理员能看到「待审核」页签并执行通过/驳回；后续应改为基于角色权限的接口校验。
 */
const userStore = useUserStore()
const isAdmin = computed(() => userStore.user?.id === 1)
const route = useRoute()

// 文献补录工作台提交完会带着 ?tab=pending 跳过来（资料已经进了待审核队列，
// 用户的下一步就是去核对它，不该让他自己再找一个页签）。
// 只认这一个取值：其他 query 一概不理会，避免这页被 URL 参数牵着走。
if (route.query.tab === 'pending' && isAdmin.value) tab.value = 'pending'

/** 审核通过时管理员选择的归入分类（按文档 id 记录，默认官方权威资料） */
const approveLevels = ref<Record<string, 'official' | 'web_crawl'>>({})
function approveLevelOf(id: string): 'official' | 'web_crawl' {
  return approveLevels.value[id] ?? 'official'
}

/** 审核（仅管理员；后端也会校验一次）。通过时按管理员选择的分类归入。 */
const reviewingId = ref<string | null>(null)
async function review(id: string, decision: 'approved' | 'rejected') {
  reviewingId.value = id
  try {
    const level = approveLevelOf(id)
    await reviewKbDoc(id, decision, decision === 'approved' ? level : undefined)
    ElMessage.success(
      decision === 'approved' ? `已通过，资料归入「${level === 'official' ? '官方权威资料' : '网页抓取补充'}」` : '已驳回',
    )
    await loadUserDocs()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '审核失败')
  } finally {
    reviewingId.value = null
  }
}

// ---------- 待审核条目的编辑（管理员改标题/分类） ----------
const editingId = ref<string | null>(null)
const editTitle = ref('')
const editCategory = ref('')
const savingEdit = ref(false)

function startEdit(b: LiteratureItem) {
  editingId.value = b.id
  editTitle.value = b.title
  editCategory.value = b.category || ''
}

function cancelEdit() {
  editingId.value = null
}

async function saveEdit(b: LiteratureItem) {
  if (!b.kbId || savingEdit.value) return
  const t = editTitle.value.trim()
  if (!t) {
    ElMessage.warning('标题不能为空')
    return
  }
  savingEdit.value = true
  try {
    await updateKbDocMeta(b.kbId, { title: t, category: editCategory.value.trim() || undefined })
    ElMessage.success('已保存修改')
    editingId.value = null
    await loadUserDocs()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingEdit.value = false
  }
}

/** 该来源下某个二级分类的资料（保留给拖拽入库用） */
function booksOf(cat: string): LiteratureItem[] {
  const fromPapers = cat === '民族疾病类' ? paperItems.value : []
  return [...fromPapers, ...allItems.value.filter((l) => l.kb && l.category === cat)]
}

function contentOf(l: LiteratureItem): string {
  return l.content || l.excerpt || ''
}

function fmtSize(n?: number): string {
  if (!n) return '0 KB'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

// 后端自动提取的结构化证据由后端保留，界面不再手动编辑/展示证据片段

// ---------- toast ----------
const toastVisible = ref(false)
const toastText = ref('')
let toastTimer: number | undefined
function toast(msg: string) {
  toastText.value = msg
  toastVisible.value = true
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toastVisible.value = false
  }, 2600)
}

// ---------- 展开 / 高亮 / 删除 ----------
const expandedIds = ref<Record<string, boolean>>({})
const flashId = ref('')
const pendingDel = ref<string | null>(null)
let pendingTimer: number | undefined

function toggleExpand(id: string) {
  expandedIds.value = { ...expandedIds.value, [id]: !expandedIds.value[id] }
}

function onDelete(l: LiteratureItem) {
  if (!l.kb || !l.kbId) return
  if (pendingDel.value === l.kbId) {
    deleteKbDoc(l.kbId)
      .then(() => {
        pendingDel.value = null
        toast(`已移除《${l.title}》`)
        return loadUserDocs()
      })
      .catch(() => {})
  } else {
    pendingDel.value = l.kbId
    if (pendingTimer) clearTimeout(pendingTimer)
    pendingTimer = window.setTimeout(() => {
      if (pendingDel.value === l.kbId) pendingDel.value = null
    }, 3000)
  }
}

// ---------- 上传（统一队列：弹窗 / 拖入书架共用） ----------
const uploading = ref(false)
const uploadName = ref('')
const uploadProgress = ref(0)
const queue = ref<( {
  file: File; cat: string; title?: string;
  source?: string; author?: string; publishYear?: number;
})[]>([])

function enqueueUpload(
  file: File, cat: string, title?: string,
  source?: string, author?: string, publishYear?: number,
) {
  queue.value.push({ file, cat, title, source, author, publishYear })
  processQueue()
}
async function processQueue() {
  if (uploading.value) return
  const item = queue.value.shift()
  if (!item) return
  uploading.value = true
  uploadName.value = item.file.name
  uploadProgress.value = 0
  try {
    const doc = await uploadKbDoc(item.file, {
      title: item.title,
      category: item.cat,
      source: item.source,
      author: item.author,
      publishYear: item.publishYear,
      onProgress: (p) => (uploadProgress.value = p),
    })
    // 上传即返回：刷新书架（后台处理中文档会自动进入轮询）
    await loadUserDocs()
    if (doc.status === 'processing') {
      toast(`已接收《${item.file.name.replace(/\.[^.]+$/, '')}》，后台解析中…`)
    } else {
      toast(`已入库《${item.file.name.replace(/\.[^.]+$/, '')}》`)
    }
  } catch {
    /* 拦截器已提示错误 */
  } finally {
    uploading.value = false
    uploadProgress.value = 0
    if (queue.value.length) processQueue()
  }
}

// ---------- 后台解析进度轮询（上传即返回，后台总结/证据/索引，卡片与弹窗进度条实时更新） ----------
const processingIds = ref<Set<string>>(new Set())
let pollTimer: number | undefined

function startPolling(id: string) {
  processingIds.value.add(id)
  if (pollTimer) return
  pollTimer = window.setInterval(tickJobs, 2000)
}

function tickJobs() {
  const ids = [...processingIds.value]
  if (!ids.length) {
    stopPolling()
    return
  }
  ids.forEach((id) => pollOne(id))
}

async function pollOne(id: string) {
  try {
    const s = await getKbDocStatus(id)
    if (s.status === 'processing') {
      const doc = userDocs.value.find((d) => d.id === id)
      if (!doc) {
        processingIds.value.delete(id)
        return
      }
      doc.status = 'processing'
      doc.progress = s.progress
      doc.stage = s.stage ?? null
    } else {
      processingIds.value.delete(id)
      if (!processingIds.value.size) stopPolling()
      await loadUserDocs()
    }
  } catch {
    processingIds.value.delete(id)
    if (!processingIds.value.size) stopPolling()
  }
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

// ---------- 添加资料弹窗（选文件 → 点击底部「解析」→ 进度条 → 自动入库） ----------
const modalVisible = ref(false)
const selectedFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const dropOver = ref(false)
const formError = ref('')
// 上传状态：modalUploading=文件正在传输；currentDocId=后台处理中的文档（完成后展示「已入库」）
const modalUploading = ref(false)
const modalProgress = ref(0)
const currentDocId = ref<string | null>(null)

const currentDoc = computed(() => {
  if (!currentDocId.value) return null
  return userDocs.value.find((d) => d.id === currentDocId.value) ?? null
})

/** 这份文件已经解析入库 */
const docDone = computed(() => currentDoc.value?.status === 'done')
/** 正在上传，或后台还在解析 */
const docBusy = computed(() => modalUploading.value || currentDoc.value?.status === 'processing')
/** 底部「解析」按钮的显示条件：选了文件、还没入库、也不在忙 */
const canParse = computed(() => !!selectedFile.value && !docDone.value && !docBusy.value)

function openModal() {
  modalVisible.value = true
  selectedFile.value = null
  formError.value = ''
  modalUploading.value = false
  modalProgress.value = 0
  currentDocId.value = null
  // 每次打开都回到默认「官方权威资料」，避免上次的选择残留到下一次上传
  uploadLevel.value = 'official'
  uploadOrg.value = ''
}
function closeModal() {
  // 解析中允许直接关闭：后台继续处理，书架卡片会继续显示进度
  modalVisible.value = false
}

function setFile(file: File) {
  const ext = (file.name.split('.').pop() || '').toLowerCase()
  const ok = ['pdf', 'docx', 'doc', 'txt', 'md', 'csv', 'tsv', 'json', 'rtf'].includes(ext)
  if (!ok) {
    formError.value = `不支持的文件类型：.${ext || '未知'}（仅支持 PDF/Word/文本）`
    return
  }
  selectedFile.value = file
  formError.value = ''
  modalUploading.value = false
  modalProgress.value = 0
  currentDocId.value = null
}

function onPickFile() {
  fileInput.value?.click()
}
function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length) setFile(input.files[0]!)
  input.value = ''
}
function onDropZoneDrop(e: DragEvent) {
  e.preventDefault()
  dropOver.value = false
  if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) {
    setFile(e.dataTransfer.files[0]!)
  }
}

/**
 * 上传时选择的来源等级与来源机构。
 *
 * 默认「官方权威资料」——但这个选择只是**申请**：后端会按上传者账号裁决，
 * 非官方账号即便选了官方也会被降级为「用户上传」并进入待审核。
 */
const uploadLevel = ref<SourceLevel>('official')
const uploadOrg = ref('')

/** 点击「解析」：上传（即时返回）→ 后台解析（进度条轮询）→ 完成后自动进对应类别 */
async function startParse() {
  const f = selectedFile.value
  if (!f || modalUploading.value) return
  modalUploading.value = true
  modalProgress.value = 0
  formError.value = ''
  try {
    const doc = await uploadKbDoc(f, {
      sourceLevel: uploadLevel.value,
      sourceOrg: uploadOrg.value.trim() || undefined,
      onProgress: (p) => (modalProgress.value = p),
    })
    // 普通账号提交的资料一律进入「待审核」，由管理员审核通过后归入分类
    if (doc.reviewStatus === 'pending') {
      ElMessage.info('已提交审核，管理员通过后才会正式入库并可被检索引用')
    }
    // 刷新书架：新文档（processing）出现，同时启动进度轮询
    await loadUserDocs()
    currentDocId.value = doc.id
    modalUploading.value = false // 传输完成，进度条改由 currentDoc 驱动
  } catch {
    formError.value = '上传失败，请重试'
    modalUploading.value = false
  }
}



// ---------- 书架拖拽 ----------
const dragCat = ref<string | null>(null)
function onShelfDragOver(cat: string, e: DragEvent) {
  e.preventDefault()
  dragCat.value = cat
}
function onShelfDragLeave(cat: string) {
  if (dragCat.value === cat) dragCat.value = null
}
function onShelfDrop(cat: string, e: DragEvent) {
  e.preventDefault()
  dragCat.value = null
  const files = e.dataTransfer?.files
  if (files && files.length) {
    for (let i = 0; i < files.length; i++) enqueueUpload(files[i]!, cat)
  }
}

// ---------- 详情卡 + 开始阅读 ----------
const viewerVisible = ref(false)
const viewing = ref<LiteratureItem | null>(null)
const viewingReading = ref(false) // false=基本信息卡；true=已展开全文
const viewingText = ref('')
const viewingLoading = ref(false)
const viewError = ref('')

/** 是否有可展示的自动识别元数据 */
function hasMeta(l: LiteratureItem | null): boolean {
  if (!l) return false
  return !!(l.author || l.source || l.publishYear || l.summary || l.articleSummary)
}

async function openViewer(l: LiteratureItem) {
  viewing.value = l
  viewerVisible.value = true
  viewingReading.value = false
  viewingText.value = ''
  viewError.value = ''
}
async function startReading() {
  const l = viewing.value
  if (!l) return
  viewingLoading.value = true
  viewError.value = ''
  try {
    if (l.paper) {
      viewingText.value = contentOf(l)
    } else if (l.kb && l.kbId) {
      const d = await getKbDoc(l.kbId)
      viewingText.value = d.content || '（未能提取到正文内容）'
    }
    viewingReading.value = true
  } catch {
    viewError.value = '加载正文失败，请稍后重试'
  } finally {
    viewingLoading.value = false
  }
}
function closeViewer() {
  viewerVisible.value = false
  viewing.value = null
  viewingReading.value = false
}

// ---------- 阅读内容分块（段落 + Markdown 表格，避免 v-html 的 XSS 风险） ----------
// 解析逻辑在 utils/contentBlocks.ts（文献补录工作台的预览也用同一份，两处排版才不会漂移）
const contentBlocks = computed<ContentBlock[]>(() => parseContentBlocks(viewingText.value))
// PDF 文献：阅读页按「原页图一张图一张图」展示（保留原始版式/图表/矢量图）；非 PDF 渲染正文文本块
const isPdfReader = computed(() => (viewing.value?.pageCount || 0) > 0)
const pdfPages = computed<number[]>(() => {
  const pc = viewing.value?.pageCount || 0
  return Array.from({ length: pc }, (_, i) => i + 1)
})
function openOriginal(l: LiteratureItem) {
  if (l.kb && l.kbId) window.open(kbFileUrl(l.kbId), '_blank')
}

// ---------- 深链接 ----------
function locateFromHash() {
  const hash = location.hash || ''
  if (!hash) return
  const id = hash.replace(/^#/, '')
  if (!id) return
  window.setTimeout(() => {
    const el = document.querySelector(`.book[data-id="${id}"]`)
    if (!el) return
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    flashId.value = id
    window.setTimeout(() => {
      if (flashId.value === id) flashId.value = ''
    }, 2600)
  }, 80)
}

onMounted(() => {
  loadAll().then(() => {
    nextTick(locateFromHash)
  })
})
</script>

<template>
  <div class="page">
    <AppTopbar />

    <main class="main">
      <div class="wrap">
        <h1 class="page-title">知识库</h1>
        <p class="page-sub">
          上传医学资料（PDF/Word/文本），点击<b>「解析」</b>后，系统会自动提取全文并归入对应类别。
          若没有匹配的类别，系统会自动创建新分类。
          库中资料均为官方筛选、审核过的文献，可直接作为权威结论引用。
        </p>

        <div class="toolbar">
          <button class="btn btn-solid" type="button" @click="openModal">＋ 添加资料</button>
        </div>

        <!-- 页签：整合资料库 / 原始文献库 / 待审核（仅管理员可见）
             分区是检索时的一级划分：整合资料库优先，原始文献库只在整合资料命中不了时降级启用，
             所以默认停在整合资料库——那也是日常维护最常看的一栏 -->
        <div class="tabs">
          <button
            class="tab"
            :class="{ on: tab === 'integrated' }"
            type="button"
            @click="tab = 'integrated'"
          >
            整合资料库
            <span class="tab-count">{{ partitionCounts.integrated }}</span>
          </button>
          <button
            class="tab"
            :class="{ on: tab === 'raw' }"
            type="button"
            @click="tab = 'raw'"
          >
            原始文献库
            <span class="tab-count">{{ partitionCounts.raw }}</span>
          </button>
          <button
            v-if="isAdmin"
            class="tab"
            :class="{ on: tab === 'pending' }"
            type="button"
            @click="tab = 'pending'"
          >
            待审核
            <span v-if="pendingItems.length" class="tab-count">{{ pendingItems.length }}</span>
          </button>
        </div>

        <!-- ① 资料：一级 = 来源，二级 = 内容类型（两个分区共用这套排版，只是数据源不同） -->
        <template v-if="tab !== 'pending'">
          <div v-if="!allItems.length" class="empty-state empty-state-sm">
            <p class="empty-state-title">
              {{ tab === 'raw' ? '原始文献库还是空的' : '整合资料库还是空的' }}
            </p>
            <p class="empty-state-desc">
              {{ tab === 'raw'
                ? '上传论文原文 PDF 会自动归到这里。'
                : '把多篇文献整理成的汇编上传上来，或从「需求分析」补录，都会归到这里。' }}
            </p>
          </div>
          <!-- 有资料时才渲染书架；空的就只留上面那句空状态 -->
          <template v-else>
              <!-- 内容分类：分类由后端识别，出现新主题就会多出一张卡片 -->
              <section
                v-for="s in shelves"
                :key="s.category"
                class="shelf"
                :class="{ 'drag-over': dragCat === s.category }"
                @dragover="onShelfDragOver(s.category, $event)"
                @dragleave="onShelfDragLeave(s.category)"
                @drop="onShelfDrop(s.category, $event)"
              >
                <div class="shelf-head">
                  <span class="shelf-name">【{{ s.category }}】</span>
                  <span class="shelf-count">共 {{ s.items.length }} 份</span>
                </div>
                <div class="shelf-body">
                  <div class="book-grid">
                    <article
                      v-for="b in s.items"
                      :key="b.id"
                      class="book"
                      :class="{ expanded: expandedIds[b.id], flash: flashId === b.id }"
                      :data-id="b.id"
                    >
                      <div class="book-top">
                        <span class="book-title">《{{ b.title }}》</span>
                        <span v-if="b.paper" class="book-badge">内置文献</span>
                        <button
                          v-else-if="b.kb"
                          class="btn btn-sm btn-ghost btn-danger book-del"
                          :class="{ 'btn-solid': pendingDel === b.kbId }"
                          type="button"
                          @click="onDelete(b)"
                        >
                          {{ pendingDel === b.kbId ? '确认删除' : '移除' }}
                        </button>
                      </div>
                      <div class="book-content" :class="{ muted: !contentOf(b) }">
                        {{ contentOf(b) || '（暂无内容）' }}
                      </div>
                      <div v-if="b.status === 'processing'" class="book-processing">
                        <div class="bp-row">
                          <span class="bp-label">{{ b.stage || '解析中…' }}</span>
                          <span class="bp-pct">{{ b.progress ?? 0 }}%</span>
                        </div>
                        <div class="bp-bar"><div class="bp-fill" :style="{ width: (b.progress ?? 0) + '%' }"></div></div>
                      </div>
                      <div class="book-actions">
                        <button class="btn btn-sm btn-soft" type="button" @click="openViewer(b)">查看全文</button>
                        <button
                          v-if="b.sourceOrg"
                          class="book-org"
                          type="button"
                          :title="`来源机构：${b.sourceOrg}`"
                        >{{ b.sourceOrg }}</button>
                        <button
                          v-if="contentOf(b).length > 180"
                          class="btn btn-sm btn-link"
                          type="button"
                          @click="toggleExpand(b.id)"
                        >
                          {{ expandedIds[b.id] ? '收起' : '展开' }}
                        </button>
                      </div>
                    </article>
                  </div>
                </div>
              </section>
          </template>
        </template>

        <!-- ② 待审核：通过后才正式入库并推入检索索引 -->
        <template v-else>
          <div v-if="!pendingItems.length" class="empty-state">
            <p class="empty-state-title">没有待审核的资料</p>
            <p class="empty-state-desc">用户上传的资料会先到这里，通过后才正式归入对应的二级分类。</p>
          </div>
          <div v-else class="review-list">
            <article v-for="b in pendingItems" :key="b.id" class="review-item">
              <div class="ri-main">
                <!-- 编辑态：改标题 / 分类（标题重名时后端自动加序号） -->
                <template v-if="editingId === b.id">
                  <div class="ri-edit">
                    <label class="ri-edit-field">
                      <span>标题</span>
                      <input v-model="editTitle" type="text" class="sf-input" :disabled="savingEdit" />
                    </label>
                    <label class="ri-edit-field">
                      <span>拟归入分类</span>
                      <input v-model="editCategory" type="text" class="sf-input" :disabled="savingEdit" />
                    </label>
                    <div class="ri-edit-acts">
                      <button class="btn btn-sm btn-solid" type="button" :disabled="savingEdit" @click="saveEdit(b)">
                        {{ savingEdit ? '保存中…' : '保存' }}
                      </button>
                      <button class="btn btn-sm btn-ghost" type="button" :disabled="savingEdit" @click="cancelEdit">取消</button>
                    </div>
                  </div>
                </template>
                <template v-else>
                  <div class="ri-title">《{{ b.title }}》</div>
                  <div class="ri-meta">
                    <SourceBadge :level="b.sourceLevel" :org="b.sourceOrg" show-org />
                    <span>{{ (b.ext || '').toUpperCase() }} · {{ fmtSize(b.size) }}</span>
                    <span v-if="b.category">拟归入：{{ b.category }}</span>
                  </div>
                  <div class="ri-excerpt">{{ contentOf(b) || '（暂无内容）' }}</div>
                </template>
              </div>
              <div class="ri-acts">
                <template v-if="editingId !== b.id">
                  <button class="btn btn-sm btn-soft" type="button" @click="openViewer(b)">预览</button>
                  <button class="btn btn-sm btn-soft" type="button" @click="startEdit(b)">编辑</button>
                  <label class="ri-level">
                    归入
                    <select
                      class="sf-input ri-level-select"
                      :value="approveLevelOf(b.id)"
                      :disabled="reviewingId === b.id"
                      @change="approveLevels[b.id] = ($event.target as HTMLSelectElement).value as 'official' | 'web_crawl'"
                    >
                      <option value="official">官方权威资料</option>
                      <option value="web_crawl">网页抓取补充</option>
                    </select>
                  </label>
                  <button
                    class="btn btn-sm btn-outline btn-danger"
                    type="button"
                    :disabled="reviewingId === b.id"
                    @click="review(b.id, 'rejected')"
                  >驳回</button>
                  <button
                    class="btn btn-sm btn-solid"
                    type="button"
                    :disabled="reviewingId === b.id"
                    @click="review(b.id, 'approved')"
                  >通过</button>
                </template>
              </div>
            </article>
          </div>
        </template>
      </div>
    </main>

    <footer class="footer">
      <div class="wrap">
        <span class="foot-brand">多民族特色医学智能体</span>
        <span class="foot-note">产品概念原型 · 演示数据</span>
      </div>
    </footer>

    <!-- 添加资料弹窗（选文件 → 点击底部「解析」→ 进度条 → 自动入库） -->
    <div v-if="modalVisible" class="modal" @keydown.esc="closeModal">
      <div class="modal-backdrop" @click="closeModal"></div>
      <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="litModalTitle">
        <div class="modal-head">
          <h3 id="litModalTitle">添加资料</h3>
          <button class="btn btn-icon btn-ghost modal-close" type="button" aria-label="关闭" @click="closeModal">&times;</button>
        </div>

        <p class="modal-sub">
          从电脑本地选择一份资料（PDF / Word / 文本），点击底部「解析」后自动提取基本信息与 AI 总结，解析完成即自动归入对应类别。
        </p>

        <div
          class="pick-box"
          :class="{ 'drag-over': dropOver }"
          @click="onPickFile"
          @dragover.prevent="dropOver = true"
          @dragleave="dropOver = false"
          @drop="onDropZoneDrop"
        >
          <div class="pb-icon">＋</div>
          <div class="pb-file">拖入文件到此处，或点击选择</div>
          <div class="pb-meta">支持 .pdf / .docx / .txt / .md 等</div>
          <input ref="fileInput" type="file" hidden @change="onFileChange" />
        </div>

        <!-- 已选文件（尚未选择时显示提示；选择后：文件名 + 入库状态 / 进度条） -->
        <div class="file-box" :class="{ 'has-file': !!selectedFile }">
          <div v-if="selectedFile" class="fb-inner">
            <div class="fb-row">
              <span class="fb-icon">📄</span>
              <span class="fb-name">{{ selectedFile.name }}</span>
              <span v-if="docDone" class="fb-done">✓ 已入库</span>
            </div>
            <div class="fb-meta">{{ fmtSize(selectedFile.size) }} · {{ (selectedFile.name.split('.').pop() || '').toUpperCase() }}</div>

            <!-- 解析进度条（上传中 / 后台解析中） -->
            <div v-if="docBusy" class="fb-progress">
              <div class="fbp-row">
                <span class="fbp-label">{{ modalUploading ? '上传中…' : (currentDoc?.stage || '解析中…') }}</span>
                <span class="fbp-pct">{{ modalUploading ? modalProgress : (currentDoc?.progress ?? 0) }}%</span>
              </div>
              <div class="fbp-bar">
                <div class="fbp-fill" :style="{ width: (modalUploading ? modalProgress : (currentDoc?.progress ?? 0)) + '%' }"></div>
              </div>
            </div>
          </div>
          <div v-else class="fb-empty">尚未选择文件</div>
        </div>

        <!-- 来源维度：管理员上传时选择归入的一级分类。
             普通账号提交的资料一律进入「待审核」，由管理员审核通过后裁决归入分类。 -->
        <div class="src-form">
          <label class="sf-field">
            <span class="sf-label">归入分类</span>
            <select v-model="uploadLevel" class="sf-input" :disabled="modalUploading || !isAdmin">
              <option v-for="k in SOURCE_ORDER" :key="k" :value="k">{{ SOURCE_META[k].label }}</option>
            </select>
          </label>
          <label class="sf-field">
            <span class="sf-label">来源机构 <em>选填</em></span>
            <input
              v-model="uploadOrg"
              class="sf-input"
              type="text"
              :disabled="modalUploading"
              :placeholder="'如：国家卫健委'"
            />
          </label>
        </div>
        <p v-if="!isAdmin" class="sf-hint">
          普通账号提交的资料会先进「待审核」，由管理员审核通过后归入「官方权威资料」或「网页抓取补充」分类，
          之后才可被 AI 检索引用。
        </p>

        <p v-if="formError" class="form-error">{{ formError }}</p>

        <!-- 底部操作：[解析] [关闭]。
             「解析」放在这里而不是文件行右侧——上传完文件后，视线自然落在弹窗底部，
             按钮在那儿才接得上「我上传了文件，然后呢？」这个问题。 -->
        <div class="form-actions">
          <button v-if="canParse" class="btn btn-solid" type="button" @click="startParse">解析</button>
          <button class="btn btn-outline" type="button" @click="closeModal">关闭</button>
        </div>
      </div>
    </div>

    <!-- 上传进度（全局横幅） -->
    <transition name="fade">
      <div v-if="uploading" class="upload-banner">
        <div class="ub-row">
          <span class="ub-name">上传中：{{ uploadName }}</span>
          <span class="ub-pct">{{ uploadProgress }}%</span>
        </div>
        <div class="ub-bar"><div class="ub-fill" :style="{ width: uploadProgress + '%' }"></div></div>
      </div>
    </transition>

    <!-- 文献详情卡 / 开始阅读 -->
    <div v-if="viewerVisible" class="modal" @keydown.esc="closeViewer">
      <div class="modal-backdrop" @click="closeViewer"></div>
      <div class="modal-card viewer" role="dialog" aria-modal="true">
        <div class="modal-head">
          <h3>《{{ viewing?.title }}》</h3>
          <button class="btn btn-icon btn-ghost modal-close" type="button" aria-label="关闭" @click="closeViewer">&times;</button>
        </div>

        <!-- 基本信息卡（有就写，没有就不写） -->
        <div v-if="!viewingReading" class="detail-card">
          <div v-if="viewing" class="viewer-meta">
            <span v-if="viewing.kb" class="vm-tag">{{ (viewing.ext || '').toUpperCase() }}</span>
            <span v-if="viewing.size" class="vm-tag">{{ fmtSize(viewing.size) }}</span>
            <span v-if="viewing.pageCount" class="vm-tag">{{ viewing.pageCount }} 页</span>
            <span v-if="viewing.paper" class="vm-tag">内置文献 · 只读</span>
            <span v-if="viewing.category" class="vm-tag">{{ viewing.category }}</span>
          </div>

          <dl class="meta-list">
            <template v-if="viewing">
              <div v-if="viewing.author" class="meta-row">
                <dt>作者</dt><dd>{{ viewing.author }}</dd>
              </div>
              <div v-if="viewing.source" class="meta-row">
                <dt>出处</dt><dd>{{ viewing.source }}</dd>
              </div>
              <div v-if="viewing.publishYear" class="meta-row">
                <dt>年份</dt><dd>{{ viewing.publishYear }}</dd>
              </div>
              <div v-if="viewing.summary" class="meta-row">
                <dt>摘要</dt><dd>{{ viewing.summary }}</dd>
              </div>
              <div v-if="viewing.articleSummary" class="meta-row">
                <dt>文章总结</dt><dd>{{ viewing.articleSummary }}</dd>
              </div>
            </template>
          </dl>

          <p v-if="!hasMeta(viewing)" class="detail-empty">
            {{ viewing?.paper ? '内置文献暂无元数据。' : '系统未自动识别到作者 / 出处 / 年份 / 摘要等信息。' }}
          </p>

          <!-- 右下角：开始阅读 -->
          <div class="detail-read">
            <button class="btn btn-solid read-btn" type="button" :disabled="viewingLoading" @click="startReading">
              {{ viewingLoading ? '正在加载…' : '开始阅读' }}
            </button>
          </div>
        </div>

        <!-- 全文阅读视图（文献版式） -->
        <div v-else>
          <div v-if="viewingLoading" class="viewer-loading">正在提取全文…</div>
          <div v-else-if="viewError" class="viewer-error">{{ viewError }}</div>
          <template v-else>
            <div class="reader-wrap">
              <h2 class="reader-title">《{{ viewing?.title }}》</h2>
              <div class="viewer-text">
                <!-- PDF：逐页原图（一张图一张图，保留原始版式/图表/矢量图，不做文字叠加） -->
                <template v-if="isPdfReader">
                  <div v-for="n in pdfPages" :key="n" class="reader-page">
                    <img
                      :src="kbPageImageUrl(viewing!.kbId!, n)"
                      :alt="`第 ${n} 页`"
                      loading="lazy"
                    />
                    <div class="reader-page-label">第 {{ n }} 页 / 共 {{ viewing!.pageCount! }} 页</div>
                  </div>
                </template>
                <!-- 非 PDF（Word/文本）：正文文本（段落/表格） -->
                <template v-else>
                  <template v-for="(b, bi) in contentBlocks" :key="bi">
                    <h2 v-if="b.type === 'h' && b.level === 1" class="reader-h1">{{ b.text }}</h2>
                    <h3 v-else-if="b.type === 'h' && b.level === 2" class="reader-h2">{{ b.text }}</h3>
                    <h4 v-else-if="b.type === 'h' && b.level === 3" class="reader-h3">{{ b.text }}</h4>
                    <p v-else-if="b.type === 'p'" class="reader-p">{{ b.text }}</p>
                    <div v-else-if="b.type === 'table'" class="reader-table-wrap">
                      <table class="reader-table">
                        <thead v-if="b.rows && b.rows.length">
                          <tr>
                            <th v-for="(c, ci) in b.rows[0]" :key="ci">{{ c }}</th>
                          </tr>
                        </thead>
                        <tbody v-if="b.rows && b.rows.length > 1">
                          <tr v-for="(row, ri) in b.rows.slice(1)" :key="ri">
                            <td v-for="(c, ci) in row" :key="ci">{{ c }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                    <!-- 列表：整合资料的「危险因素 / 专家建议」两节就是列表 -->
                    <ol v-else-if="b.type === 'list' && b.ordered" class="reader-list">
                      <li v-for="(it, ii) in b.items" :key="ii">{{ it }}</li>
                    </ol>
                    <ul v-else-if="b.type === 'list'" class="reader-list">
                      <li v-for="(it, ii) in b.items" :key="ii">{{ it }}</li>
                    </ul>
                  </template>
                </template>
              </div>
            </div>
            <div class="reader-foot">
              <button class="btn btn-outline" type="button" @click="viewingReading = false">返回信息</button>
              <button
                v-if="viewing?.kb"
                class="btn btn-solid"
                type="button"
                @click="openOriginal(viewing)"
              >
                下载 / 打开原文件
              </button>
              <button class="btn btn-outline" type="button" @click="closeViewer">关闭</button>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- toast -->
    <transition name="fade">
      <div v-show="toastVisible" class="toast">{{ toastText }}</div>
    </transition>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; display: flex; flex-direction: column; }

.toolbar { margin-top: 18px; display: flex; justify-content: flex-end; }
.main { flex: 1; padding: 32px 24px 64px; }
.wrap { max-width: 1120px; margin: 0 auto; }
.page-title { font-family: var(--serif); font-weight: 700; font-size: 24px; letter-spacing: 0.5px; }
.page-sub { color: var(--ink-2); font-size: 14.5px; margin-top: 6px; }
.page-sub b { color: var(--clay); font-weight: 500; }

/* ---------- 页签 ---------- */
.tabs {
  display: flex; gap: 4px; margin-top: 20px;
  border-bottom: 1px solid var(--line);
}
.tab {
  border: 0; background: transparent; cursor: pointer;
  font-family: var(--sans); font-size: 14px; color: var(--ink-3);
  padding: 10px 16px; border-bottom: 2px solid transparent;
  transition: 0.15s; display: inline-flex; align-items: center; gap: 6px;
}
.tab:hover { color: var(--ink-2); }
.tab.on { color: var(--clay-deep); font-weight: 600; border-bottom-color: var(--clay); }
.tab-count {
  font-size: 11px; font-weight: 700; line-height: 1;
  background: var(--amber); color: #fff;
  border-radius: var(--r-full); padding: 2px 6px;
}

/* 来源机构（卡片底部的小字，不是按钮） */
.book-org {
  border: 0; background: transparent; cursor: default; padding: 0;
  font-family: var(--sans); font-size: 12px; color: var(--ink-3);
  max-width: 12ch; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* ---------- 待审核 ---------- */
.review-list { margin-top: 20px; display: flex; flex-direction: column; gap: 12px; }
.review-item {
  display: flex; align-items: flex-start; gap: 16px;
  border: 1px solid var(--amber-line); background: var(--surface);
  border-radius: var(--r-md); padding: 16px 18px; box-shadow: var(--shadow-sm);
}
.ri-main { flex: 1; min-width: 0; }
.ri-title { font-family: var(--serif); font-weight: 700; font-size: 15.5px; color: var(--ink); }
.ri-meta {
  display: flex; align-items: center; flex-wrap: wrap; gap: 10px;
  margin-top: 6px; font-size: 12.5px; color: var(--ink-3);
}
.ri-excerpt {
  margin-top: 8px; font-size: 13px; color: var(--ink-2); line-height: 1.7;
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2;
  -webkit-box-orient: vertical; overflow: hidden;
}
.ri-acts { flex: none; display: flex; gap: 8px; align-items: center; }
.ri-level { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--ink-3); white-space: nowrap; }
.ri-level-select { width: auto; min-width: 0; padding: 5px 8px; font-size: 12.5px; height: auto; }
.ri-edit { display: flex; flex-direction: column; gap: 10px; }
.ri-edit-field { display: flex; align-items: center; gap: 10px; font-size: 13px; color: var(--ink-2); }
.ri-edit-field > span { flex: none; width: 84px; }
.ri-edit-field .sf-input { flex: 1; }
.ri-edit-acts { display: flex; gap: 8px; }
@media (max-width: 720px) {
  .review-item { flex-direction: column; }
}

/* ---------- 书架 ---------- */
.shelf {
  margin-top: 22px; border: 1px solid var(--line); background: var(--surface);
  border-radius: var(--r-lg); overflow: hidden; box-shadow: var(--shadow-sm);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.shelf.drag-over { border-color: var(--clay); box-shadow: 0 0 0 4px var(--clay-soft); }
.shelf-head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 16px 22px; background: var(--clay-soft); border-bottom: 1px solid var(--line);
}
.shelf-name { font-family: var(--serif); font-weight: 700; font-size: 16.5px; }
.shelf-sub { font-size: 12.5px; color: var(--ink-3); margin-left: 10px; }
.shelf-count { font-size: 12.5px; color: var(--clay-deep); background: #fff; border: 1px solid var(--line); padding: 3px 11px; border-radius: 999px; }
.shelf-body { padding: 18px 22px; }
/* 空书架保留虚线框：它是拖拽落点的视觉提示，结构与排版来自 .empty-state */
.shelf-empty { border: 1px dashed var(--line); border-radius: var(--r-sm); }

.book-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 16px; }
.book {
  border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--bg);
  padding: 14px 16px; display: flex; flex-direction: column; gap: 8px;
  position: relative; transition: box-shadow 0.15s, border-color 0.15s;
}
.book:hover { box-shadow: var(--shadow-sm); border-color: var(--line-2); }
.book.flash { border-color: var(--clay); box-shadow: 0 0 0 3px var(--clay-soft); animation: bookFlash 2.4s ease; }
@keyframes bookFlash { 0% { background: var(--clay-soft); } 55% { background: var(--clay-soft); } 100% { background: var(--bg); } }
.book-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.book-title { font-family: var(--serif); font-weight: 700; font-size: 15.5px; line-height: 1.5; }
/* 移除按钮：形制来自 .btn，这里只管它在标题行里不被压缩 */
.book-del { flex: none; }
.book-badge {
  flex: none; background: var(--clay-soft); color: var(--clay-deep);
  font-size: 12px; padding: 3px 10px; border-radius: 999px; white-space: nowrap;
}
.book-content {
  font-size: 12.5px; color: var(--ink-2); line-height: 1.7; white-space: pre-line;
  max-height: 132px; overflow: hidden;
}
.book-content.muted { color: var(--ink-3); }
.book.expanded .book-content { max-height: none; }
.book-processing { margin-top: 2px; }
.bp-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 5px; }
.bp-label { font-size: 12px; color: var(--ink-2); }
.bp-pct { font-size: 12px; color: var(--clay-deep); font-variant-numeric: tabular-nums; }
.bp-bar { height: 5px; background: var(--clay-soft); border-radius: 999px; overflow: hidden; }
.bp-fill { height: 100%; background: var(--clay); border-radius: 999px; transition: width 0.2s; }
.book-actions { display: flex; align-items: center; gap: 12px; }

/* ---------- 弹窗 ---------- */
.modal {
  position: fixed; inset: 0; z-index: 100; display: flex; align-items: flex-start; justify-content: center;
  padding: 24px 16px; overflow-y: auto;
}
.modal-backdrop { position: absolute; inset: 0; background: rgba(43, 36, 29, 0.44); }
.modal-card {
  position: relative; width: 100%; max-width: 560px; background: var(--surface);
  border: 1px solid var(--line); border-radius: var(--r-lg); box-shadow: var(--shadow-lg);
  padding: 26px 28px; margin-top: 4vh;
}
.modal-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 4px; }
.modal-head h3 { font-family: var(--serif); font-weight: 700; font-size: 20px; }
/* 关闭按钮：× 比常规图标大一号 */
.modal-close { font-size: 20px; }
.modal-sub { font-size: 13px; color: var(--ink-3); margin-bottom: 16px; }
.modal-sub .req { color: var(--danger); }

.drop-zone {
  border: 1.5px dashed var(--clay); border-radius: var(--r-sm); background: var(--clay-soft);
  padding: 26px 18px; text-align: center; cursor: pointer; margin-bottom: 18px; transition: background 0.15s;
}
.drop-zone:hover, .drop-zone.drag-over { background: var(--amber-soft); }
.drop-zone .dz-title { font-size: 15px; font-weight: 600; color: var(--clay-deep); }
.drop-zone .dz-sub { font-size: 12.5px; color: var(--ink-3); margin-top: 5px; }

.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-field { display: flex; flex-direction: column; gap: 6px; }
.form-field.full { grid-column: 1/-1; }
.form-field label { font-size: 13px; font-weight: 500; color: var(--ink-2); }
.form-field label .req { color: var(--danger); }
.form-field label .muted { color: var(--ink-3); font-weight: 400; }
.form-field input, .form-field select, .form-textarea {
  font-size: 14.5px; color: var(--ink); border: 1px solid var(--line); border-radius: var(--r-sm); background: #fff;
  padding: 9px 12px; width: 100%; outline: none; transition: border-color 0.15s; font-family: var(--sans);
}
.form-field input:focus, .form-field select:focus, .form-textarea:focus { border-color: var(--clay); }
.form-textarea { resize: vertical; line-height: 1.7; min-height: 120px; }
.form-error { grid-column: 1/-1; font-size: 13px; color: var(--danger); }
/* ---------- 来源维度表单（上传弹窗） ---------- */
.src-form {
  display: grid; grid-template-columns: 1fr 1fr; gap: 14px;
  margin-top: 16px;
}
.sf-field { display: flex; flex-direction: column; gap: 6px; }
.sf-label { font-size: 12.5px; color: var(--ink-2); }
.sf-label em { font-style: normal; color: var(--ink-3); font-size: 11.5px; }
.sf-input {
  font-family: var(--sans); font-size: 14px; color: var(--ink);
  background: var(--bg); border: 1px solid var(--line);
  border-radius: var(--r-sm); padding: 8px 12px;
  outline: none; transition: border-color 0.15s;
}
.sf-input:focus { border-color: var(--clay); }
.sf-input:disabled { opacity: 0.6; cursor: not-allowed; }
.sf-hint {
  margin-top: 10px; font-size: 12.5px; color: var(--amber);
  background: var(--amber-soft); border: 1px solid var(--amber-line);
  border-radius: var(--r-sm); padding: 8px 12px; line-height: 1.7;
}
@media (max-width: 560px) { .src-form { grid-template-columns: 1fr; } }

.form-actions { margin-top: 20px; display: flex; justify-content: flex-end; gap: 10px; }
/* ---------- 解析预览 / 两步式核对 ---------- */
.modal-card.wide { max-width: 860px; }
.review-scroll { max-height: 72vh; overflow-y: auto; padding-right: 6px; margin-top: 4px; }
.review-sec { padding: 10px 0 14px; border-bottom: 1px dashed var(--line); }
.review-sec:last-child { border-bottom: 0; }
.review-h { font-family: var(--serif); font-weight: 700; font-size: 15px; margin: 0 0 12px; color: var(--ink); }
.review-h-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 12px; }
.review-empty { color: var(--ink-3); font-size: 13px; padding: 6px 2px 10px; }

/* ---------- 上传进度 ---------- */
.upload-banner {
  position: fixed; left: 50%; top: 18px; transform: translateX(-50%); z-index: 200;
  width: min(440px, 92vw); background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--r-lg); box-shadow: var(--shadow-lg); padding: 12px 16px;
}
.ub-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.ub-name { font-size: 13.5px; color: var(--ink); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ub-pct { font-size: 13px; color: var(--clay-deep); font-variant-numeric: tabular-nums; }
.ub-bar { height: 6px; background: var(--clay-soft); border-radius: 999px; overflow: hidden; }
.ub-fill { height: 100%; background: var(--clay); border-radius: 999px; transition: width 0.2s; }

/* ---------- 查看全文 / 详情卡（大文献阅读版式） ---------- */
.modal-card.viewer { max-width: 1080px; }
.viewer-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; }
.vm-tag { font-size: 12px; background: var(--clay-soft); color: var(--clay-deep); padding: 3px 10px; border-radius: 999px; }
.detail-card { padding: 6px 4px 4px; }
.meta-list { margin: 0; display: flex; flex-direction: column; gap: 12px; }
.meta-row { display: flex; gap: 14px; align-items: baseline; font-size: 15px; line-height: 1.8; }
.meta-row dt { flex: none; width: 56px; color: var(--ink-3); font-weight: 500; }
.meta-row dd { margin: 0; color: var(--ink); white-space: pre-wrap; word-break: break-word; }
.detail-empty { color: var(--ink-3); font-size: 13.5px; padding: 8px 2px; }
.detail-read { margin-top: 20px; display: flex; justify-content: flex-end; }
.read-btn { min-width: 148px; padding: 11px 26px; font-size: 15px; }

/* ---------- 选择文件大方框 ---------- */
.pick-box {
  border: 2px dashed var(--clay); border-radius: var(--r-md); background: var(--clay-soft);
  padding: 40px 22px; text-align: center; cursor: pointer; margin-bottom: 6px; transition: background 0.15s;
}
.pick-box:hover, .pick-box.drag-over { background: var(--amber-soft); }
.pb-icon { font-size: 34px; line-height: 1; color: var(--clay-deep); }
.pb-file { font-size: 16px; font-weight: 600; color: var(--ink); margin-top: 12px; word-break: break-word; }
.pb-meta { font-size: 13px; color: var(--ink-3); margin-top: 6px; }

/* ---------- 已选择文件独立展示框 ---------- */
.file-box {
  border: 1px solid var(--line); border-radius: var(--r-md); background: #fff;
  padding: 18px 20px; margin-top: 14px; min-height: 70px; display: flex; align-items: center;
}
.file-box.has-file { border-color: var(--clay); }
.fb-row { display: flex; align-items: center; gap: 10px; }
.fb-icon { font-size: 22px; }
.fb-name { font-size: 15px; font-weight: 600; color: var(--ink); word-break: break-word; flex: 1 1 auto; min-width: 0; }
.fb-meta { font-size: 12.5px; color: var(--ink-3); margin-top: 5px; }
.fb-tip { font-size: 12px; color: var(--clay-deep); margin-top: 5px; }
.fb-empty { color: var(--ink-3); font-size: 13.5px; }
.fb-done { flex: none; font-size: 13px; color: var(--sage-deep); background: var(--sage-soft); padding: 5px 12px; border-radius: 999px; white-space: nowrap; }
.fb-progress { margin-top: 12px; }
.fbp-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 6px; }
.fbp-label { font-size: 12.5px; color: var(--ink-2); }
.fbp-pct { font-size: 12.5px; color: var(--clay-deep); font-variant-numeric: tabular-nums; }
.fbp-bar { height: 6px; background: var(--clay-soft); border-radius: 999px; overflow: hidden; }
.fbp-fill { height: 100%; background: var(--clay); border-radius: 999px; transition: width 0.2s; }

/* ---------- 全文阅读（文献版式） ---------- */
.reader-wrap { max-height: 68vh; overflow-y: auto; background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-md); padding: 40px 52px; }
.reader-title { font-family: var(--serif); font-weight: 700; font-size: 22px; line-height: 1.5; margin-bottom: 24px; text-align: center; color: var(--ink); }
.viewer-text {
  font-size: 16px; line-height: 2; color: var(--ink); font-family: var(--serif);
}
.reader-h1 { font-size: 1.28em; font-weight: 700; color: var(--ink); margin: 26px 0 10px; padding-bottom: 6px; border-bottom: 1px solid var(--line); }
.reader-h2 { font-size: 1.14em; font-weight: 700; color: var(--ink); margin: 20px 0 8px; }
.reader-h3 { font-size: 1.02em; font-weight: 600; color: var(--clay-deep); margin: 14px 0 6px; }
.reader-p { margin: 0 0 14px; }
.reader-table-wrap { margin: 18px 0; overflow-x: auto; }
.reader-table { width: 100%; border-collapse: collapse; font-size: 14px; font-family: var(--sans); }
.reader-table th, .reader-table td { border: 1px solid var(--line); padding: 8px 12px; text-align: left; vertical-align: top; }
.reader-table th { background: var(--clay-soft); color: var(--clay-deep); font-weight: 600; }
.reader-table tbody tr:nth-child(even) td { background: var(--bg); }
/* 列表（危险因素 / 专家建议）：缩进与行距对齐正文，序号用陶土色 */
.reader-list { margin: 0 0 16px; padding-left: 22px; }
.reader-list li { margin-bottom: 6px; line-height: 1.85; }
.reader-list li::marker { color: var(--clay-deep); }
.reader-page { margin: 0 0 34px; text-align: center; }
.reader-page img {
  width: 100%;
  max-width: 980px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  background: #fff;
  display: block;
  margin: 0 auto;
}
.reader-page-label { margin-top: 8px; font-size: 12px; color: var(--clay-deep); text-align: center; }
.reader-foot { margin-top: 22px; display: flex; justify-content: flex-end; gap: 10px; }

/* ---------- toast ---------- */
.toast {
  position: fixed; left: 50%; bottom: 28px; transform: translateX(-50%); z-index: 200;
  background: var(--ink); color: #fff; padding: 11px 22px; border-radius: 999px; font-size: 14px;
  box-shadow: var(--shadow-lg);
}
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* ---------- 底栏 ---------- */
.footer { border-top: 1px solid var(--line); background: var(--surface); }
.footer .wrap { height: 76px; display: flex; align-items: center; justify-content: space-between; }
.foot-brand { font-family: var(--serif); font-weight: 700; font-size: 15px; letter-spacing: 0.5px; color: var(--ink-2); }
.foot-note { font-size: 12.5px; color: var(--ink-3); }
</style>
