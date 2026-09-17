-- 用户表（POC 阶段：本地账号密码登录）
-- 表名使用 app_user，避免 H2 / 部分数据库中 user 为保留字
CREATE TABLE IF NOT EXISTS `app_user` (
    `id`         BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    `username`   VARCHAR(50)  NOT NULL UNIQUE COMMENT '登录名',
    `password`   VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密后的密码',
    `nickname`   VARCHAR(50)  COMMENT '昵称',
    `created_at` TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间'
);

-- ============================================================
-- 登录令牌表（token 持久化，后端重启后登录态不丢失）
-- token 为随机 UUID（去连字符，32 位十六进制）
-- ============================================================
CREATE TABLE IF NOT EXISTS `auth_token` (
    `token`      VARCHAR(64)  PRIMARY KEY COMMENT '令牌',
    `user_id`    BIGINT       NOT NULL COMMENT '所属用户ID',
    `created_at` TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '签发时间',
    `expires_at` TIMESTAMP    NOT NULL COMMENT '过期时间'
);
CREATE INDEX IF NOT EXISTS `idx_token_user` ON `auth_token` (`user_id`);
CREATE INDEX IF NOT EXISTS `idx_token_expires` ON `auth_token` (`expires_at`);

-- ============================================================
-- 知识库：论文（paper）与证据片段（evidence）
-- evidence.topic 对应查询意图：
--   prevalence 患病情况 / risk 危险因素 / diet 饮食与生活方式 /
--   genetics 遗传相关研究 / overview 研究总体情况
-- ============================================================
CREATE TABLE IF NOT EXISTS `paper` (
    `id`         BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '论文ID',
    `title`      VARCHAR(300) NOT NULL COMMENT '论文名称',
    `ethnicity`  VARCHAR(50)  NOT NULL COMMENT '民族',
    `disease`    VARCHAR(50)  NOT NULL COMMENT '疾病',
    `population` VARCHAR(300) COMMENT '研究对象',
    `study_year` VARCHAR(20)  COMMENT '研究时间',
    `findings`   TEXT         COMMENT '研究结果',
    `limitation` TEXT         COMMENT '研究限制',
    `source`     VARCHAR(300) COMMENT '资料来源说明'
);

CREATE TABLE IF NOT EXISTS `evidence` (
    `id`         BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '证据ID',
    `paper_id`   BIGINT       NOT NULL COMMENT '所属论文ID',
    `topic`      VARCHAR(30)  NOT NULL COMMENT '主题（查询意图）',
    `content`    TEXT         NOT NULL COMMENT '证据原文片段'
);

CREATE INDEX IF NOT EXISTS `idx_paper_ed` ON `paper` (`ethnicity`, `disease`);
CREATE INDEX IF NOT EXISTS `idx_evidence_paper` ON `evidence` (`paper_id`);

-- ============================================================
-- 对话：会话（conversation）与消息（chat_message）
-- 一个会话属于一个用户，包含多条消息；
-- 消息以 chat_message 命名，避免部分数据库将 message 视为保留字
-- ============================================================
CREATE TABLE IF NOT EXISTS `conversation` (
    `id`         BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '会话ID',
    `user_id`    BIGINT       NOT NULL COMMENT '所属用户ID',
    `title`      VARCHAR(100) NOT NULL COMMENT '会话标题（默认取首问前若干字）',
    `created_at` TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间'
);
CREATE INDEX IF NOT EXISTS `idx_conversation_user` ON `conversation` (`user_id`);

CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    `conversation_id` BIGINT       NOT NULL COMMENT '所属会话ID',
    `role`            VARCHAR(20)  NOT NULL COMMENT 'user / assistant',
    `kind`            VARCHAR(20)  NOT NULL COMMENT 'text / clarify / answer / notice',
    `content`         TEXT         COMMENT '文本内容（用户问题 / 提示文案）',
    `detail_json`     TEXT         COMMENT 'assistant 消息的完整响应 JSON，用于历史回放渲染',
    `created_at`      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间'
);
CREATE INDEX IF NOT EXISTS `idx_message_conversation` ON `chat_message` (`conversation_id`);

