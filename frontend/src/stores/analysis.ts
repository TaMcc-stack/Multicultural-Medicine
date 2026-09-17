import { defineStore } from 'pinia'
import { evidencePool, generate } from '@/api/qa'
import type { EvidencePoolResult, GenerateResult, KnowledgeDoc, Understanding, StoredQuestion, TurnContext } from '@/api/qa'

/**
 * 分析工作台 · 共享状态
 *
 * 前台两个入口（智能对话页、高级检索页）与后台分析工作台共用的单一数据源：
 * 当前验证的问题 + 知识证据池结果（evidence-pool）+ 加工栏结果（generate）。
 *
 * 之所以集中到这里，是为了解决两个问题：
 *  - 后台不再「每次进入都独立重跑一遍 RAG」，命中缓存直接复用，避免加载滞后；
 *  - 登录 / 退出 / 401 时整体重置，避免跨账号残留上一把的后台处理记录。
 *
 * **按来源分成两条独立记录**（dialogue / search），不是共用一个槽位。
 * 原先只有一个槽位时，两条写入路径互相覆盖：高级检索检索到一半，用户切到对话页问一句，
 * 先完成的那一方会被后完成的那一方静默顶掉——后台看到的是谁最后跑完就是谁。
 * 检索要打两次大模型（evidence-pool + generate），几十秒很正常，这个窗口足够用户切走再问一句。
 * 拆开之后两条各存各的，后台用「来源」开关选择要看哪一条。
 */

/**
 * 分析的来源入口。
 * `dialogue` = 智能对话页（走 `load`，会真的跑一遍 RAG）
 * `search`   = 高级检索页（走 `adopt`，只登记已经算好的结果，不重跑）
 */
export type AnalysisSource = 'dialogue' | 'search'

/**
 * 一次 RAG 跑到哪个阶段了。供前台的「分步进度提示」推进用。
 *
 * 用字符串而不是数字：数字会和「第几步」的语义纠缠（组件那边 0/1/2 是**步骤序号**，
 * 这里是**后端在做什么**），将来在检索与生成之间插一步时两边容易错位。
 * `retrieve` = 正在等 evidence-pool；`generate` = 正在等 generate。
 */
export type AnalysisStage = 'idle' | 'retrieve' | 'generate'

/** 一条来源的分析记录：问题 + 两次调用的原始返回 + 缓存签名 */
export interface AnalysisRecord {
  question: StoredQuestion | null
  pool: EvidencePoolResult | null
  generate: GenerateResult | null
  /** 已算完的签名；与当前问题一致时说明缓存仍有效 */
  computedSig: string
  loading: boolean
  /**
   * 当前阶段。前台靠它把进度条推进到正确的步骤——`load()` 内部连续 await 了两个接口，
   * 调用方只看到「一个 await 吞掉两步」，没有这个字段就分不出检索完成没完成。
   */
  stage: AnalysisStage
}

function emptyRecord(): AnalysisRecord {
  return { question: null, pool: null, generate: null, computedSig: '', loading: false, stage: 'idle' }
}

/**
 * 当前「问题＋理解」签名：与缓存一致时说明结果仍有效，可直接复用。
 *
 * 不含 `source`：签名只在**同一条来源的记录内部**比对，而一条记录里来源是恒定的
 * （对话页写的 localStorage 从不带 source 字段）。跨来源的「同一个问题被两边各问一次」
 * 由记录本身隔开，不再需要靠签名区分。
 */
function sig(q: StoredQuestion | null): string {
  if (!q) return ''
  return [
    q.text,
    q.ethnic,
    q.disease,
    q.intent,
    q.questionType,
    q.standardized,
    q.confidence,
  ].join('|')
}

