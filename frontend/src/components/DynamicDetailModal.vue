<script setup lang="ts">
/**
 * 动态详情弹窗 —— 用「智能对话」的聊天记录样式展示整段分享的对话。
 *
 * 为什么是一个组件而不是 HTML 字符串：依据文献要可点击、能弹原文并定位，
 * 用 `v-html` 拼字符串做不到交互。这里用真实的元素渲染，顺便也自动获得
 * Vue 的转义保护（只有 Markdown 正文那一层用 v-html，且已先转义）。
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { DynamicItem } from '@/api/dynamic'
import { dynamicAuthor, timeAgo } from '@/api/dynamic'
import { avatarInitial, avatarStyleOf } from '@/utils/avatar'
import type { CandidateEvidence } from '@/api/qa'
import { mdToHtml } from '@/utils/markdown'
import { extractTags } from '@/utils/medicalVocab'
// 溯源按分区展示（PDF 原页 / 正文片段），与问答页、后台共用同一个组件
import EvidenceSourceViewer from '@/components/EvidenceSourceViewer.vue'
import SourceBadge from '@/components/SourceBadge.vue'

const props = defineProps<{
  modelValue: boolean
  dynamic: DynamicItem | null
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

interface Citation {
  title: string
  quote: string
  id?: string
  startOffset?: number
  endOffset?: number
  matchedTerms?: string[]
  /** 来源等级（一级分类）；缺省视为官方 */
  sourceLevel?: string
  /** 来源机构：国家卫健委 / 人民日报 / 用户上传 … */
  sourceOrg?: string | null
  /** 知识库分区：raw 的溯源展示 PDF 原页，integrated 展示正文片段 */
  partition?: 'integrated' | 'raw'
  /** 证据所在页码，PDF 定位用 */
  page?: string | number | null
}

interface Turn {
  question: string
  plain: string
  answer: {
    conclusion?: string
    detailed?: string
    actions?: string
    sources?: string | null
    scope?: string | null
    applicable?: string | null
    timeRegion?: string | null
    cautions?: string | null
    sections?: { title: string; content: string }[]
  } | null
  citations: Citation[]
}

/**
 * 把 detailJson 解析成对话轮次。
 * 兼容两种形态：v2 是 { version, turns: [...] }；v1（改版前分享的动态）是单条 { answer, citations }。
 */
const turns = computed<Turn[]>(() => {
  const dj = props.dynamic?.detailJson
  if (!dj) return []
  let obj: any
  try {
    obj = JSON.parse(dj)
  } catch {
    return []
  }
  const raw: any[] = Array.isArray(obj?.turns) && obj.turns.length ? obj.turns : [obj]
  return raw.map((t) => ({
    question: String(t?.question || ''),
    plain: String(t?.plain || ''),
    answer: t?.answer || null,
    citations: (t?.citations || []).map((c: any) => ({
      title: c?.title || c?.paper?.title || '',
      quote: c?.quote || '',
      id: c?.id,
      startOffset: c?.startOffset,
      endOffset: c?.endOffset,
      matchedTerms: c?.matchedTerms,
      sourceLevel: c?.sourceLevel,
      sourceOrg: c?.sourceOrg ?? null,
    })),
  }))
})

const tags = computed(() => (props.dynamic ? extractTags(props.dynamic.title) : []))

// ---------- 作者：可点击跳个人主页 ----------
const router = useRouter()
const authorName = computed(() => (props.dynamic ? dynamicAuthor(props.dynamic) : ''))
const authorInitial = computed(() => (authorName.value ? avatarInitial(authorName.value) : ''))
const authorAvatarStyle = computed(() => avatarStyleOf(props.dynamic?.avatar))

/** 点作者 → 关掉弹窗再跳转，否则弹窗会盖在新打开的资料页上 */
function goAuthor() {
  const d = props.dynamic
  if (!d) return
  visible.value = false
  router.push({ name: 'profile', query: { userId: String(d.userId) } })
}