-- ============================================================
-- 知识库：用户上传文档（kb_document）
-- 由 Spring Boot 业务后端持久化：文件落盘 + 元数据入 H2
-- ============================================================
CREATE TABLE IF NOT EXISTS `kb_document` (
    `id`            VARCHAR(64)   PRIMARY KEY COMMENT '文档ID（UUID）',
    `title`         VARCHAR(300)  NOT NULL COMMENT '标题',
    `category`      VARCHAR(100)  DEFAULT '民族疾病类' COMMENT '分类',
    `original_name` VARCHAR(300)  NOT NULL COMMENT '原始文件名',
    `stored_name`   VARCHAR(300)  NOT NULL COMMENT '存储文件名',
    `ext`           VARCHAR(20)   COMMENT '扩展名',
    `mime`          VARCHAR(100)  COMMENT 'MIME 类型',
    `size`          BIGINT        DEFAULT 0 COMMENT '字节大小',
    `content`       TEXT          COMMENT '提取的全文文本',
    `has_content`   BOOLEAN       DEFAULT FALSE COMMENT '是否已提取全文',
    `page_count`    INT           DEFAULT 0 COMMENT '页数（PDF）',
    `author`        VARCHAR(300)  COMMENT '自动识别的作者',
    `source`        VARCHAR(300)  COMMENT '自动识别的出处/期刊/出版社',
    `publish_year`  INT           COMMENT '自动识别的发表年份',
    `summary`       TEXT          COMMENT '摘要（文档自带/用户核对确认的真实摘要）',
    `article_summary` TEXT        COMMENT '文章总结（AI 概括主要内容，可编辑）',
    `evidence_json` TEXT          COMMENT '结构化证据片段JSON（出处/作者/民族疾病/研究类型/研究人群/结论/局限性/证据片段）',
    `user_id`       BIGINT        COMMENT '上传用户ID（可选）',
    `created_at`    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间'
);
ALTER TABLE `kb_document` ADD COLUMN IF NOT EXISTS `article_summary` TEXT COMMENT '文章总结（AI 概括主要内容，可编辑）';
ALTER TABLE `kb_document` ADD COLUMN IF NOT EXISTS `status` VARCHAR(20) DEFAULT 'done' COMMENT '处理状态：processing（后台解析中）/ done（完成）/ error（失败）';
CREATE INDEX IF NOT EXISTS `idx_kb_doc_cat` ON `kb_document` (`category`);

-- ── 来源维度（分类体系升级：一级=来源，二级=category）────────────────────
-- 与已有的 `source` 列**语义不同**：`source` 是文献出处（期刊/出版社，由 MetadataExtractor
-- 从正文识别）；这里记的是**来源渠道**——这份资料是怎么进来的。两者是不同的问题，
-- 故新列命名为 source_org 而不是 source_name，避免与 `source` 混淆。
ALTER TABLE `kb_document` ADD COLUMN IF NOT EXISTS `source_level` VARCHAR(20) DEFAULT 'official' COMMENT '来源等级：official（官方上传）/ web_crawl（网页抓取）/ user_upload（用户上传）';
ALTER TABLE `kb_document` ADD COLUMN IF NOT EXISTS `source_org` VARCHAR(300) COMMENT '来源机构：国家卫健委 / 人民日报 / 用户上传 等';
ALTER TABLE `kb_document` ADD COLUMN IF NOT EXISTS `source_url` VARCHAR(1000) COMMENT '原始链接（网页抓取时记录，供溯源与去重）';
-- 审核态**独立于 status**：status 已被后台解析任务占用（processing/done/error），
-- 审核塞进去会与解析进度互相覆盖。默认 approved，使存量数据与官方上传自动通过，
-- 只有 user_upload 才置 pending。
ALTER TABLE `kb_document` ADD COLUMN IF NOT EXISTS `review_status` VARCHAR(20) DEFAULT 'approved' COMMENT '审核状态：approved（通过）/ pending（待审核）/ rejected（已驳回）';

