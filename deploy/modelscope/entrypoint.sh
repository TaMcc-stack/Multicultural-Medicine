#!/usr/bin/env bash
#
# 魔搭创空间单容器入口：nginx(:7860) + Spring Boot(:8080) + FastAPI(:8000)
#
# 平台只放行 7860 一个端口，所以三个进程都必须在本容器内，由 nginx 做统一入口。
# 后端只监听 127.0.0.1，不对外暴露。
#
set -u

APP_HOME=/app

# ---------------------------------------------------------------
# 持久化目录
#
# 平台约定 /mnt/workspace 重启后保留，容器内其它路径都会丢。
# 落在这里的是 H2 数据库（登录态、会话、消息、知识库文档）和上传的原始文件。
# 没有这个目录（比如本地 docker run）就退回容器内，功能一样，只是重启清零。
# ---------------------------------------------------------------
PERSIST=/mnt/workspace
if [ ! -d "$PERSIST" ] || [ ! -w "$PERSIST" ]; then
    echo "[entrypoint] $PERSIST 不存在或不可写，退回 /app/runtime（重启后数据会丢）"
    PERSIST=/app/runtime
fi
mkdir -p "$PERSIST/backend-data" "$PERSIST/kb_data"
echo "[entrypoint] 持久化目录：$PERSIST"

# ---------------------------------------------------------------
# 灌入知识库种子数据
#
# 要与三种情况区分开：
#   1) 全新部署，持久化目录里没有库文件      -> 灌
#   2) 早先部署留下的**空库**（本次就踩到了）-> 灌
#   3) 用户已经在用的库（可能已新增资料）    -> 绝不覆盖
#
# 第 2 种是关键：光判「文件是否存在」会把空库当成已有数据而跳过灌入，
# 于是知识库永远是空的。这里用**体积**区分 2 和 3 —— 实测 H2 空库
# 只有 56KB，带 4 篇资料的种子库是 1.7MB，差 30 倍。
# 只要现有库明显小于种子的一半，就认定它是空库。
#
# ★ 必须赶在 Spring Boot 之前做 ★ —— 它一启动就会打开这个库文件。
# ---------------------------------------------------------------
SEED_DIR=/app/seed
SEED_DB="$SEED_DIR/multiethnic.mvdb"
DB_FILE="$PERSIST/backend-data/multiethnic.mv.db"

do_seed=0
if [ -f "$SEED_DB" ]; then
    if [ ! -f "$DB_FILE" ]; then
        echo "[entrypoint] 没有数据库，灌入种子"
        do_seed=1
    else
        cur=$(stat -c %s "$DB_FILE" 2>/dev/null || echo 0)
        seed=$(stat -c %s "$SEED_DB" 2>/dev/null || echo 0)
        if [ "$seed" -gt 0 ] && [ "$cur" -lt $((seed / 2)) ]; then
            echo "[entrypoint] 检测到空库（$cur 字节，种子 $seed 字节），用种子替换"
            do_seed=1
        else
            echo "[entrypoint] 已有数据库（$cur 字节），保留现有资料"
        fi
    fi
fi

if [ "$do_seed" = 1 ]; then
    cp "$SEED_DB" "$DB_FILE"
    if [ -d "$SEED_DIR/kb_data" ]; then
        # -n 不覆盖同名文件：已有资料时只补缺，不动用户的东西
        cp -rn "$SEED_DIR/kb_data/." "$PERSIST/kb_data/" 2>/dev/null || true
    fi
    echo "[entrypoint] 种子灌入完成，原始文件 $(ls -1 "$PERSIST/kb_data" 2>/dev/null | wc -l) 个"
fi

# ---------------------------------------------------------------
# 等待端口就绪
#
# nginx 先起的话，后端还没监听，平台探活会连着拿 502，
# 有可能被判定成部署失败。所以等后端起来再交给 nginx。
# ---------------------------------------------------------------
wait_port() {
    local port="$1" name="$2" seconds="${3:-90}"
    for _ in $(seq 1 "$seconds"); do
        if (echo > "/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
            echo "[entrypoint] $name 已就绪 (:$port)"
            return 0
        fi
        sleep 1
    done
    echo "[entrypoint] 警告：$name 在 $seconds 秒内未监听 :$port"
    return 1
}

# ---------------------------------------------------------------
# Spring Boot —— 登录鉴权 / 会话消息 / 知识库 / 动态收藏
#
# 数据库路径必须显式覆盖成绝对路径：默认值是 ./data/multiethnic（相对启动目录），
# 而这里为了写进持久化目录会把 cwd 换掉，不覆盖就会写到容器里、重启即丢。
#
# demo 账号由 DemoDataInitializer 在启动时自动创建（库中没有才建），
# schema 由 spring.sql.init + classpath:sql/schema.sql 自动执行，
# 所以首启动不需要任何种子数据，也不需要带 .mv.db。
# ---------------------------------------------------------------
# ---------------------------------------------------------------
# nginx 先起来
#
# ★ 顺序很重要 ★ 平台的探测是 `POST /invoke`（见 nginx.conf），
# 端口越早通、越早回 200 越好。原来写的是「等两个后端就绪再起 nginx」，
# 于是头几十秒 7860 压根没人监听，平台探测连吃闭门羹。
# 现在反过来：nginx 立刻占住端口，后端在它后面慢慢就绪。
# 那期间 /api/* 会短暂 502，但页面本身能正常打开。
# ---------------------------------------------------------------
echo "[entrypoint] 启动 nginx，对外监听 7860"
nginx -g 'daemon off;' &
NGINX_PID=$!

# ---------------------------------------------------------------
# Spring Boot —— 登录鉴权 / 会话消息 / 知识库 / 动态收藏
#
# 数据库路径必须显式覆盖成绝对路径：默认值是 ./data/multiethnic（相对启动目录），
# 而这里为了写进持久化目录会把 cwd 换掉，不覆盖就会写到容器里、重启即丢。
#
# demo 账号由 DemoDataInitializer 在启动时自动创建（库中没有才建），
# schema 由 spring.sql.init + classpath:sql/schema.sql 自动执行，
# 所以首启动不需要任何种子数据，也不需要带 .mv.db。
# ---------------------------------------------------------------
echo "[entrypoint] 启动 Spring Boot ..."
java -jar "$APP_HOME/app.jar" \
    --spring.datasource.url="jdbc:h2:file:$PERSIST/backend-data/multiethnic;MODE=MySQL;DB_CLOSE_DELAY=-1" \
    --app.kb.storage-dir="$PERSIST/kb_data" \
    &

# ---------------------------------------------------------------
# FastAPI —— 问题理解 / 证据检索 / 回答生成
#
# 监听 127.0.0.1:8000。**不能用 7860**，那是 nginx 的平台端口；
# 也不要用 8080，平台上该端口被占用。
#
# LLM_API_KEY / EMBED_API_KEY 等从创空间的环境变量注入，不写进镜像。
# ---------------------------------------------------------------
echo "[entrypoint] 启动 FastAPI ..."
(
    cd "$APP_HOME/ai-service" || exit 1
    exec uvicorn app.main:app --host 127.0.0.1 --port 8000
) &

# 后端就绪情况只作日志用：nginx 已经在跑了，这里再慢也不影响平台放流量，
# 50 秒起不来只是记一笔，不阻塞。
wait_port 8080 "Spring Boot" 50
wait_port 8000 "FastAPI" 50

echo "[entrypoint] 容器进入运行态"
# 容器的存活以 nginx 为准：它退了就该重启
wait "$NGINX_PID"
