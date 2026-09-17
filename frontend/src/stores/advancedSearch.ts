import { defineStore } from 'pinia'
import type { EvidencePoolResult, GenerateAnswer, GenerateResult, IntentCode, PoolEvidence } from '@/api/qa'
import type { KbVocab } from '@/api/kb'

/** 一次检索的三个槽位（「民族 + 疾病 + 想了解的方面」） */
export interface SearchSlots {
  ethnicity: string
  disease: string
  intent: IntentCode
}

/** 结果区展示的内容。实时检索与历史还原写的是同一个形状，所以两条路径共用一套模板。 */
export interface SearchResult {
  answer: GenerateAnswer | null
  evidence: PoolEvidence[]
}

/** 未命中：给出「实际收录的是哪个方面」，而不是一句死胡同 */
export interface SearchMiss {
  reason: string
  alternatives: IntentCode[]
}

/**
 * 高级检索 · 页面状态
 *
 * 为什么放在 store 而不是组件里：这个页面**不在 keep-alive 名单里**（只有对话页在），
 * 点顶栏切到知识库 / 动态再切回来，组件会整个卸载重建 —— 组件内的 ref 全部清零，
 * 用户的角度就是「检索结果莫名其妙没了」。状态提到 store 后，重挂载时直接接着用。
 *
 * 为什么连 `pool` / `generate` 这两份**原始返回**也要留着：
 * 「查看后台分析」要把这次检索的完整链路交给后台工作台。只留 `result`（答案 + 打包证据）
 * 是不够的——后台的知识检索模块渲染的是 `candidates`（候选资料与命中片段），
 * 那份数据只在 pool 里。留在手上，点按钮时直接交出去，不必再打一次接口。
 *
 * 刻意**不**持久化到 localStorage：需求只要求「切 Tab 不丢」，刷新回到初始态与对话页一致
 * （对话页靠 keep-alive，刷新同样丢当前视图）。而且 `result` 里带着整份证据片段，
 * 每检索一次就序列化写盘并不划算。
 */
export const useAdvancedSearchStore = defineStore('advancedSearch', {
  state: () => ({
    // ---------- 下拉选择与词表 ----------
    ethnicity: '',
    disease: '',
    intent: '' as IntentCode | '',
    /** 词表也留在 store：重挂载时不必再拉一次，下拉不会先空一帧再填上 */
    vocab: null as KbVocab | null,
    vocabError: '',

    // ---------- 检索 ----------
    /** 结果区当前展示的是**哪一次**检索（与上面三个输入框是两回事，见组件内注释） */
    shown: null as SearchSlots | null,
    result: null as SearchResult | null,
    miss: null as SearchMiss | null,
    searching: false,
    /** 侧栏中高亮的那条历史记录 */
    activeHistoryId: null as number | null,

    // ---------- 知识缺口（未命中时登记，「反馈缺文献」用） ----------
    /**
     * 本次未命中对应的缺口 id（由服务端返回）。
     * 跟着 `shown` 一起留存：切 Tab 回来还能接着反馈，不必重新检索一次。
     */
    gapId: null as number | null,
    /** 这一次是否已经反馈过 —— 只用于按钮回显，去重由服务端按 (缺口, 用户) 保证 */
    gapFeedbackSent: false,

    // ---------- 交给后台分析工作台的原始返回 ----------
    /**
     * 本次结果对应的证据池原始返回。
     * 与 `shown` 是**一对**：历史记录还原出来的结果没有链路的原始返回，
     * 所以 `openHistory` 会把这两项清空——否则「查看后台分析」会把上一次检索的
     * 链路当成这一条交出去，后台显示的就是张冠李戴的分析。
     *
     * 命名带 raw 前缀是为了和接口返回的局部变量区分开（组件里 `generate` 已经是导入的函数名）。
     */
    rawPool: null as EvidencePoolResult | null,
    rawGenerate: null as GenerateResult | null,
  }),

  actions: {
    /** 退出登录 / 切换账号：清空检索页状态，避免跨账号残留上一个人的检索记录 */
    reset() {
      this.ethnicity = ''
      this.disease = ''
      this.intent = ''
      this.vocab = null
      this.vocabError = ''
      this.shown = null
      this.result = null
      this.miss = null
      this.searching = false
      this.activeHistoryId = null
      this.gapId = null
      this.gapFeedbackSent = false
      this.rawPool = null
      this.rawGenerate = null
    },
  },
})