CREATE INDEX IF NOT EXISTS `idx_kb_doc_source` ON `kb_document` (`source_level`);
CREATE INDEX IF NOT EXISTS `idx_kb_doc_review` ON `kb_document` (`review_status`);

-- ============================================================
-- 动态 / 分享（user_dynamic）与收藏（user_favorite）
-- 一条动态由「某次会话中的某条 AI 回答」转存而来，可被其他用户收藏。
-- 表名沿用本项目「避免保留字 + 语义前缀」的规范：
--   app_user / chat_message / kb_document / user_dynamic / user_favorite
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_dynamic` (
    `id`                    BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '动态ID',
    `user_id`               BIGINT       NOT NULL COMMENT '发布用户ID',
    `source_conversation_id` BIGINT      COMMENT '来源会话ID',
    `source_message_id`     BIGINT       COMMENT '来源AI回答消息ID',
    `title`                 VARCHAR(200) COMMENT '动态标题（默认取来源问题）',
    `content`               TEXT         NOT NULL COMMENT '动态正文（AI回答内容）',
    `detail_json`           TEXT         COMMENT '结构化回答JSON，供详情页还原展示',
    `status`                VARCHAR(20)  DEFAULT 'normal' COMMENT 'normal 正常 / deleted 已删除',
    `created_at`            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    `updated_at`            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);
CREATE INDEX IF NOT EXISTS `idx_dynamic_user` ON `user_dynamic` (`user_id`);
CREATE INDEX IF NOT EXISTS `idx_dynamic_status` ON `user_dynamic` (`status`);
CREATE INDEX IF NOT EXISTS `idx_dynamic_source_msg` ON `user_dynamic` (`source_message_id`);

CREATE TABLE IF NOT EXISTS `user_favorite` (
    `id`          BIGINT   AUTO_INCREMENT PRIMARY KEY COMMENT '收藏ID',
    `user_id`     BIGINT   NOT NULL COMMENT '收藏用户ID',
    `dynamic_id`  BIGINT   NOT NULL COMMENT '被收藏的动态ID',
    `created_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间'
);
-- 唯一约束：同一用户不能重复收藏同一条动态
CREATE UNIQUE INDEX IF NOT EXISTS `uk_favorite_user_dynamic` ON `user_favorite` (`user_id`, `dynamic_id`);
CREATE INDEX IF NOT EXISTS `idx_favorite_dynamic` ON `user_favorite` (`dynamic_id`);

-- ============================================================
-- 个人中心：用户头像与个人简介
-- avatar 存的是配色 key（sage/clay/amber/... 前端据此渲染「昵称首字 + 渐变底」的头像），
-- 不是图片地址——POC 阶段避免引入文件上传与存储，将来要换真图再改这一列的语义即可。
-- ============================================================
ALTER TABLE `app_user` ADD COLUMN IF NOT EXISTS `avatar` VARCHAR(32) DEFAULT 'sage' COMMENT '头像配色 key';
ALTER TABLE `app_user` ADD COLUMN IF NOT EXISTS `bio` VARCHAR(500) DEFAULT '' COMMENT '个人简介';

-- ============================================================
-- 会话收藏（user_conversation_favorite）
-- 与「动态收藏」（user_favorite）是两回事：
--   user_favorite              收藏的是**已发布的动态**（公开内容）
--   user_conversation_favorite 收藏的是**自己的对话**（私有书签，不公开发布）
-- 之所以要分开：早先没有这张表，收藏对话只能先把它转存成动态再收藏，
-- 结果「点收藏」这个动作会把内容公开出去——私有书签和公开发布被绑成了一件事。
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_conversation_favorite` (
    `id`              BIGINT   AUTO_INCREMENT PRIMARY KEY COMMENT '收藏ID',
    `user_id`         BIGINT   NOT NULL COMMENT '收藏用户ID',
    `conversation_id` BIGINT   NOT NULL COMMENT '被收藏的会话ID',
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间'
);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_conv_fav` ON `user_conversation_favorite` (`user_id`, `conversation_id`);
CREATE INDEX IF NOT EXISTS `idx_conv_fav_user` ON `user_conversation_favorite` (`user_id`);