/** 有可定位的片段才显示「可点开原文」的提示 */
function canLocate(c: Citation): boolean {
  return !!c.id && typeof c.startOffset === 'number' && c.startOffset >= 0
}

function hasEvidence(t: Turn): boolean {
  return t.citations.some((c) => c.quote || c.title)
}

// ---------- 依据文献：点开原文并定位 ----------
const docVisible = ref(false)
const docTitle = ref('')
/** 证据所属文档 id + 分区：决定弹窗展示 PDF 原页还是正文片段（判断与取数都在共用组件里） */
const docId = ref('')
const docPartition = ref<'integrated' | 'raw' | undefined>(undefined)
const docPage = ref<number | null>(null)
const docItems = ref<CandidateEvidence[]>([])
const docFocusIndex = ref<number | null>(null)

async function openDoc(cits: Citation[], index: number) {
  const target = cits[index]
  if (!target || !canLocate(target)) return
  // 同一条回答里属于同一份文献的片段一起传进去，方便在原文里逐条跳转
  const sameDoc = cits.filter((c) => c.id === target.id)
  docItems.value = sameDoc.map((c) => ({
    fragment: c.quote,
    startOffset: c.startOffset,
    endOffset: c.endOffset,
    matchedTerms: c.matchedTerms,
    page: null,
  }))
  const focus = sameDoc.indexOf(target)
  const at = focus >= 0 ? focus : 0
  docFocusIndex.value = at
  docTitle.value = `《${target.title || '文献'}》`
  // 展示什么由分区决定；拉正文 / 页数交给 EvidenceSourceViewer，这里不再预取
  docId.value = target.id ?? ''
  docPartition.value = target.partition
  const p = Number(target.page)
  docPage.value = Number.isFinite(p) && p > 0 ? p : null
  docVisible.value = true
}

// 换一条动态时关掉原文弹窗，避免张冠李戴
watch(
  () => props.dynamic?.id,
  () => {
    docVisible.value = false
    docId.value = ''
  },
)
</script>

