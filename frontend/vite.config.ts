import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import type { ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// 内网穿透（Cloudflare Tunnel）会把请求的 Host 换成 *.trycloudflare.com，
// Vite 默认只放行 localhost，不加白名单会 403。`.trycloudflare.com` 匹配任意子域名。
// dev（server）和演示部署（preview）都要过隧道，所以共用这一份。
const allowedHosts = ['.trycloudflare.com']

/**
 * 摘掉浏览器带来的 `Origin` 头，再转发给后端。
 *
 * 浏览器对**非 GET 请求（POST/PUT/DELETE）一定会带 `Origin` 头，即使是同源请求**——
 * 这是 Fetch 规范，不是跨域才有。而后端 `WebConfig.addCorsMappings` 的白名单只有
 * `http://localhost:*` 与 `http://127.0.0.1:*`，隧道域名不在其中，Spring 会判成
 * 「Invalid CORS request」直接回 **403**，前端解析不出内容就显示兜底的「网络异常，请稍后重试」。
 *
 * 这也解释了为什么只有部分操作挂：GET 请求浏览器不带 Origin，所以首页、健康检查全都正常；
 * 只有登录、注册、发消息这类 POST 才会中招。
 *
 * 走代理转发时后端看到的本来就该是同源请求，所以这里把 Origin 摘掉，让后端按
 * 非 CORS 请求正常处理。**隧道网址每次重启都会变**，摘头这种方式不依赖具体域名，一劳永逸；
 * 往白名单里加域名则每换一次网址就要改一次。
 *
 * 注：Python 那边（FastAPI/Starlette）没有这个问题——它的 CORS 中间件遇到不认识的
 * Origin 只是不加响应头，不会拒绝请求。所以这个坑只在 Spring 这条链路上。
 */
function apiProxy(target: string): ProxyOptions {
  return {
    target,
    changeOrigin: true,
    configure(proxy) {
      proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))
    },
  }
}

const BACKEND = 'http://127.0.0.1:8080'
const AI = 'http://127.0.0.1:8000'

// /api 代理规则：dev 和 preview 完全一致。
// 注意顺序：8080 的各具体前缀必须排在下面 /api 兜底之前 —— 否则会被转发到 Python，
// 拿到 FastAPI 的 {"detail":"Not Found"}，前端弹一个「Not Found」的错误提示。
const proxy: Record<string, ProxyOptions> = {
  // 登录 / 会话（会话管理 + 问答编排）走 Spring Boot 后端
  '/api/auth': apiProxy(BACKEND),
  '/api/conversations': apiProxy(BACKEND),
  '/api/qa': apiProxy(BACKEND),
  // 知识库业务接口
  '/api/kb': apiProxy(BACKEND),
  // 动态 / 收藏
  '/api/dynamics': apiProxy(BACKEND),
  '/api/favorites': apiProxy(BACKEND),
  // 高级检索历史
  '/api/search-history': apiProxy(BACKEND),
  // 知识缺口 / 需求分析
  '/api/gaps': apiProxy(BACKEND),
  // 用户反馈留言板
  '/api/feedback': apiProxy(BACKEND),
  // 后端健康检查（AI 服务的健康检查是 /api/ai/health，走下面的兜底）
  '/api/health': apiProxy(BACKEND),
  // 其余 /api 走 Python FastAPI 后端：AI 检索 / 回答生成等（/api/ai/*）
  '/api': apiProxy(AI),
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: true,
    port: 5173,
    allowedHosts,
    proxy,
  },
  // 演示部署：`npm run build` 后用 `vite preview` 提供打包产物。
  // 相比 dev 模式少几百个模块请求，走慢隧道（中国 → 美国节点 → 回来）快得多。
  // 代理与白名单与 dev 一致。
  //
  // 深链接回落（/chat 直接刷新也能打开）不需要额外配置：`appType` 是**根级**选项、
  // 默认就是 'spa'，preview 会继承它。（早先误把它写进 preview 块里，vue-tsc 报
  // TS2769 —— PreviewOptions 里没有这个字段。）
  preview: {
    host: true,
    port: 5173,
    allowedHosts,
    proxy,
  },
})