-- ============================================================
-- 检索记录收藏（user_search_favorite）
-- 第三种私有书签，形状与 user_conversation_favorite 完全同构：
-- 收藏的是「高级检索留下的一条历史记录」，只自己可见。
-- 收藏到**记录行**（search_history_id）而不是「民族+疾病+方面」这个槽位——
-- 记录本身已经被 uk_search_user_slots 按槽位去重了，收藏行等价于收藏槽位；
-- 但指向 id 才能在一行记录被删掉时识别出「这条收藏指向的东西没了」。
-- 被收藏的记录被删除时**不级联删收藏**（与 user_conversation_favorite 一致）：
-- 列表组装时跳过找不到的那条即可，关系留着无害。
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_search_favorite` (
    `id`                BIGINT   AUTO_INCREMENT PRIMARY KEY COMMENT '收藏ID',
    `user_id`           BIGINT   NOT NULL COMMENT '收藏用户ID',
    `search_history_id` BIGINT   NOT NULL COMMENT '被收藏的检索记录ID',
    `created_at`        TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间'
);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_search_fav` ON `user_search_favorite` (`user_id`, `search_history_id`);
CREATE INDEX IF NOT EXISTS `idx_search_fav_user` ON `user_search_favorite` (`user_id`);

-- ============================================================
-- 动态防重：同一用户 + 同一会话只允许存在一条动态
-- 应用层的「先查再插」不是原子的——两个并发请求可以都查到「不存在」然后各插一条，
-- 表现为动态列表里出现两条一模一样的内容。唯一约束才是真正的保证。
-- （source_conversation_id 为 NULL 的历史数据不受影响：SQL 里 NULL 互不相等。）
-- ============================================================
-- 建索引前先去重，否则索引创建会失败。
-- 注意一：要覆盖**所有状态**——唯一索引连软删除的行一起占位，只清 normal 不够。
-- 注意二：用 id IN (要删的) 而不是 id NOT IN (要留的)。
--         两者在正常情况等价，但子查询返回空集时：IN(空) 不删任何行，
--         NOT IN(空) 恒为真、会**删掉整张表**。失败方向必须选安全的那个。
DELETE FROM `user_dynamic`
WHERE `id` IN (
    SELECT `d`.`id`
    FROM `user_dynamic` `d`
    JOIN (
        SELECT `user_id`, `source_conversation_id`,
               COALESCE(MIN(CASE WHEN `status` = 'normal' THEN `id` END), MIN(`id`)) AS `keep_id`
        FROM `user_dynamic`
        WHERE `source_conversation_id` IS NOT NULL
        GROUP BY `user_id`, `source_conversation_id`
    ) `k` ON `d`.`user_id` = `k`.`user_id`
         AND `d`.`source_conversation_id` = `k`.`source_conversation_id`
    WHERE `d`.`id` <> `k`.`keep_id`
);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_dynamic_user_conv` ON `user_dynamic` (`user_id`, `source_conversation_id`);

-- ============================================================
-- 一问一答社区：动态分两类，共用这张表
--   dialogue 多轮对话分享（原有形态，历史数据全部归此类）
--   qa       一问一答卡片（高级检索页分享出来的单轮问答）
-- 为什么共用一张表而不是新建：两者的详情载荷本来就是同一形状
-- （detail_json = {version:2, turns:[...]}），详情弹窗按 turns 渲染，长度为 1 天然兼容。
-- 分表就得把收藏（user_favorite 按 dynamic_id 关联）也一起分叉，代价远大于收益。
-- ============================================================
ALTER TABLE `user_dynamic` ADD COLUMN IF NOT EXISTS `kind` VARCHAR(20) DEFAULT 'dialogue' COMMENT '动态类型：dialogue 多轮对话 / qa 一问一答';
ALTER TABLE `user_dynamic` ADD COLUMN IF NOT EXISTS `qa_key` VARCHAR(300) COMMENT '问答卡规范化键「民族|疾病|意图」；对话类为 NULL';
CREATE INDEX IF NOT EXISTS `idx_dynamic_kind` ON `user_dynamic` (`kind`);

-- 一问一答卡的**全局唯一**约束（跨用户）：同一「民族+疾病+意图」社区里只留一张卡，
-- 重复分享是刷新内容而不是新增。去重后一个组合有多张卡只会让社区看起来像刷屏。
-- qa_key 为 NULL 的行（全部对话分享）不受影响：SQL 里 NULL 互不相等，与上面 uk_dynamic_user_conv 同理。
CREATE UNIQUE INDEX IF NOT EXISTS `uk_dynamic_qa` ON `user_dynamic` (`qa_key`);

-- ============================================================
-- 高级检索历史（user_search_history）
-- 高级检索页左侧「历史记录」栏的数据源：记下用户点过的「民族 × 疾病 × 想了解的方面」。
--
-- 为什么不复用 conversation：那边是**多轮消息**（chat_message 挂着 conversation_id），
-- 而一次高级检索没有对话轮次；而且它的幂等键是「用户 + 三个槽位」，不是会话 id。
-- 混进 conversation 会让对话列表里冒出一堆点不开的历史残留。
--
-- 为什么不只是存三个槽位：需求要「查看当时的结果」，而重跑一遍要打一次 LLM（数秒，
-- 且 AI 服务没启动就什么也看不到）。所以把结果快照一并存下，点历史即刻还原。
-- 注意存的**不是** evidence-pool 的原始返回：那里面 candidates[].fullText 是整份文档正文
-- （单条几十 KB，一份共识全文就 35k 字），存下来这张表会迅速膨胀。
-- 只留结果区真正会渲染的那几项（answer / evidence / reason / status）——形状由前端的
-- SearchSnapshot 说了算：服务端不解析这份 JSON（RecordSearchRequest.detail 就是个 Object），
-- 只是原样存档，所以加字段不需要动后端。
--
-- 唯一键是「用户 + 三槽位」：重复检索同一组合是**刷新**时间与快照，不是新增一条。
-- 否则用户为了找一个有资料的方面连点几下，侧栏就被同一组合的各种尝试刷满了。
-- 有了它这张表也不会无限长——上限就是下拉里真能选出来的组合数。
--
-- 不存展示标题（对比 user_dynamic.title）：动态的标题来自来源问句，服务端拼不出来，
-- 所以必须落一列；而检索历史的标题就是「民族 + 疾病 + 意图中文名」，三个槽位全在这张表里，
-- 前端拿 INTENT_LABELS 一拼就有。多存一列反而是个漂移点——标签改了以后旧记录会与新记录不一致。
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_search_history` (
    `id`          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '检索记录ID',
    `user_id`     BIGINT       NOT NULL COMMENT '所属用户ID',
    `ethnicity`   VARCHAR(50)  NOT NULL COMMENT '民族',
    `disease`     VARCHAR(50)  NOT NULL COMMENT '疾病',
    `intent`      VARCHAR(30)  NOT NULL COMMENT '想了解的方面（意图码，如 prevalence）',
    `detail_json` TEXT         COMMENT '结果快照JSON（答案 + 证据 + 未命中原因），供「查看当时的结果」还原',
    `created_at`  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '首次检索时间',
    `updated_at`  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '最后一次检索时间（列表按它倒序）'
);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_search_user_slots` ON `user_search_history` (`user_id`, `ethnicity`, `disease`, `intent`);
CREATE INDEX IF NOT EXISTS `idx_search_user_time` ON `user_search_history` (`user_id`, `updated_at`);

-- ============================================================
-- 知识缺口（knowledge_gap）与缺口反馈（knowledge_gap_feedback）
-- 未命中时登记一条需求，形成「发现缺口 → 补录文献 → 命中」的闭环。
--
-- 有两个入口，靠 from_search / from_chat 两列各自留痕（见下面加列那一段）：
--   ① 高级检索未命中 —— **自动**登记。三个槽位是用户从下拉里明确选出来的，本身就是结构化
--      的需求信号。
--   ② 智能对话未命中、且用户点了【反馈此问题】—— **用户主动**才登记。
--      对话页的问题要先过一轮 NLU 才能拆出同样三要素，拆错会污染榜单，所以这里刻意不做
--      自动登记：只有用户明确说「这个问题对我重要」才算数。自动登记会把每一条 NLU 猜测
--      都变成一条需求，而猜测是会错的。
--
-- 为什么是**全局**而不是按用户：
--   这张榜要回答的是「知识库缺什么」，与谁问的无关。同一组合全库只有一行，被问次数是
--   所有用户累加的。按用户隔离的话，10 个人各问一次会变成 10 行各 1 次，「哪些缺口最
--   紧迫」就看不出优先级了。
--
-- 为什么反馈要单独一张表：
--   反馈次数若直接在 knowledge_gap 上自增，同一个人连点 50 下就能把某条刷到榜首。
--   按 (gap_id, user_id) 去重之后，它表示「有多少人想要这个」——这才是有意义的排序依据。
--   对话页的【反馈此问题】也写这张表，所以它同样是「有多少人想要」而不是「点了几次」。
--
-- 为什么不复用 user_search_history：
--   那张表是**每个用户自己**的检索历史（幂等键含 user_id，用户可以删）；
--   缺口是跨用户汇总的需求统计，生命周期也不同（补上了才算结束）。两者语义无关。
--
-- ask_count 必须用 ON DUPLICATE KEY UPDATE 自增，不能照抄 SearchHistoryService 的
-- 「先查再插 + 捕获重复键」：那个模式是给**幂等刷新**用的，两个并发的未命中会双双查不到、
-- 一个插入成功、另一个走 fallback 分支——而 fallback 里不含自增，那一次提问就被吞掉了。
-- ============================================================
CREATE TABLE IF NOT EXISTS `knowledge_gap` (
    `id`             BIGINT      AUTO_INCREMENT PRIMARY KEY COMMENT '缺口ID',
    `ethnicity`      VARCHAR(50) NOT NULL COMMENT '民族',
    `disease`        VARCHAR(50) NOT NULL COMMENT '疾病',
    `intent`         VARCHAR(30) NOT NULL COMMENT '想了解的方面（意图码，如 genetics）',
    `ask_count`      INT         DEFAULT 0 COMMENT '被问次数（每次未命中 +1，全用户累加）',
    `feedback_count` INT         DEFAULT 0 COMMENT '反馈人数（按用户去重，见下面那张表）',
    `status`         VARCHAR(20) DEFAULT 'open' COMMENT 'open 待补充 / filled 已补充',
    `filled_doc_id`  VARCHAR(64) COMMENT '补录并审核通过的那份文档ID',
    `created_at`     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP COMMENT '首次出现时间',
    `last_asked_at`  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP COMMENT '最后一次被问时间'
);
-- 幂等键：同一「民族 + 疾病 + 方面」全库一行，重复未命中走自增而不是新增
CREATE UNIQUE INDEX IF NOT EXISTS `uk_gap_slots` ON `knowledge_gap` (`ethnicity`, `disease`, `intent`);
CREATE INDEX IF NOT EXISTS `idx_gap_ask` ON `knowledge_gap` (`ask_count`);
CREATE INDEX IF NOT EXISTS `idx_gap_status` ON `knowledge_gap` (`status`);

CREATE TABLE IF NOT EXISTS `knowledge_gap_feedback` (
    `id`         BIGINT    AUTO_INCREMENT PRIMARY KEY COMMENT '反馈ID',
    `gap_id`     BIGINT    NOT NULL COMMENT '所属缺口ID',
    `user_id`    BIGINT    NOT NULL COMMENT '反馈用户ID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '反馈时间'
);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_gap_feedback` ON `knowledge_gap_feedback` (`gap_id`, `user_id`);

