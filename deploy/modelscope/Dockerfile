# ============================================================================
#  多民族特色医学智能体 · 魔搭创空间（ModelScope Studio）部署镜像
#
#  平台只放行 7860 一个端口，所以三个服务必须同容器共存：
#
#       nginx :7860  ← 唯一对外端口
#       ├── /                                 → /app/dist（前端静态产物）
#       ├── /api/auth|conversations|qa|...    → 127.0.0.1:8080  Spring Boot
#       └── /api/*                            → 127.0.0.1:8000  FastAPI
#
#  构建上下文需要四个东西（由 assemble.ps1 准备好）：
#       app.jar        后端产物（backend/target/backend-*.jar）
#       dist/          前端产物（frontend/dist）
#       ai-service/    AI 服务源码（含 data/ 知识库 JSON）
#       nginx.conf  entrypoint.sh
#
#  注意：本镜像**不包含任何密钥**。SiliconFlow 的 API Key 通过创空间的
#  环境变量注入（LLM_API_KEY / EMBED_API_KEY），见创空间「设置 → 环境变量」。
# ============================================================================

# Temurin 官方镜像里 JRE 固定在 /opt/java/openjdk，整目录拷走即可。
# 比 apt 装 openjdk 更稳：不依赖发行版的包源和版本。
FROM eclipse-temurin:17-jre AS jre


# ★ Python 版本必须是 3.12+，不能用 3.11 ★
#
# ai-service 里用了 PEP 701 语法（f-string 内嵌套同种引号），3.12 才支持。
# 例：app/services/answer.py 的
#     f"{_evidence_blocks(..., understanding.get("ethnicity"), ...)}"
# 在 3.11 下是 SyntaxError，uvicorn 会在 import 阶段就崩，容器活不过启动。
# 项目开发环境一直是 3.12/3.13，这里对齐。
FROM python:3.12-slim

# ---------- Java 运行时 ----------
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:$PATH"

# ---------- nginx ----------
# --no-install-recommends 省掉一堆用不上的依赖；
# 装完立刻清 apt 缓存，镜像能小几十 MB。
RUN apt-get update \
 && apt-get install -y --no-install-recommends nginx \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ---------- AI 服务（FastAPI）----------
# 依赖很轻（fastapi/uvicorn/httpx/pydantic/trafilatura），没有本地模型、
# 不需要 GPU —— 向量化走 SiliconFlow 的远程 bge-m3。
# 走清华源：构建机在国内，直连 PyPI 慢且容易超时。
COPY ai-service/ /app/ai-service/
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple \
        -r /app/ai-service/requirements.txt \
 && find /app/ai-service -name '__pycache__' -type d -prune -exec rm -rf {} +

# ---------- 前端静态产物 ----------
COPY dist/ /app/dist/

# ---------- 业务后端（Spring Boot）----------
COPY app.jar /app/app.jar

# ---------- 知识库种子数据 ----------
#
# 后端把知识库拆成两半存放，缺一不可：
#   seed/multiethnic.mvdb    H2 库里的 kb_document 元数据（标题/状态/正文/分区）
#   seed/kb_data/            上传的原始文件（PDF/docx），DB 里存的是它的文件名
#
# 扩展名刻意写成 .mvdb 而不是 .mv.db —— 仓库的 .gitattributes 有
# `*.db* filter=lfs`，叫 .mv.db 会被 LFS 接管成指针文件，构建时若没拉取
# LFS 内容，Spring 打开的就是个一百多字节的文本，后端直接起不来。
# entrypoint.sh 灌入时会改回真实文件名 multiethnic.mv.db。
#
# 不带这两个，容器里就是个全新的空库 —— 平台日志会显示
# 「知识库演示数据已禁用（app.kb.demo-seed=false），跳过注入」，
# 前端知识库页面只剩内置文献，用户文档全空。
#
# 实际灌入动作在 entrypoint.sh 里做：只在持久化目录**还没有**库文件时
# 才复制一份种子过去，这样重启和重新部署都不会覆盖用户后续新增的资料。
COPY seed/ /app/seed/

# ---------- 入口 ----------
COPY nginx.conf     /etc/nginx/nginx.conf
COPY entrypoint.sh  /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# 平台要求：必须监听 7860，且必须是 0.0.0.0（由 nginx 满足）
EXPOSE 7860

CMD ["/app/entrypoint.sh"]
