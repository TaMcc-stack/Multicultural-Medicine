# 多民族医学智能体 · 项目架构说明

> 本文档说明按「Python(FastAPI)=AI 服务 / Spring Boot=业务逻辑」分层整理后的项目结构与运行方式。
> 整理原则：**只重组文件与目录，不改动既有功能**；前端交互、AI 链路、会话体验保持原样。

---

## 一、分层架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  Frontend (Vue3 + Vite + Element Plus)      :5173            │
│  src/api/{request,auth,chat,qa,kb}.ts —— 全部 /api 走代理     │
└───────────────┬─────────────────────────────────────────────┘
                │  vite proxy: /api → http://127.0.0.1:8000
                ▼
┌─────────────────────────────────────────────────────────────┐
│  Python FastAPI (backend/)                    :8000          │
│  ├─ ai/        纯 AI 能力（理解/检索/生成/知识库/嵌入/LLM）   │
│  ├─ business.py 登录/注册/令牌/会话 CRUD（暂时留 Python）     │
│  ├─ kb_store.py 用户文档库（上传/查询/删除/查看全文）         │
│  └─ main.py    编排 + 路由注册                               │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Spring Boot (legacy/backend1/)               :8080          │
│  可复用的业务后端：auth / conversations(消息流) / qa / health │
│  H2 内存库（MODE=MySQL），演示账号 demo / 123456              │
│  现状：已就绪可运行，作为「业务层迁移目标」暂未接入前端代理   │
└─────────────────────────────────────────────────────────────┘
```

| 层 | 技术 | 端口 | 职责 |
| --- | --- | --- | --- |
| 前端 | Vue3 + Vite + Element Plus + axios | 5173 | UI、路由、API 调用 |
| AI 服务 | Python + FastAPI + Qwen/bge-m3 | 8000 | 问题理解、知识检索、结构化回答、知识证据池、文献/文档库 |
| 业务后端（参考） | Spring Boot 4.1.1（已复用 `legacy/backend1`） | 8080 | 登录鉴权、用户/会话管理（消息流模型）、接口管理 |

---

## 二、目录结构与职责

```
E:\MultiEthnic\多民族医学智能体\
├── backend\                      # Python FastAPI（AI 服务 + 临时业务层）
│   ├── main.py                   # 应用入口：注册 business 路由 + AI 路由，lifespan 装载知识库
│   ├── business.py               # 登录/注册/令牌签发/会话 CRUD（内存实现，迁移 Spring Boot 时仅改此处）
│   ├── kb_store.py               # 用户文档库：落盘 + 全文提取 + 元数据(index.json)
│   ├── ai\                       # 纯 AI 能力包（FastAPI 仅做编排）
│   │   ├── __init__.py
│   │   ├── config.py             # 配置 / 路径 / LLM·Embedding 引擎选择
│   │   ├── llm.py                # Qwen 调用封装
│   │   ├── embeddings.py         # bge-m3 向量 / hashing 兜底 / 分词
│   │   ├── kb.py                 # 内置 papers.json 文献 + BM25/向量混合索引
│   │   ├── understanding.py       # 问题理解（民族/疾病/意图 5 码）
│   │   ├── rag.py                # retrieve / evidence_pool 检索链路
│   │   └── answer.py             # 结构化回答生成（结论/依据/表格/图表）
│   ├── papers.json               # 内置文献数据集（10 篇）
│   └── kb_data\                  # 用户上传文档库（index.json + files/）
│
├── legacy\backend1\              # 复用的 Spring Boot 业务后端（不改动）
│   ├── pom.xml                   # Spring Boot 4.1.1 / Java 17 / H2 / MySQL 驱动 / Lombok
│   ├── mvnw / mvnw.cmd           # Maven Wrapper
│   ├── src\main\java\com\backend\
│   │   ├── auth\                 # AuthController / AuthInterceptor / AuthService / TokenManager / User
│   │   ├── chat\                 # ConversationController/Service（消息流会话模型）
│   │   ├── qa\                   # QaController / QaService / Nlu / Retrieval / Llm
│   │   ├── common\               # ApiResponse<T>{code,message,data} / 全局异常
│   │   ├── config\               # WebConfig(CORS/UTF-8) / DemoDataInitializer
│   │   └── controller\HealthController
│   └── src\main\resources\
│       ├── application.properties  # port=8080, H2 内存, demo/123456, qwen 配置
│       └── sql\schema.sql
│
├── frontend\                     # Vue3 应用（vite 配置见下）
│   ├── vite.config.ts            # server.proxy['/api'] → http://127.0.0.1:8000
│   └── src\api\{request,auth,chat,qa,kb}.ts
│
├── ai-service\                   # 既有 Python 服务（保留，未纳入本次重组）
├── backend\ (根下另一份历史后端)   # 保留，未纳入本次重组
├── README-DEPLOY.md              # Netlify 部署指南
└── README-ARCHITECTURE.md        # 本文档
```

---

## 三、关键决策与权衡

### 1. 会话（conversations）为何暂留在 Python
- 前端 `QaView.vue` 使用的会话模型是 **「单轮快照」**：`{question, ethnic, disease, intent, status, answer_summary}` 平铺，`createdAt/updatedAt` 为毫秒时间戳。
- `legacy/backend1` 的 Spring Boot 会话模型是 **「消息流」**：`ConversationDetail{ conversation, List<ChatMessage> }`，消息含 `role/content/detail` 与 ISO 时间戳。
- 两者不兼容。若直接把 `/api/conversations` 切到 Spring Boot，前端「新建/打开/继续提问/重命名」会全部失效，**违反「不改动既有功能」**。
- 因此：会话接口与登录接口（契约已兼容）暂由 `backend/business.py` 提供，隔离在独立模块，便于将来整体迁移。

### 2. 两份后端响应契约不同（重要）
- **FastAPI (Python)**：直接返回业务数据，例如登录返回 `{token, user{...}}`。前端 `request.ts` 拦截器 `return response.data` 直接解包。
- **Spring Boot**：统一信封 `ApiResponse{code, message, data}`，例如登录返回 `{code:0, message:"success", data:{token, user{...}}}`。
- 当前前端只适配了 FastAPI 的直接返回风格。若切换 auth 到 Spring Boot，需改 `request.ts` 拦截器或在 Spring Boot 侧加「去信封」适配层。**未做，以保证前端零改动。**

### 3. 知识库文档库留 Python
- 用户文档上传/查看全文（`/api/kb/*`）由 `kb_store.py` 提供，与 AI 检索共用，且 Spring Boot 侧当前无对应实现，故保留。

---

## 四、API 路由清单

### Python :8000（FastAPI，直接返回业务数据）
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查（含 llm/embedding/papers/kb_entries 计数） |
| GET | `/api/papers` | 内置文献列表（papers.json） |
| POST | `/api/understand` | 问题理解 |
| POST | `/api/retrieve` | 知识库提取（混合检索） |
| POST | `/api/generate` | 结构化回答生成 |
| POST | `/api/evidence-pool` | 知识证据池 |
| GET | `/api/kb/docs` | 文档列表 |
| GET | `/api/kb/docs/{id}` | 文档详情（含全文） |
| POST | `/api/kb/upload` | 上传文档（multipart） |
| DELETE | `/api/kb/docs/{id}` | 删除文档 |
| GET | `/api/kb/files/{id}` | 原始文件内联预览 |
| POST | `/api/auth/login` | 登录（business.py） |
| POST | `/api/auth/register` | 注册 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/logout` | 登出 |
| GET/POST/PUT/DELETE | `/api/conversations[/...]` | 会话 CRUD（business.py） |

### Spring Boot :8080（信封 `ApiResponse{code,message,data}`）
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查 |
| POST | `/api/auth/login` | 登录（demo/123456） |
| POST | `/api/auth/register` | 注册 |
| GET | `/api/auth/me` | 当前用户（需 Bearer） |
| POST | `/api/auth/logout` | 登出 |
| GET/POST/... | `/api/conversations` | 会话（消息流模型） |
| POST | `/api/qa/ask` | 完整 QA 链路（前端未使用） |

---

## 五、启动顺序与命令

> 开发态：MySQL 可选（Spring Boot 用 H2 内存库即可）；前端经 vite 代理访问 Python :8000。

### 1) Python AI 服务（必须）
```bash
cd E:\MultiEthnic\多民族医学智能体\backend
.venv\Scripts\python.exe -m uvicorn main:app --host 127.0.0.1 --port 8000
# 验证：curl http://127.0.0.1:8000/health
```

### 2) 前端（必须，开发态）
```bash
cd E:\MultiEthnic\多民族医学智能体\frontend
npm install
npm run dev        # :5173，/api 自动代理到 :8000
```

### 3) Spring Boot 业务后端（可选 / 迁移备用）
```bash
cd E:\MultiEthnic\多民族医学智能体\legacy\backend1
.\mvnw spring-boot:run      # 或 java -jar target/*.jar
# 验证：curl http://127.0.0.1:8080/api/health
# 演示账号：demo / 123456
```
> 注：本机此前已有一个运行中的实例（PID 30460，:8080）。沙箱内 Maven Wrapper 曾因 `plexus-classworlds` 缺失报 `ClassNotFoundException`；生产机用标准 `mvnw` 即可。当前前端未接入该端口。

---

## 六、迁移路径（将来把业务层整体迁到 Spring Boot）

1. **统一会话模型**：在 `legacy/backend1` 的 `Conversation` 增加「单轮快照」字段（question/ethnic/disease/intent/status/answer_summary），或前端改为消息流模型。
2. **契约对齐**：在 Spring Boot 侧加去信封适配层，或在 `frontend/src/api/request.ts` 支持 `ApiResponse` 信封。
3. **切流量**：在 `frontend/vite.config.ts` 增加代理分流：
   ```ts
   proxy: {
     '/api/auth':        { target: 'http://127.0.0.1:8080', changeOrigin: true },
     '/api/conversations': { target: 'http://127.0.0.1:8080', changeOrigin: true },
     '/api':             { target: 'http://127.0.0.1:8000', changeOrigin: true },
   }
   ```
4. **下线 `business.py`**：迁移完成后，从 `main.py` 移除 `business.register_business_routes(app)` 即可，AI 层零改动。

---

## 七、约束与注意事项

- **不改动既有功能**：本次重组仅移动/拆分文件、修正 `ai/` 包的相对导入、抽出 `business.py`，未修改任何对外行为。
- **CORS**：FastAPI `allow_origins=["*"]`；Spring Boot `WebConfig` 对 `/api/**` 放开 CORS 并强制 UTF-8。
- **路径基线**：`ai/config.py` 的 `BASE_DIR = Path(__file__).resolve().parent.parent`（指向 `backend/`），故 `.env` 与 `papers.json` 从 `backend/` 解析。
- **部署**：Netlify 仅托管前端（见 `README-DEPLOY.md`）；AI 能力需另部署 Python 后端或用环境变量 `VITE_API_BASE` 指向后端地址。