-- ============================================================
-- 缺口的两个来源维度（增量加列）
--
-- 为什么是两列**标记**而不是一列互斥的枚举：
--   唯一键是「民族 + 疾病 + 方面」，全库只有一行。同一个组合完全可能先被高级检索记过、
--   后来又有用户在对话里点了【反馈此问题】——那一行就同时来自两个入口。用一列枚举只能
--   记一个，另一个入口的事实就丢了（榜单上会显示成「只有高级检索」，与用户刚做的事对不上）。
--   两列各记各的，界面上两个徽标可以同时出现。
--
-- 为什么 from_search 默认 1：
--   存量行全部产生于这次改动之前，那时唯一的自动来源就是高级检索，给它们标上「高级检索」
--   是符合事实的，也就不必再写一条 UPDATE 回填。
--
-- 两列都是 0 的情形只有一种：留言板反馈被管理员转成缺口（GapOrigin.BOARD）——
--   它既不是检索未命中，也不是对话里点的反馈，界面给它一个中性的「用户反馈」徽标。
--
-- original_query 只有「对话」与「留言板」来源才有：高级检索那边用户只选了三个下拉，
--   根本没有一句完整的话可存。
-- ============================================================
ALTER TABLE `knowledge_gap` ADD COLUMN IF NOT EXISTS `from_search` TINYINT DEFAULT 1
    COMMENT '来源之一：高级检索未命中自动登记过';