<template>
  <el-dialog
    v-model="visible"
    width="860px"
    top="5vh"
    :show-close="false"
    class="ddm"
    align-center
  >
    <template #header>
      <div class="ddm-head">
        <button
          class="ddm-author"
          type="button"
          :disabled="!dynamic"
          :title="dynamic ? `查看「${authorName}」的主页` : ''"
          @click="goAuthor"
        >
          <span class="ddm-avatar" :style="authorAvatarStyle">{{ authorInitial }}</span>
          <span class="ddm-who">
            <span class="ddm-name">{{ authorName }}</span>
            <span class="ddm-time">{{ dynamic ? timeAgo(dynamic.createdAt) : '' }} 分享</span>
          </span>
        </button>
        <span v-if="tags.length" class="ddm-tags">
          <span v-for="t in tags" :key="t" class="ddm-tag">#{{ t }}</span>
        </span>
        <button class="btn btn-icon btn-outline ddm-x" type="button" title="关闭" @click="visible = false">×</button>
      </div>
    </template>

    <div class="ddm-body">
      <div v-if="!turns.length" class="empty-state empty-state-sm">
        <p class="empty-state-title">这条动态没有可展示的对话内容</p>
      </div>

      <!-- 对话流：一轮 = 用户气泡 + AI 回答卡 -->
      <div v-for="(t, ti) in turns" :key="ti" class="turn">
        <div v-if="t.question" class="bubble-row">
          <div class="bubble">{{ t.question }}</div>
        </div>

        <article class="ans">
          <div class="ans-cap">
            <span class="ans-badge">回答</span>
            <span class="ans-sub">有依据 · 可溯源</span>
          </div>

          <!-- 结论走 Markdown 渲染：危险因素/症状/预防/饮食/遗传这几个方面，提示词要求用
               无序列表作答，列表会落在 conclusion 里；纯文本插值会把「- 」原样显示出来 -->
          <div v-if="t.answer?.conclusion" class="ans-lead" v-html="mdToHtml(t.answer.conclusion)"></div>
          <div v-else-if="t.plain" class="ans-lead">{{ t.plain }}</div>

          <section v-if="t.answer?.detailed" class="ans-sec">
            <div class="ans-sec-title">文献数据说明</div>
            <div class="ans-sec-body" v-html="mdToHtml(t.answer.detailed)"></div>
          </section>

          <section v-if="t.answer?.actions" class="ans-sec">
            <div class="ans-sec-title">专家行动建议</div>
            <div class="ans-sec-body" v-html="mdToHtml(t.answer.actions)"></div>
          </section>

          <!-- 旧结构的分节（改版前的动态仍留在库里） -->
          <section v-for="(s, si) in t.answer?.sections || []" :key="si" class="ans-sec">
            <div v-if="s.title" class="ans-sec-title">{{ s.title }}</div>
            <div class="ans-sec-body" v-html="mdToHtml(s.content)"></div>
          </section>

          <section
            v-if="t.answer?.applicable || t.answer?.timeRegion || t.answer?.cautions || t.answer?.sources || t.answer?.scope"
            class="ans-sec"
          >
            <template v-if="t.answer.sources || t.answer.scope">
              <!-- 新「三件套」：证据来源 / 适用范围 / 使用边界 三张独立小卡片 -->
              <div class="ans-trio">
                <div v-if="t.answer.sources && t.answer.sources !== '—'" class="ans-card">
                  <div class="ans-card-title">📚 证据来源</div>
                  <div class="ans-card-body" v-html="mdToHtml(t.answer.sources)"></div>
                </div>
                <div class="ans-card">
                  <div class="ans-card-title">🎯 适用范围</div>
                  <div
                    class="ans-card-body"
                    v-html="mdToHtml(t.answer.scope && t.answer.scope !== '—' ? t.answer.scope : '⚠️ 该文献未明确说明适用范围，请谨慎参考')"
                  ></div>
                </div>
                <div class="ans-card">
                  <div class="ans-card-title">⚠️ 使用边界</div>
                  <div
                    class="ans-card-body"
                    v-html="mdToHtml(t.answer.cautions && t.answer.cautions !== '—' ? t.answer.cautions : '该文献未明确说明使用边界，建议结合个人实际情况咨询专业医生。')"
                  ></div>
                </div>
              </div>
            </template>
            <template v-else>
              <div class="ans-sec-title">文献来源与专家提醒</div>
              <div v-if="t.answer.applicable && t.answer.applicable !== '—'" class="ans-scope">
                <b>适用人群</b>：{{ t.answer.applicable }}
              </div>
              <div
                v-if="t.answer.timeRegion && !String(t.answer.timeRegion).includes('未明确')"
                class="ans-scope"
              >
                <b>研究时间/地区</b>：{{ t.answer.timeRegion }}
              </div>
              <div v-if="t.answer.cautions && t.answer.cautions !== '—'" class="ans-scope warn">
                {{ t.answer.cautions }}
              </div>
            </template>
          </section>

          <!-- 依据文献：可点开原文并定位 -->
          <div v-if="hasEvidence(t)" class="ans-fold">
            <div class="ans-fold-head">
              依据文献（{{ t.citations.filter((c) => c.quote || c.title).length }}）
              <span v-if="t.citations.some(canLocate)" class="ans-fold-hint">点击可查看原文</span>
            </div>
            <button
              v-for="(c, ci) in t.citations.filter((x) => x.quote || x.title)"
              :key="ci"
              class="cit"
              :class="{ locatable: canLocate(c) }"
              type="button"
              :disabled="!canLocate(c)"
              @click="openDoc(t.citations, t.citations.indexOf(c))"
            >
              <span class="cit-src">
                《{{ c.title || '来源资料' }}》
                <SourceBadge :level="c.sourceLevel" :org="c.sourceOrg" show-org />
              </span>
              <span class="cit-text">“{{ c.quote }}”</span>
              <span v-if="canLocate(c)" class="cit-go">查看原文 ›</span>
            </button>
          </div>
        </article>
      </div>
    </div>

    <template #footer>
      <button class="btn btn-outline ddm-close" type="button" @click="visible = false">关闭</button>
    </template>
  </el-dialog>

  <!-- 原文：从依据文献点进来的，自动定位并高亮该片段 -->
  <el-dialog
    v-model="docVisible"
    :title="docTitle || '文献原文'"
    width="72%"
    top="6vh"
    append-to-body
    destroy-on-close
  >
    <EvidenceSourceViewer
      v-if="docId"
      :doc-id="docId"
      :title="docTitle"
      :partition="docPartition"
      :page="docPage"
      :evidence="docItems"
      :focus-index="docFocusIndex"
    />
    <div v-else class="ddm-empty">这条依据没有可定位的原文</div>
  </el-dialog>
