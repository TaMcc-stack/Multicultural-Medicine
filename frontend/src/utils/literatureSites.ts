/**
 * 外部文献站的检索入口（文献补录工作台用）。
 *
 * **为什么集中在这里**：这是全项目唯一一处「指向外部站点」的链接生成逻辑，
 * 站点改版时只改这一个文件。分散在组件里，改一处漏一处。
 *
 * **哪些验证过**：只有 PubMed 那条是实测过的（`?term=` 能被正确解析，
 * 且它对中文词的处理很差——所以关键词请用中文民族名 + 中文疾病名时自行判断）。
 * 知网与 SinoMed 的检索 URL 形态**没有验证过**（开发环境出不了网），
 * 所以工作台上还配了「复制关键词」按钮：链接万一失效，关键词仍是一键可得，
 * 粘到站内搜索框里照样能用。
 *
 * SinoMed 另有硬限制：它检索需要登录 + 验证码，服务端抓不到它的页面
 * （见 application.properties 里导入白名单那条注释），只能人工检索后粘贴正文。
 */
export interface LiteratureSite {
  name: string
  /** 把关键词拼成该站的检索地址 */
  build: (keyword: string) => string
}

export const LITERATURE_SITES: LiteratureSite[] = [
  {
    name: 'PubMed',
    build: (kw) => `https://pubmed.ncbi.nlm.nih.gov/?term=${encodeURIComponent(kw)}`,
  },
  {
    name: '知网',
    build: (kw) => `https://kns.cnki.net/kns8s/defaultresult/index?kw=${encodeURIComponent(kw)}`,
  },
  {
    name: 'SinoMed',
    // 检索要登录，进站后自己搜；这里只保证把人送到站内
    build: () => 'https://www.sinomed.ac.cn/',
  },
]
