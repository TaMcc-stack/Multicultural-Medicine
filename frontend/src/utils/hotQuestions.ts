/**
 * 首页「热门问题」。
 *
 * **当前是写死的 Mock**：后端还没有 `GET /api/conversations/hot`。
 * 接口做好之后，把 `listHotQuestions` 换成一次真实的请求即可——
 * 返回结构保持一致（字符串数组），调用方（HomeView）不用动。
 *
 * 题目都取自知识库**已收录**的民族与疾病组合（与对话页的推荐问题池同源），
 * 免得用户点了热门问题却答不上来。
 */
const MOCK_HOT_QUESTIONS: string[] = [
  '白族糖尿病患病率高吗？',
  '傣族高血压和汉族比，谁更严重？',
  '傈僳族的肥胖率是多少？',
  '哈尼族的心脏瓣膜病常见吗？',
  '苗族和彝族，哪个民族的脂肪肝患病率更高？',
  '纳西族的肥胖情况严重吗？',
  '白族饮食习惯里，哪些容易吃出糖尿病？',
  '傣族的心血管-肾脏-代谢综合征（CKM）情况如何？',
]

/** 热门问题。接口就绪前返回 Mock；将来换成 `http.get('/conversations/hot')`。 */
export function listHotQuestions(): Promise<string[]> {
  return Promise.resolve(MOCK_HOT_QUESTIONS)
}
