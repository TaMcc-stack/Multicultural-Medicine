/**
 * 民族 / 疾病受控词表。
 *
 * 原先这份表只存在于 QaView.vue 里（用于接口不可用时的关键词降级）。
 * 动态页要「从标题里提取民族和疾病当标签」，也需要同一份词表——
 * 抽到这里共用，避免两处各维护一份后逐渐不一致。
 */

export const ETHNICS = [
  '白族', '傣族', '哈尼族', '汉族', '苗族', '彝族', '维吾尔族', '藏族', '回族',
  '蒙古族', '朝鲜族', '景颇族', '傈僳族', '佤族', '普米族', '布朗族', '纳西族',
  '土家族', '满族', '布依族', '壮族', '畲族', '哈萨克族',
]

export const DISEASES = [
  '糖尿病', '高血压', '代谢综合征', 'CKM', '心脏瓣膜病', '非酒精性脂肪性肝病',
  'NAFLD', '脂肪肝', '肥胖', '乙型肝炎', '乙肝', '慢性肾病',
  '视网膜病变', '高尿酸血症', '痛风',
]

/** 从文本里找第一个命中的词（长词优先，避免「糖尿病」被「糖尿」抢先匹配） */
function firstMatch(text: string, list: string[]): string {
  const sorted = [...list].sort((a, b) => b.length - a.length)
  return sorted.find((k) => text.includes(k)) || ''
}

/**
 * 从动态标题里提取标签。
 *
 * 后端**不返回 tags 字段**，所以只能从标题推断——动态标题就是用户问的第一个问题，
 * 里面通常写着民族和疾病（如「哈尼族的心脏瓣膜病患病率高吗？」）。
 * 一个都提不出来时返回空数组，卡片上自动隐藏标签栏。
 */
export function extractTags(title: string, limit = 3): string[] {
  const t = title || ''
  const out: string[] = []
  const eth = firstMatch(t, ETHNICS)
  if (eth) out.push(eth)
  const dis = firstMatch(t, DISEASES)
  if (dis) out.push(dis)
  return out.slice(0, limit)
}

/**
 * 从文本里分别取出「民族」与「疾病」两个槽位。
 *
 * 与 `extractTags` 的区别是**返回值分得清哪个是哪个**：那个返回的是一个混在一起的数组，
 * 只命中一个时无从判断它是民族还是疾病。需求分析页要拿这两个值去建知识缺口，
 * 必须有明确的归属，所以单独给一个函数而不是让调用方去猜数组的第 0 项是什么。
 * 任一项没识别出来就是空串，调用方据此禁用「转为知识缺口」。
 */
export function extractSlots(text: string): { ethnicity: string; disease: string } {
  const t = text || ''
  return { ethnicity: firstMatch(t, ETHNICS), disease: firstMatch(t, DISEASES) }
}
