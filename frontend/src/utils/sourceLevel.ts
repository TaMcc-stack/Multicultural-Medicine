import type { SourceLevel } from '@/api/kb'

/**
 * 来源等级的展示元数据（一级分类）。
 *
 * 图标取 **Lucide** 的路径（24×24 网格、stroke-width 2、currentColor）——
 * 上一轮 UI 改造时评估过不引入 Lucide 依赖，只借它的图形规范，所以这里内联路径字符串。
 * 配色沿用现有 token：官方=陶土红、网页=鼠尾草绿、待审核=琥珀。
 *
 * 一级分类只有 **官方权威资料 / 网页抓取补充** 两个；
 * user_upload 不再是展示分类，仅作为「待审核中」条目的内部标记
 * （管理员通过审核时会重新裁决归入 official 或 web_crawl）。
 */
export interface SourceMeta {
  key: SourceLevel
  /** 一级分类名 */
  label: string
  /** Lucide 图标路径（内联 SVG 用） */
  icon: string
  color: string
  soft: string
  line: string
  /** 一句话说明这个等级意味着什么，给分类卡片当副标题 */
  desc: string
}

export const SOURCE_META: Record<SourceLevel, SourceMeta> = {
  official: {
    key: 'official',
    label: '官方权威资料',
    // Lucide: landmark
    icon: '<line x1="3" y1="22" x2="21" y2="22"/><line x1="6" y1="18" x2="6" y2="11"/><line x1="10" y1="18" x2="10" y2="11"/><line x1="14" y1="18" x2="14" y2="11"/><line x1="18" y1="18" x2="18" y2="11"/><polygon points="12 2 20 7 4 7"/>',
    color: 'var(--clay)',
    soft: 'var(--clay-soft)',
    line: 'var(--clay)',
    desc: '官方人员筛选、审核过的文献，可直接作为权威结论引用',
  },
  web_crawl: {
    key: 'web_crawl',
    label: '网页抓取补充',
    // Lucide: globe
    icon: '<circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>',
    color: 'var(--sage)',
    soft: 'var(--sage-soft)',
    line: 'var(--sage-line)',
    desc: '自动抓取、清洗后入库的公开网页内容，引用时会注明「据公开报道」',
  },
  user_upload: {
    key: 'user_upload',
    label: '待审核',
    // Lucide: clock
    icon: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    color: 'var(--amber)',
    soft: 'var(--amber-soft)',
    line: 'var(--amber-line)',
    desc: '用户提交的资料，管理员审核通过后才正式入库',
  },
}

/**
 * 一级分类的展示顺序——现在只剩一档。
 *
 * 「网页抓取补充」（web_crawl）已从界面撤掉：它唯一的入口是「从链接导入」，而管理员的
 * 工作流是手工整合文献，那条路径一直是空的。**取值本身仍在 `SOURCE_META` 里保留**——
 * 它是数据库列的合法取值，将来若要恢复这个分类，把它加回本数组即可。
 *
 * user_upload 也不是分类：普通用户提交的资料只出现在管理员可见的「待审核」页签里。
 */
export const SOURCE_ORDER: SourceLevel[] = ['official']

/**
 * 容错取元数据。
 *
 * 后端可能返回未知值，历史数据（加字段之前入库的）还可能整个字段缺失，
 * 一律回退到官方——那批数据本来就是官方上传的，与数据库列的 DEFAULT 一致。
 */
export function sourceMetaOf(level?: string | null): SourceMeta {
  return SOURCE_META[level as SourceLevel] || SOURCE_META.official
}

/** 按分类名归类到一级：二级分类是内容主题，一级只认来源等级 */
export function levelOf(doc: { sourceLevel?: string | null; reviewStatus?: string | null }): SourceLevel {
  if (doc.reviewStatus === 'pending') return 'user_upload'
  return (doc.sourceLevel as SourceLevel) || 'official'
}