</template>

<style scoped>
/* ---------- 头部：发布者信息 ---------- */
.ddm-head { display: flex; align-items: center; gap: 12px; padding-right: 6px; }
/* 头像 + 昵称整体可点，跳 TA 的个人主页 */
.ddm-author {
  display: flex; align-items: center; gap: 12px;
  flex: 1; min-width: 0;
  border: 0; background: none; padding: 0;
  cursor: pointer; text-align: left; font-family: var(--sans);
}
.ddm-author:disabled { cursor: default; }
/* 悬停时只给名字加提示（头像本身没有文字，变色反而不明显） */
.ddm-author:not(:disabled):hover .ddm-name { color: var(--clay-deep); text-decoration: underline; }
.ddm-avatar {
  flex: none; width: 40px; height: 40px; border-radius: 50%;
  display: grid; place-items: center;
  color: #fff; font-family: var(--serif); font-weight: 700; font-size: 17px;
}
.ddm-who { flex: 1; min-width: 0; }
.ddm-name { display: block; font-size: 14.5px; font-weight: 600; color: var(--ink); }
.ddm-time { display: block; font-size: 12px; color: var(--ink-3); margin-top: 2px; }
.ddm-tags { display: flex; gap: 6px; flex: none; }
.ddm-tag {
  font-size: 11.5px; color: var(--sage-deep); background: var(--sage-soft);
  border-radius: 999px; padding: 1px 9px;
}
/* 关闭按钮：× 比常规图标大一号 */
.ddm-x { font-size: 18px; }

/* ---------- 对话流 ---------- */
.ddm-body {
  max-height: 64vh;
  overflow-y: auto;
  padding: 4px 2px 8px;
  background: var(--bg);
  border-radius: var(--r-md);
  padding-inline: 16px;
}
.turn + .turn { margin-top: 26px; }
.ddm-empty { text-align: center; color: var(--ink-3); font-size: 13.5px; padding: 40px 0; }

/* 用户气泡：右对齐、陶土底，与智能对话页一致 */
.bubble-row { display: flex; justify-content: flex-end; margin-bottom: 12px; }
.bubble {
  max-width: 76%;
  background: var(--clay); color: #fff;
  padding: 10px 15px; border-radius: 15px 15px 4px 15px;
  font-size: 14.5px; line-height: 1.7;
  word-break: break-word;
}

/* AI 回答卡 */
.ans {
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 14px; padding: 16px 20px; box-shadow: var(--shadow-sm);
}
.ans-cap { display: flex; align-items: center; gap: 9px; margin-bottom: 11px; }
.ans-badge {
  font-family: var(--serif); font-size: 13px; font-weight: 700; color: var(--clay-deep);
}
.ans-sub { font-size: 11.5px; color: var(--ink-3); }
.ans-lead { font-size: 14.5px; line-height: 1.85; color: var(--ink); }

/* 与 QaView 的回答卡片保持同一套区块标题样式：
   小字号 + 主色 + 左侧短竖线。同一批回答在两处渲染，样式不一致会很明显。 */