ALTER TABLE `knowledge_gap` ADD COLUMN IF NOT EXISTS `from_chat` TINYINT DEFAULT 0
    COMMENT '来源之一：用户在智能对话里主动点过「反馈此问题」';
ALTER TABLE `knowledge_gap` ADD COLUMN IF NOT EXISTS `original_query` VARCHAR(500)
    COMMENT '首次触发这条缺口的原始提问（对话/留言板来源才有）';

-- 补录来源：这份文档是为补哪个缺口而传的（可空——绝大多数上传与缺口无关）。
-- 审核**通过且索引同步成功**之后，把对应缺口标为已补充（见 KbService.review）。
-- 只打「通过」还不够：同步失败时文档根本没进检索索引，标成已补充会让缺口凭空消失。
ALTER TABLE `kb_document` ADD COLUMN IF NOT EXISTS `gap_id` BIGINT COMMENT '补录来源的缺口ID（可空）';

-- ============================================================
-- 知识库分区（kb_document.partition）
-- 把知识库拆成两半，检索时优先用前者：
--   integrated  整合资料库 —— 多篇文献提炼出来的结构化文档（《数据资料》《文献(2)》、
--               文献补录工作台产出的汇编）。优先检索。
--   raw         原始文献库 —— 知网/SinoMed 下载的论文原文、官方完整报告。降级检索。
--
-- 为什么按「扩展名」分而不是让上传者选：pdf 就是论文原文，docx/md 在本项目里都是
-- 人工整理的汇编（见 kb_data 目录的实际构成）。少一个必填项，也少一处填错的机会。
-- 判错的代价不大：知识库页面上每个文档都能手工改属哪个库。
--
-- ⚠️ 同一条规则在 AI 服务侧也有一份（`app/services/kb.py` 的 `_partition_of`）——
-- 那边要处理的是一旦落盘就不会再同步的索引文件，只能自己从 file_name 推断。
-- 改这里的规则时，那边要一起改。
-- ============================================================
ALTER TABLE `kb_document` ADD COLUMN IF NOT EXISTS `partition` VARCHAR(20) COMMENT '知识库分区：integrated 整合资料库（优先检索）/ raw 原始文献库（降级检索）';
CREATE INDEX IF NOT EXISTS `idx_kb_doc_partition` ON `kb_document` (`partition`);