export const useAnalysisStore = defineStore('analysis', {
  state: () => ({
    /** 两条来源各存一条记录；键固定，不随使用增长 */
    records: {
      dialogue: emptyRecord(),
      search: emptyRecord(),
    } as Record<AnalysisSource, AnalysisRecord>,
    /** 后台工作台默认展示哪一条；写入方（load / adopt）会把它设成自己那一侧 */
    activeSource: 'dialogue' as AnalysisSource,
    /**
     * 后台要追溯的历史问答。由对话页点「查看后台分析」写入，后台工作台读取。
     * null 表示后台跟随前台当前问题（常规模式）。
     * 用共享状态而不是路由参数：点击时不跳转，用户仍停留在对话页。
     */
    traceTarget: null as { messageId: number; conversationId: number | null } | null,
  }),

  getters: {
    /**
     * 显式取某一侧的记录。
     *
     * 调用方**必须**用这两个而不是猜 `activeSource`：对话页在 `load()` 之后要读自己那份结果，
     * 而这期间高级检索可能刚好跑完并把 `activeSource` 抢过去——读 activeSource 就会读到别人的。
     */
    dialogueRecord: (s): AnalysisRecord => s.records.dialogue,
    searchRecord: (s): AnalysisRecord => s.records.search,
  },

  actions: {
    /** 后台「来源」开关切换时调用 */
    setActiveSource(source: AnalysisSource) {
      this.activeSource = source
    },

    /**
     * 统一 RAG 链路：知识证据池 evidence-pool → 加工栏 generate。
     * 若对话那条记录已有有效计算结果则直接复用（不再重跑，避免后台加载滞后）。
     *
     * 只写 `dialogue` 槽：这是对话页的链路。（高级检索走 `adopt`，只登记不重算。）
     * **返回刚写入的记录**，调用方据此读 pool / generate，不依赖 `activeSource` 的时序。
     */
    async load(q: StoredQuestion | null, literature: KnowledgeDoc[],
               history: TurnContext[] = []): Promise<AnalysisRecord> {
      const rec = this.records.dialogue
      rec.question = q
      // 开始一次**新的**分析 = 不再追溯旧的：追溯目标一旦残留，后台下次进入时会优先按
      // 那个 messageId 走追溯模式（见 BackendView.reload），于是新问题怎么问都看不到。
      this.traceTarget = null
      // 刚跑完分析的这一侧接管工作台
      this.activeSource = 'dialogue'
      if (!q || q.status !== 'clear') {
        rec.pool = null
        rec.generate = null
        rec.loading = false
        rec.computedSig = ''
        rec.stage = 'idle'
        return rec
      }
      if (rec.pool && rec.computedSig === sig(q)) {
        rec.loading = false
        // 缓存命中：两个接口都不发，进度条应当直接收场而不是停在上一轮的旧阶段
        rec.stage = 'idle'
        return rec
      }

      rec.loading = true
      // 用 Partial<Understanding>（就是 evidencePool / generate 接受的类型），
      // 不要再手写一份结构——此前这里内联了一个同形状的匿名类型，抄漏了
      // medical_advice，于是用药咨询会被当成普通问题送进检索。
      const understanding: Partial<Understanding> = {
        ethnicity: q.ethnic || '',
        disease: q.disease || '',
        intent: q.intent || '',
        question_type: q.questionType || '',
        standardized_question: q.standardized || '',
        confidence: q.confidence ?? 0,
        // 疾病缺失 = 泛化检索（按「民族 + 意图」搜）。后端据此把证据充分度降为
        // 「🟡 部分相关」，并说明「以下为民族整体健康资料，非该民族该疾病的专属数据」。
        generalized: !q.disease && !!q.ethnic && !!q.intent,
        // 医疗安全边界：后端据此跳过检索、直接返回拒绝话术
        medical_advice: !!q.medicalAdvice,
      }
      try {
        rec.stage = 'retrieve'
        const pool = await evidencePool(q.text, understanding, literature, history)
        rec.pool = pool
        rec.stage = 'generate'
        rec.generate = await generate(q.text, understanding, pool, history)
        rec.computedSig = sig(q)
      } catch {
        rec.pool = null
        rec.generate = null
        rec.computedSig = ''
      } finally {
        rec.loading = false
        rec.stage = 'idle'
      }
      return rec
    },

    /**
     * 直接采纳一份**已经算好**的分析结果（高级检索页用），写 `search` 槽。
     *
     * 与 load 的关键区别是**不重跑**：高级检索自己已经调过 evidence-pool 与 generate，
     * 再走一遍 load 就是第二次打大模型——既慢，答案又会因随机性与用户眼前那份不一致。
     * 所以这里只把结果登记进来，并把签名标成「已算完」：后台打开时缓存命中，直接复用这份。
     *
     * `literature` 参数保留在签名里是为了与 load 对齐（后台展示用），高级检索没有本地文献，
     * 调用方传空数组。
     */
    adopt(
      q: StoredQuestion | null,
      _literature: KnowledgeDoc[],
      pool: EvidencePoolResult | null,
      generate: GenerateResult | null,
    ) {
      const rec = this.records.search
      rec.question = q
      rec.pool = pool
      rec.generate = generate
      rec.loading = false
      rec.computedSig = sig(q)
      // 结果已经算好了才登记进来，不存在中间阶段
      rec.stage = 'idle'
      // 刚跑完分析的这一侧接管工作台
      this.activeSource = 'search'
      // 同上：这次检索接管工作台，残留的追溯目标会让后台去显示另一条历史问答
      this.traceTarget = null
    },

    /** 退出登录 / 401 / 切换账号：清空前台工作状态 */
    reset() {
      this.records.dialogue = emptyRecord()
      this.records.search = emptyRecord()
      this.activeSource = 'dialogue'
      this.traceTarget = null
    },

    /** 设定后台要追溯的历史问答（对话页点击时调用，不跳转） */
    setTraceTarget(messageId: number, conversationId: number | null) {
      this.traceTarget = { messageId, conversationId }
    },

    /** 退出追溯，后台回到跟随前台当前问题 */
    clearTraceTarget() {
      this.traceTarget = null
    },
  },
})
