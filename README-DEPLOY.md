# 多民族医学智能体 · 部署指南

## 一、这个应用是三个服务，不是两个

| 服务 | 目录 | 端口 | 技术栈 | 职责 |
| --- | --- | --- | --- | --- |
| 前端 | `frontend/` | 5173（dev） | Vue3 + TypeScript + Vite | 界面；构建后是纯静态文件 |
| 业务后端 | `backend/` | 8080 | Spring Boot 4.1.1 · Java 17 · H2 | 登录鉴权、会话消息、知识库摄取、动态与收藏 |
| AI 服务 | `ai-service/` | 8000 | FastAPI · Python | 问题理解、证据检索、回答生成 |

**三者缺一不可。** 只部署前端，页面能打开，但**登录、提问、知识库全部不可用**——
因为这三件事分别要打 `:8080` 和 `:8000`。

---

## 二、最关键的一点：`/api` 的分流在生产环境必须有人接管

开发环境靠 `frontend/vite.config.ts` 的 `server.proxy` 分流：

| 路径前缀 | 转发目标 |
| --- | --- |
| `/api/auth` `/api/conversations` `/api/qa` `/api/kb` `/api/dynamics` `/api/favorites` | Spring Boot `:8080` |
| 其余 `/api/*`（`/api/understand`、`/api/generate`、`/api/evidence-pool`、`/api/ai/*` …） | FastAPI `:8000` |

**`server.proxy` 只在 dev server 生效，生产构建里根本不存在。**

而前端 `request.ts` 的 `baseURL` 默认是 `/api`——上线后如果没人接管这个前缀，
所有接口都会打到前端自己的域名上，**全部 404**。

### 所以生产环境需要一个反向代理

把前端静态文件 + 两个后端放在**同一个域名**下（推荐）：

```nginx
server {
    listen 80;
    server_name your-domain.com;

    root /var/www/multiethnic/dist;   # frontend 的构建产物
    index index.html;

    # SPA 路由回退：直接访问 /chat、/kb、/backend 并刷新时不 404
    location / {
        try_files $uri $uri/ /index.html;
    }

    # ① 业务后端（Spring Boot）
    location ~ ^/api/(auth|conversations|qa|kb|dynamics|favorites)(/|$) {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # ② AI 服务（FastAPI）—— 其余 /api/*
    location /api/ {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # 大模型生成慢：前端 axios 超时设的是 120s，反代不能比它短
        proxy_read_timeout 180s;
        proxy_buffering off;
    }
}
```

> 用正则 location 包住那 6 个前缀，而不是靠 nginx 的前缀匹配——
> nginx 对**前缀** location 取最长匹配，写成 `/api/auth/` 之类容易被 `/api/` 吃掉。
> 顺序上把更具体的放在前面更保险。

**用同一域名的最大好处：所有请求都变成同源，两个后端的 CORS 一行都不用改。**

（它们目前都只放行本地——Spring Boot 是
`allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")`，
AI 服务是 `allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"]`。
放在同域下就完全不涉及跨域。）

### 如果一定要前后端分离部署

**不推荐**，因为要同时解决两件事：

1. 两个后端都得改 CORS，各自加上前端的域名；
2. 但 axios 只有**一条** `baseURL`，指不到两个后端——还是得再加一层网关。

也就是说分离部署没有省掉反向代理，只是把它挪了个位置，还多出 CORS 的维护成本。

---

## 三、环境变量

`frontend/` 下复制模板：

```bash
cp frontend/.env.production.example frontend/.env.production
```

| 场景 | `VITE_API_BASE` | 说明 |
| --- | --- | --- |
| 前端与反代**同域** | 留空 | `baseURL` 回退成同源 `/api`，**推荐** |
| 前端与反代**跨域** | `https://api.example.com` | 填反代地址，不带结尾斜杠 |

没有 `.env.production` 时行为与「同域」相同（回退到 `/api`）。

---

## 四、部署步骤

1. **构建前端**
   ```bash
   cd frontend
   npm ci
   npm run build        # 产物在 frontend/dist/
   ```
2. 把 `frontend/dist/` 放到 web 服务器根目录
3. **启动两个后端**
   - 业务后端：`backend/restart-backend.bat`（Windows），或 `./mvnw spring-boot:run`
   - AI 服务：`ai-service/restart-ai.bat`（Windows），或 `uvicorn app.main:app --port 8000`
4. 按第二节配置 nginx 并 reload

### 关于 Netlify

`netlify.toml` 已配置 `base = "frontend"`、`publish = "dist"`、`NODE_VERSION = 24`
（`package.json` 要求 `^22.18.0 || >=24.12.0`）。

但 **Netlify 只能托管静态文件，跑不了 Java 也跑不了 Python**。所以：

| 做法 | 结果 |
| --- | --- |
| 纯 Netlify | 页面能打开，登录 / 提问 / 知识库全部 404 |
| Netlify + 自建反代 | 把 `VITE_API_BASE` 指向反代地址即可 |

---

## 五、验证清单

部署完按顺序过一遍，任何一步不通就先看浏览器 Network 里 `/api/*` 打到了哪个地址：

- [ ] 打开网址能看到首页
- [ ] 直接访问 `/chat` 并**刷新**，不 404 → SPA 回退生效
- [ ] 能**登录** → Spring Boot（:8080）通了
- [ ] 能**提问并出大模型回答** → AI 服务（:8000）通了
- [ ] 能**上传资料并解析入库** → 知识库链路通了
- [ ] 点「依据文献」里的片段能跳原文 → 全文接口通了