-- 存量归类。带 `IS NULL` 守卫，所以每次启动重跑都是空操作；
-- 顺序不能反：先认领 pdf，剩下的才归整合资料库。
UPDATE `kb_document` SET `partition` = 'raw' WHERE `partition` IS NULL AND LOWER(`ext`) = 'pdf';
UPDATE `kb_document` SET `partition` = 'integrated' WHERE `partition` IS NULL;

-- ============================================================
-- 用户反馈留言板（user_feedback + user_feedback_like）
-- 用户在这里说「我想了解某民族某疾病的什么」，按点赞数排序，管理员据此决定补录什么。
--
-- 与 knowledge_gap 的区别（两者都在回答「缺什么」，但来源与形状不同）：
--   knowledge_gap   来自**高级检索未命中**，天生带「民族 / 疾病 / 方面」三个槽位，是结构化需求。
--   user_feedback   来自**用户自由输入**，是一句话，槽位要事后识别，表达的东西也更宽
--                   （可能是抱怨、可能是提问、也可能指向知识库之外的东西）。
--   所以两者并存：一个是「系统量出来的」，一个是「用户说出来的」。
--
-- user_id 可空：这是全站唯一允许**未登录发布**的表。
--   顶栏的【用户反馈】是公开页面（未登录可读可发），所以匿名帖的发布者是 NULL，
--   卡片上显示「匿名用户」。有 id 的帖子仍照常显示昵称与头像。
--
-- like_count 存成一列而不是每次 COUNT 聚合：列表要按它排序，聚合排序用不上索引。
--   明细表 user_feedback_like 才是权威，这一列只是它的缓存，每次点赞/取消后用
--   COUNT(*) 重算一遍（照 GapDao.refreshFeedbackCount 的思路，任何一处漏掉都能自愈）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_feedback` (
    `id`         BIGINT      AUTO_INCREMENT PRIMARY KEY COMMENT '反馈ID',
    `user_id`    BIGINT      COMMENT '发布者ID；NULL = 未登录的匿名发布',
    `content`    TEXT        NOT NULL COMMENT '反馈原文（用户原话，保留换行）',
    `status`     VARCHAR(20) DEFAULT 'pending' COMMENT 'pending 待补充 / processing 处理中 / done 已补充',
    `gap_id`     BIGINT      COMMENT '转为知识缺口后指向它（可空，照 kb_document.gap_id 的先例）',
    `like_count` INT         DEFAULT 0 COMMENT '点赞数（由 user_feedback_like 重算，不是自增维护）',
    `created_at` TIMESTAMP   DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间'
);
-- 列表按「点赞数倒序 + id 倒序」取，这两个索引对应那个排序键
CREATE INDEX IF NOT EXISTS `idx_feedback_like` ON `user_feedback` (`like_count`);
CREATE INDEX IF NOT EXISTS `idx_feedback_status` ON `user_feedback` (`status`);

CREATE TABLE IF NOT EXISTS `user_feedback_like` (
    `id`          BIGINT    AUTO_INCREMENT PRIMARY KEY COMMENT '点赞ID',
    `feedback_id` BIGINT    NOT NULL COMMENT '被点赞的反馈ID',
    `user_id`     BIGINT    NOT NULL COMMENT '点赞用户ID',
    `created_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间'
);
-- 同一用户对同一条只能点一次赞。再点是**取消**（删行），不是加一行——
-- 没有这条约束的话，一个人连点就能把某条刷到榜首，排行榜就失去意义了。
CREATE UNIQUE INDEX IF NOT EXISTS `uk_feedback_like` ON `user_feedback_like` (`feedback_id`, `user_id`);