.ans-sec { margin-top: 22px; }
.ans-sec-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--clay-deep);
  letter-spacing: 0.02em;
  line-height: 1.5;
  padding-left: 9px;
  border-left: 2px solid var(--clay);
  margin-bottom: 7px;
}
.ans-sec-body { font-size: 13.5px; line-height: 1.85; color: var(--ink-2); }
/* 列表与引用块：mdToHtml 会产出这两种块，弹窗里此前没有对应样式，会落回浏览器默认
   （40px 缩进、外间距过大），与旁边的正文不搭。结论区与分节区共用。 */
.ans-lead :deep(.ans-list),
.ans-sec-body :deep(.ans-list) { margin: 8px 0 4px; padding-left: 22px; }
.ans-lead :deep(.ans-list li),
.ans-sec-body :deep(.ans-list li) { margin-bottom: 4px; }
.ans-lead :deep(.ans-list li::marker),
.ans-sec-body :deep(.ans-list li::marker) { color: var(--clay-deep); }
.ans-lead :deep(.ans-quote),
.ans-sec-body :deep(.ans-quote) {
  margin: 10px 0; padding: 8px 12px;
  border-left: 3px solid var(--accent-line); background: var(--bg);
  color: var(--ink-2); border-radius: 0 4px 4px 0;
}
.ans-sec-body :deep(.ans-table) {
  border-collapse: collapse; width: 100%; max-width: 460px;
  margin-top: 8px; font-size: 13px;
}
.ans-sec-body :deep(.ans-table th),
.ans-sec-body :deep(.ans-table td) {
  border: 1px solid var(--line); padding: 6px 10px; text-align: left;
}
.ans-sec-body :deep(.ans-table th) { background: var(--bg); font-weight: 600; }
.ans-scope { font-size: 13.5px; color: var(--ink-2); line-height: 1.8; }
.ans-scope.warn { color: var(--amber); }
.ans-scope b { color: var(--ink); }

/* 三件套：证据来源 / 适用范围 / 使用边界 三张独立小卡片 */
.ans-trio { display: flex; flex-wrap: wrap; gap: 10px; }
.ans-card {
  flex: 1 1 200px; min-width: 180px;
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 10px; padding: 10px 12px;
}
.ans-card-title { font-size: 12px; font-weight: 700; color: var(--clay-deep); letter-spacing: 0.02em; margin-bottom: 6px; }
.ans-card-body { font-size: 13px; color: var(--ink-2); line-height: 1.7; }

/* ---------- 依据文献 ---------- */
.ans-fold { margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--line); }
.ans-fold-head {
  font-size: 12.5px; color: var(--ink-3); margin-bottom: 8px;
  display: flex; align-items: baseline; gap: 8px;
}
.ans-fold-hint { font-size: 11.5px; color: var(--clay); }
.cit {
  display: block; width: 100%; text-align: left; cursor: pointer;
  border: 1px solid var(--line); border-left: 3px solid var(--accent-line);
  background: var(--bg); border-radius: 0 8px 8px 0;
  padding: 9px 12px; margin-bottom: 7px; transition: 0.15s;
  font-family: var(--sans);
}
.cit:disabled { cursor: default; }
.cit.locatable:hover { background: var(--sage-soft); border-left-color: var(--sage); }
.cit-src { display: block; font-size: 12px; font-weight: 600; color: var(--clay-deep); margin-bottom: 3px; }
.cit-text { display: block; font-size: 13px; line-height: 1.75; color: var(--ink-2); }
.cit-go { display: block; margin-top: 4px; font-size: 11.5px; color: var(--clay); }

.ddm-close { padding: 7px 26px; }

@media (max-width: 640px) {
  .ddm-body { max-height: 70vh; padding-inline: 10px; }
  .bubble { max-width: 88%; }
  .ddm-tags { display: none; }
}
</style>
