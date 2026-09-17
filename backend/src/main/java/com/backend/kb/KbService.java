package com.backend.kb;

import com.backend.gap.dao.GapDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** PDF 页分隔符：文本抽取器在每页间插入的换页符，阅读器据此切页并插入原页图。 */
/**
 * 知识库服务：文件落盘 + 全文提取 + H2 元数据。
 * 上传/查询/删除为对外核心能力；不依赖外部 Python 服务即可工作。
 */
@Service
public class KbService {

    private static final List<String> ALLOWED = Arrays.asList(
            "pdf", "docx", "doc", "txt", "md", "csv", "tsv", "json", "rtf");

    /** PDF 页分隔符：文本抽取器在每页间插入的换页符，阅读器据此切页并插入原页图。 */
    private static final char PDF_PAGE_SEP = '\u000c';

    private final JdbcTemplate jdbcTemplate;
    private final String storageDir;

    // ── 来源等级（一级分类）────────────────────────────────────────
    /** 官方上传：人工筛选/审核过的权威资料，AI 可直接当权威结论引用 */
    public static final String SOURCE_OFFICIAL = "official";
    /** 网页抓取：URL 自动抓取、清洗后入库的公开内容 */
    public static final String SOURCE_WEB = "web_crawl";
    /** 用户上传：普通用户提交，需审核通过才正式入库 */
    public static final String SOURCE_USER = "user_upload";

    // ── 知识库分区（与上面的「来源等级」是两个正交的维度）──────────────
    // 来源等级说的是「这份资料可不可信」；分区说的是「它是原文还是提炼过的」。
    // 检索时先查整合资料库，命中不了才降级去翻原始文献（见 ai-service 的检索层）。
    /** 整合资料库：多篇文献提炼出来的结构化文档，优先检索 */
    public static final String PARTITION_INTEGRATED = "integrated";
    /** 原始文献库：论文原文 / 官方完整报告，降级检索 */
    public static final String PARTITION_RAW = "raw";

    /**
     * 按扩展名判定分区。
     *
     * <p>pdf 就是论文原文，docx / md 在本项目里都是人工整理的汇编——少一个必填项，
     * 也少一处填错的机会。判错代价不大：知识库页面上每个文档都能手工改属哪个库。</p>
     *
     * <p><b>同一条规则在 AI 服务侧也有一份</b>（{@code app/services/kb.py} 的
     * {@code _partition_of}）：那边要处理一旦落盘就不再同步的索引文件，只能自己从
     * file_name 推断。改这里时那边要一起改。</p>
     */
    public static String partitionOf(String ext) {
        return "pdf".equalsIgnoreCase(ext == null ? "" : ext)
                ? PARTITION_RAW : PARTITION_INTEGRATED;
    }

    // ── 审核状态（刻意与解析用的 status 分开）──────────────────────
    /** 通过（默认值，存量数据与官方上传都走这里） */
    public static final String REVIEW_APPROVED = "approved";
    /** 待审核 */
    public static final String REVIEW_PENDING = "pending";
    /** 已驳回 */
    public static final String REVIEW_REJECTED = "rejected";

    /**
     * 官方账号白名单（配置项 {@code app.kb.official-user-ids}）。
     *
     * <p>用配置而不是硬编码用户 ID：ID 是自增的，写死在 Java 里换套环境就失效；
     * 将来加官方账号也只改配置。</p>
     */
    private final Set<Long> officialUserIds;

    /** 「从链接导入」的域名白名单——服务端请求的唯一放行口 */
    private final ImportWhitelist importWhitelist;

    /**
     * 知识缺口（只用到两个动作：审核通过时标已补充、删除文档时退回待补充）。
     *
     * <p>方向是 KbService → GapDao 的单向依赖：缺口本身不知道知识库的存在，
     * 由知识库在「审核/删除」这两个真正改变事实的地方回报结果，不会形成环。</p>
     */
    private final GapDao gapDao;

    /** 大模型辅助提取：调用 Python AI 服务（best-effort，失败自动降级为 Java 启发式结果） */
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient AI_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)  // 关键：禁用 HTTP/2(h2c) 协商，否则 body 被 uvicorn/h11 丢弃→422
            .build();
    private static final String AI_BASE_URL = "http://127.0.0.1:8000";
    private static final int AI_TIMEOUT_SECONDS = 90;

    /** 页→PNG 缓存（docId#page → png bytes），避免每次阅读重复渲染。 */
    private final Map<String, byte[]> pageCache = new ConcurrentHashMap<>();

    /** 后台处理执行器：上传后异步跑 LLM 总结 / 证据抽取 / 索引同步，用户无需停留在页面等待。 */
    private final ExecutorService jobExecutor = Executors.newCachedThreadPool();

    /** 处理中任务进度（docId -> 进度/阶段）。内存态：进程重启后由 init() 清理为 done。 */
    private final Map<String, JobState> jobStates = new ConcurrentHashMap<>();

    private static final class JobState {
        final int progress;
        final String stage;
        JobState(int progress, String stage) {
            this.progress = progress;
            this.stage = stage;
        }
    }

    /**
     * 来源维度（一级分类）的上传参数。
     *
     * <p>单独成组而不是继续往 {@code upload()} 上加位置参数：那个方法已经有 9 个参数，
     * 再加 4 个（其中两个还是相邻的 String）极易传错位置，且编译器不会报错。</p>
     *
     * @param userId      上传者；鉴权修复前为 null
     * @param sourceLevel 前端指定的来源等级；为空时按 {@code userId} 自动判定
     * @param sourceOrg   来源机构（国家卫健委 / 人民日报 / 用户上传…）
     * @param sourceUrl   原始链接，网页抓取时记录
     * @param gapId       文献补录的来源缺口；非空时**强制进待审核**，审核通过（且索引同步成功）
     *                    后由 {@code review} 把该缺口标为已补充。普通上传一律为 null。
     */
    public record SourceInfo(Long userId, String sourceLevel, String sourceOrg, String sourceUrl, Long gapId) {
        public static SourceInfo of(Long userId, String sourceLevel, String sourceOrg, String sourceUrl) {
            return new SourceInfo(userId, sourceLevel, sourceOrg, sourceUrl, null);
        }

        /** 文献补录工作台用：带上要补的那个缺口 */
        public static SourceInfo forGap(Long userId, String sourceLevel, String sourceOrg,
                                        String sourceUrl, Long gapId) {
            return new SourceInfo(userId, sourceLevel, sourceOrg, sourceUrl, gapId);
        }
    }

    public KbService(JdbcTemplate jdbcTemplate,
                    @Value("${app.kb.storage-dir:kb_data}") String storageDir,
                    @Value("${app.kb.official-user-ids:1}") String officialUserIds,
                    ImportWhitelist importWhitelist,
                    GapDao gapDao) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageDir = storageDir;
        this.officialUserIds = parseIds(officialUserIds);
        this.importWhitelist = importWhitelist;
        this.gapDao = gapDao;
    }

    /** 解析 "1,34" 形式的 ID 列表；单项写错只跳过该项，不让整个服务起不来 */
    private static Set<Long> parseIds(String raw) {
        Set<Long> out = new HashSet<>();
        if (raw == null) return out;
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                // 配置项写错不该阻断启动
            }
        }
        return out;
    }

    /** 该用户是否属于官方账号——决定其上传资料的来源等级与是否免审核 */
    public boolean isOfficial(Long userId) {
        return userId != null && officialUserIds.contains(userId);
    }

    /** 归一化来源等级；无法识别时返回 null，交由调用方按上传者身份兜底 */
    private static String normalizeLevel(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase();
        return switch (v) {
            case SOURCE_OFFICIAL, SOURCE_WEB, SOURCE_USER -> v;
            default -> null;
        };
    }

    /** 归一化审核状态；无法识别时返回 null（= 不筛） */
    private static String normalizeReview(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase();
        return switch (v) {
            case REVIEW_APPROVED, REVIEW_PENDING, REVIEW_REJECTED -> v;
            default -> null;
        };
    }

    /**
     * 审核一份用户提交的资料（通过 / 驳回）。
     *
     * <p>通过时由管理员裁决归入的一级分类（官方权威资料 / 网页抓取补充），通过后才把切片
     * 推给 AI 检索索引——上传时因为 pending 被跳过了这一步；驳回则从索引移除（万一之前推过）。
     * **不删文件**：留着便于追溯与反悔。</p>
     */
    public void review(String id, String decision, String targetLevel) {
        String rs = normalizeReview(decision);
        if (!REVIEW_APPROVED.equals(rs) && !REVIEW_REJECTED.equals(rs)) {
            throw new IllegalArgumentException("审核结论只能是 approved 或 rejected");
        }
        if (REVIEW_APPROVED.equals(rs)) {
            // 管理员选择归入的分类：official / web_crawl（未传或传了 user_upload 时兜底为官方）
            String lv = normalizeLevel(targetLevel);
            if (lv == null || SOURCE_USER.equals(lv)) lv = SOURCE_OFFICIAL;
            List<KbDocDto> cur = jdbcTemplate.query(
                    "SELECT * FROM kb_document WHERE id = ?", ROW_MAPPER_WITH_CONTENT, id);
            if (cur.isEmpty()) throw new IllegalStateException("文档不存在");
            KbDocDto d = cur.get(0);
            // 来源机构为空或仍是「用户上传」占位时，按归入的分类给兜底文案
            String org = d.getSourceOrg();
            if (org == null || org.isBlank() || "用户上传".equals(org)) org = defaultOrg(lv);
            jdbcTemplate.update(
                    "UPDATE kb_document SET review_status = ?, source_level = ?, source_org = ? WHERE id = ?",
                    rs, lv, org, id);
            // 补推索引（sourceMetaOf 从库里读刚更新的 source_level，保证两端一致）
            String text = d.getContent() == null ? "" : d.getContent();
            boolean indexed = callPythonDocumentSync(id, d.getTitle(), d.getOriginalName(), text);
            // 补录来源的缺口：只有**真的进了索引**才算补上。
            // 同步失败时把缺口留在「待补充」——否则榜上少一条、而用户再检索依然未命中，
            // 等于把问题藏了起来，比暴露更难发现。
            Long gapId = gapIdOf(id);
            if (gapId != null) {
                if (indexed) {
                    gapDao.markFilled(gapId, id);
                } else {
                    System.err.println("[KbService.review] 文档已通过但索引同步失败，缺口保持待补充：gapId="
                            + gapId + " docId=" + id);
                }
            }
        } else {
            // 驳回：缺口维持「待补充」，无需额外处理——它本来就只在审核通过时才被标 filled
            int n = jdbcTemplate.update(
                    "UPDATE kb_document SET review_status = ? WHERE id = ?", rs, id);
            if (n == 0) throw new IllegalStateException("文档不存在");
            callPythonDocumentRemove(id);
        }
    }

    /** 调用方没填来源机构时的兜底文案 */
    private static String defaultOrg(String level) {
        return switch (level) {
            case SOURCE_WEB -> "网页抓取";
            case SOURCE_USER -> "用户上传";
            default -> "官方权威资料";
        };
    }

    /**
     * 读某份文档关联的知识缺口 id。
     *
     * <p>单独查一列而不是加进 {@code ROW_MAPPER_WITH_CONTENT}：那个 mapper 用在别处的
     * 「读全文」路径上，为了一个只有审核/删除两个调用点用得到的字段去扩它，
     * 会让所有读文档的地方都多带一列无用数据。</p>
     */
    private Long gapIdOf(String docId) {
        List<Long> list = jdbcTemplate.query("SELECT gap_id FROM kb_document WHERE id = ?",
                (rs, i) -> {
                    long v = rs.getLong("gap_id");
                    return rs.wasNull() ? null : v;
                }, docId);
        return list.isEmpty() ? null : list.get(0);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(Paths.get(storageDir));
        // 清理上次进程遗留的「处理中」状态：进程重启后后台任务不会恢复，启发式结果已入库，标记为完成。
        try {
            jdbcTemplate.update("UPDATE kb_document SET status = 'done' WHERE status = 'processing'");
        } catch (Exception ignored) {
        }
    }

    private static final RowMapper<KbDocDto> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        Timestamp ts = rs.getTimestamp("created_at");
        long uploadedAt = ts != null ? ts.getTime() : 0L;
        int pc = rs.getInt("page_count");
        boolean pageCountNull = rs.wasNull();
        KbDocDto d = new KbDocDto(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("original_name"),     // fileName
                rs.getString("original_name"),     // originalName
                rs.getLong("size"),
                rs.getString("ext"),
                rs.getString("mime"),
                uploadedAt,
                pageCountNull ? null : pc,
                rs.getBoolean("has_content"),
                null,                              // 列表不含全文
                rs.getString("author"),
                rs.getString("source"),
                (Integer) rs.getObject("publish_year"),
                rs.getString("summary"),
                rs.getString("article_summary"),
                rs.getString("evidence_json")
        );
        String st = rs.getString("status");
        d.setStatus(st == null ? "done" : st);
        d.setProgress(100);
        // 来源维度：加列之前入库的历史数据取不到值，这里兜一层默认，
        // 免得前端拿到 null 后显示成空白标签（列本身也有 DEFAULT，这是双保险）
        String sl = rs.getString("source_level");
        d.setSourceLevel(sl == null ? SOURCE_OFFICIAL : sl);
        d.setSourceOrg(rs.getString("source_org"));
        d.setSourceUrl(rs.getString("source_url"));
        String rv = rs.getString("review_status");
        d.setReviewStatus(rv == null ? REVIEW_APPROVED : rv);
        d.setUserId((Long) rs.getObject("user_id"));
        // 分区：加列之前入库的数据取不到值，兜成整合资料库（与 SQL 里那次存量归类一致）
        String pt = rs.getString("partition");
        d.setPartition(pt == null || pt.isBlank() ? PARTITION_INTEGRATED : pt);
        return d;
    };

    private static final RowMapper<KbDocDto> ROW_MAPPER_WITH_CONTENT = (ResultSet rs, int rowNum) -> {
        KbDocDto d = ROW_MAPPER.mapRow(rs, rowNum);
        d.setContent(rs.getString("content"));
        return d;
    };

    public KbDocDto upload(MultipartFile file, String title, String category,
                           String source, String author, Integer publishYear,
                           String summary, String articleSummary, String evidenceJson,
                           SourceInfo src) throws IOException {
        String name = file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename();
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        return store(file.getBytes(), name, mime, title, category, source, author, publishYear,
                summary, articleSummary, evidenceJson, src, false);
    }

    /**
     * 落库核心：落盘 → 提全文 → 抽元数据 → 裁决来源 → INSERT → 提交后台任务。
     *
     * <p>浏览器上传与网页导入只差「字节从哪来」，所以两条路径共用这里——
     * 切分、抽证据、索引同步、审核闸门全部复用，不必各写一套。</p>
     *
     * @param requireReview 强制进待审核。网页抓来的内容**无论谁导入**都要人工过目一遍，
     *                      不能因为导入者是官方账号就直接入库。
     */
    private KbDocDto store(byte[] bytes, String originalName, String mime, String title,
                           String category, String source, String author, Integer publishYear,
                           String summary, String articleSummary, String evidenceJson,
                           SourceInfo src, boolean requireReview) throws IOException {
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) ext = originalName.substring(dot + 1).toLowerCase();
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型：." + (ext.isEmpty() ? "未知" : ext));
        }
        // 重名不再覆盖：同名文件允许并存，标题在下方 uniquifyTitle 里自动加序号
        //（《数据资料》《数据资料1》《数据资料2》……），存储文件名与标题保持一致。
        String id = UUID.randomUUID().toString().replace("-", "");

        // 快速同步完成：落盘 + 提取全文 + 启发式元数据/证据；慢的 LLM 总结/证据/索引交给后台线程，用户无需停留等待。
        TextExtractor.ExtractResult ex = TextExtractor.extract(ext, bytes);
        boolean hasContent = ex.text != null && !ex.text.isBlank();
        // 正文存库带页分隔符（供阅读器切页插原图）；但启发式/LLM 用去掉分隔符的干净文本，避免控制字符干扰行级正则。
        String cleanText = cleanForText(ex.text);

        // 自动识别文献元数据（标题/作者/出处/年份/类别/摘要）
        MetadataExtractor.DocMeta meta = MetadataExtractor.extract(cleanText, originalName);

        // 标题：优先调用方传入 -> 原文件名（去扩展名）。不再从正文自动识别标题，保持「原标题是什么就是什么」。
        // 重名自动加序号：《数据资料》《数据资料1》《数据资料2》……先到先得，永不覆盖。
        String baseName = dot > 0 ? originalName.substring(0, dot) : originalName;
        String safeTitle = uniquifyTitle(
                (title == null || title.trim().isEmpty()) ? baseName : title.trim());
        // 存储文件名与标题保持一致（docId 前缀防物理冲突），列表/下载/检索引用显示同名
        String storedName = id + "_" + sanitize(safeTitle + (ext.isEmpty() ? "" : "." + ext));
        Path target = Paths.get(storageDir, storedName);
        Files.write(target, bytes);
        // 类别：优先调用方传入 -> 自动识别 -> "其他"
        String safeCategory = (category == null || category.trim().isEmpty())
                ? (meta.category != null ? meta.category : MetadataExtractor.DEFAULT_CATEGORY)
                : category.trim();

        // ── 来源等级：以调用方传入为准，但**官方身份由后端裁决** ──────────
        // 不能让上传者自称官方：否则 source_level 只是个自选标签，「⭐⭐⭐ 权威」就没有任何约束力。
        // 非白名单账号即便传了 official，也一律降级为 user_upload 并进入待审核。
        Long uploaderId = src == null ? null : src.userId();
        String level = normalizeLevel(src == null ? null : src.sourceLevel());
        if (level == null) {
            level = isOfficial(uploaderId) ? SOURCE_OFFICIAL : SOURCE_USER;
        }
        if (SOURCE_OFFICIAL.equals(level) && !isOfficial(uploaderId)) {
            level = SOURCE_USER;
        }
        // 需要人工过目的三类：用户上传（来源不可信）、网页抓取（内容没过审核）、
        // 文献补录（是冲着某个具体缺口去的，必须有人核对补对了没有）。
        // 补录那条不能靠调用方传 requireReview——store 是共用入口，让 src 自己带着意图更不容易漏。
        String safeReview = (requireReview || SOURCE_USER.equals(level)
                || (src != null && src.gapId() != null))
                ? REVIEW_PENDING : REVIEW_APPROVED;
        String safeOrg = (src == null || src.sourceOrg() == null || src.sourceOrg().isBlank())
                ? defaultOrg(level) : src.sourceOrg().trim();
        String safeUrl = src == null ? null : src.sourceUrl();
        // 出处/期刊：优先调用方传入 -> 自动识别
        String safeSource = (source == null || source.trim().isEmpty())
                ? meta.source : source.trim();
        // 作者：优先调用方传入 -> 自动识别
        String safeAuthor = (author == null || author.trim().isEmpty())
                ? meta.author : author.trim();
        // 年份：优先调用方传入 -> 自动识别
        Integer safeYear = (publishYear == null)
                ? meta.publishYear : publishYear;
        // 摘要：仅文档真实摘要（非 LLM）。summary 未传(null)=用自动识别；已传（含清空''）=以用户为准。
        String safeSummary;
        if (summary == null) {
            safeSummary = (meta.summary != null) ? meta.summary.trim() : null;
        } else {
            safeSummary = summary.trim();
        }
        // 文章总结：AI 概括主要内容。articleSummary 已传（含清空）→以用户为准（同步）；未传(null)→后台 LLM 生成。
        boolean needSummarize = (articleSummary == null);
        String safeArticleSummary = needSummarize ? null : articleSummary.trim();

        // 结构化证据：前端「解析→确认」已回传非空 evidenceJson →直接采用（同步）；未传→先启发式入库，后台再补 LLM 片段。
        EvidenceExtractor.ExtractedEvidence evidence = EvidenceExtractor.extract(cleanText, originalName);
        boolean needEvidence = (evidenceJson == null || evidenceJson.trim().isEmpty());
        String finalEvidenceJson = needEvidence ? evidence.toJson() : evidenceJson.trim();

        // 先以「处理中」入库，立即返回；LLM 总结/证据/索引在后台补齐。
        jdbcTemplate.update(
                "INSERT INTO kb_document (id, title, category, original_name, stored_name, ext, mime, size, "
                        + "content, has_content, page_count, author, source, publish_year, summary, article_summary, evidence_json, created_at, status, "
                        + "user_id, source_level, source_org, source_url, review_status, gap_id, partition) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, safeTitle, safeCategory, originalName, storedName, ext, mime,
                bytes.length, ex.text, hasContent, ex.pageCount == null ? 0 : ex.pageCount,
                safeAuthor, safeSource, safeYear, safeSummary, safeArticleSummary, finalEvidenceJson,
                Timestamp.valueOf(LocalDateTime.now()), "processing",
                uploaderId, level, safeOrg, safeUrl, safeReview,
                src == null ? null : src.gapId(), partitionOf(ext));

        jobStates.put(id, new JobState(0, "已接收，排队处理中"));
        // searchable：只有已通过审核的文档才推进检索索引。
        // 用户上传默认 pending，此时**不入索引**——否则未审核内容会被 AI 检索到并当成依据引用。
        boolean searchable = REVIEW_APPROVED.equals(safeReview);
        jobExecutor.submit(() -> processAsync(id, safeTitle, cleanText, originalName, ex.text,
                finalEvidenceJson, evidence, needSummarize, needEvidence, searchable));

        KbDocDto dto = new KbDocDto(id, safeTitle, safeCategory, originalName, originalName,
                bytes.length, ext, mime, System.currentTimeMillis(),
                ex.pageCount, hasContent, ex.text,
                safeAuthor, safeSource, safeYear, safeSummary, safeArticleSummary, finalEvidenceJson);
        dto.setStatus("processing");
        dto.setProgress(0);
        dto.setStage("已接收，排队处理中");
        dto.setSourceLevel(level);
        dto.setSourceOrg(safeOrg);
        dto.setSourceUrl(safeUrl);
        dto.setReviewStatus(safeReview);
        dto.setUserId(uploaderId);
        return dto;
    }

    /**
     * 从链接导入：抓一个白名单内的网页，清洗后当成一份资料入库。
     *
     * <p>**不另写导入链路**——把抓来的正文写成 `.md`，喂进 {@link #store}，
     * 于是切分、抽证据、审核闸门、索引同步全部复用现有实现。</p>
     *
     * <p>两道白名单校验，缺一不可：</p>
     * <ol>
     *   <li><b>请求前</b>校验用户给的 URL——不然这个接口就是 SSRF 跳板，
     *       能拿服务器去请求内网地址。</li>
     *   <li><b>抓取后</b>再校验最终 URL——白名单站点 302 到白名单外/内网就绕过了第一道。</li>
     * </ol>
     *
     * <p>抓来的内容**一律进待审核**（requireReview=true）：不管导入者是不是官方账号，
     * 网页内容都得人工过目一遍才能进知识库和检索索引。</p>
     */
    /** 白名单内的网页抓取结果 */
    private record FetchedPage(String org, String title, String finalUrl, String publishedAt, String text) {}

    /**
     * 白名单校验 → 抓取 → 复校验最终地址。
     *
     * <p>{@code importFromUrl}（抓来即落库）与 {@code supplySource}（只取正文供抽取）共用这一段：
     * 白名单是**服务端请求的唯一放行口**，抄成两份迟早只改一处——那就等于开了个后门。</p>
     */
    private FetchedPage fetchWhitelisted(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("请输入链接");
        }
        // ① 请求前：白名单
        String org = importWhitelist.displayNameFor(url.trim());
        if (org == null) {
            String why = importWhitelist.isEmpty()
                    ? "服务端未配置导入白名单（app.kb.import-whitelist）"
                    : "只允许导入白名单内的站点：" + importWhitelist.describe();
            throw new IllegalArgumentException(why);
        }
        // ② 交给 AI 服务抓取 + 清洗（HTML 正文提取要专门的库，Java 侧不做）
        Map<String, Object> got = callPythonFetchUrl(url.trim());
        String text = str(got.get("text"));
        if (text.isBlank()) {
            throw new IllegalStateException("没抓到正文");
        }
        String finalUrl = str(got.get("finalUrl"));
        // ③ 抓取后：复校验最终地址（跟随重定向之后，白名单站点可能 302 到内网）
        if (!finalUrl.isBlank() && importWhitelist.displayNameFor(finalUrl) == null) {
            throw new IllegalArgumentException("链接跳转到了白名单之外的地址，已拒绝：" + finalUrl);
        }
        return new FetchedPage(org, str(got.get("title")),
                finalUrl.isBlank() ? url.trim() : finalUrl, str(got.get("publishedAt")), text);
    }

    public KbDocDto importFromUrl(String url, Long userId) throws IOException {
        return importFromUrl(url, userId, null);
    }

    /** @param gapId 文献补录来源的缺口；非空时审核通过且索引成功后会把它标为已补充 */
    public KbDocDto importFromUrl(String url, Long userId, Long gapId) throws IOException {
        FetchedPage page = fetchWhitelisted(url);
        String org = page.org();
        String finalUrl = page.finalUrl();
        String text = page.text();

        // 落成 .md 走现有流程。标题用抓到的；年份从 trafilatura 的日期里取，
        // 其余元数据（分类/作者/出处/摘要）留空，交给 MetadataExtractor 自动识别。
        String title = page.title();
        if (title.isBlank()) title = "网页导入";
        String name = sanitize(title) + ".md";
        Integer year = yearOf(page.publishedAt());
        SourceInfo src = (gapId == null)
                ? SourceInfo.of(userId, SOURCE_WEB, org, finalUrl)
                : SourceInfo.forGap(userId, SOURCE_WEB, org, finalUrl, gapId);

        return store(text.getBytes(StandardCharsets.UTF_8), name, "text/markdown",
                title, null, null, null, year, null, null, null, src, true);
    }

    /**
     * 文献补录工作台专用：**只取正文，不入库**。
     *
     * <p>为什么需要它：工作台要先拿到正文才能抽数据行，而「抓取」这一步必须过白名单，
     * 白名单逻辑只在服务端。绝不能让前端直接去打 AI 服务的 /api/ai/kb/fetch-url——
     * 那条路径没有白名单，等于把 SSRF 的闸门敞开。</p>
     *
     * <p>刻意不复用 {@code parse()}：那个会顺带跑 LLM 做摘要与证据抽取（两次大模型调用、
     * 数秒），而这里只需要纯文本提取。</p>
     */
    public Map<String, Object> supplySource(MultipartFile file, String url) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (file != null && !file.isEmpty()) {
            String name = file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename();
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) ext = name.substring(dot + 1).toLowerCase();
            if (!ALLOWED.contains(ext)) {
                throw new IllegalArgumentException("不支持的文件类型：." + (ext.isEmpty() ? "未知" : ext));
            }
            String text = cleanForText(TextExtractor.extract(ext, file.getBytes()).text);
            out.put("title", name);
            out.put("text", text);
            out.put("chars", text.length());
            return out;
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("请上传文件或填写链接");
        }
        FetchedPage page = fetchWhitelisted(url);
        out.put("title", page.title());
        out.put("url", page.finalUrl());
        out.put("sourceOrg", page.org());
        out.put("text", page.text());
        out.put("chars", page.text().length());
        return out;
    }

    /** 从 "2026-09-11" 这类日期里取年份；取不到返回 null（交给自动识别） */
    private static Integer yearOf(String date) {
        if (date == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})").matcher(date);
        if (!m.find()) return null;
        try {
            int y = Integer.parseInt(m.group(1));
            return (y >= 1900 && y <= 2100) ? y : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 粘贴导入：管理员自己把正文复制进来（用于抓不了的站点，如 SinoMed——
     * 它检索要登录 + 验证码，服务端抓不到）。
     *
     * <p>与链接导入**结果完全一致**：同样标记 web_crawl、同样进待审核、同样走 store 落库。
     * 区别只在「正文从哪来」，所以两条路都叫「导入」，管理员不必记两套操作。</p>
     *
     * <p>不校验白名单：内容已经在管理员的浏览器里显示过、由他亲手复制，
     * 不存在 SSRF 问题。sourceUrl 仅作溯源记录，服务端不会去访问它。</p>
     */
    public KbDocDto importText(String text, String title, String sourceUrl, Long userId) throws IOException {
        return importText(text, title, sourceUrl, userId, null);
    }

    /** @param gapId 文献补录来源的缺口；非空时审核通过且索引成功后会把它标为已补充 */
    public KbDocDto importText(String text, String title, String sourceUrl, Long userId,
                               Long gapId) throws IOException {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("请粘贴正文内容");
        }
        String name = sanitize(
                (title == null || title.isBlank()) ? "粘贴导入" : title.trim()) + ".md";
        String srcUrl = (sourceUrl == null || sourceUrl.isBlank()) ? null : sourceUrl.trim();
        SourceInfo src = (gapId == null)
                ? SourceInfo.of(userId, SOURCE_WEB, "粘贴导入", srcUrl)
                : SourceInfo.forGap(userId, SOURCE_WEB, "粘贴导入", srcUrl, gapId);
        return store(body.getBytes(StandardCharsets.UTF_8), name, "text/markdown",
                title, null, null, null, null, null, null, null, src, true);
    }

    /**
     * 用当前的解析规则重新解析一份已入库的资料。
     *
     * <p>正文是**入库那一刻**存下来的，解析规则升级（比如 docx 转 Markdown 的排版变好）
     * 不会回溯到老文档。重新上传又会多出一份副本（同名时标题自动加序号），
     * 所以给一条原地重解析的路。</p>
     *
     * <p>已通过审核的还要**重推检索索引**，否则生成回答时用的仍是旧文本。</p>
     */
    public KbDocDto reparse(String id) throws IOException {
        List<KbDocDto> cur = jdbcTemplate.query(
                "SELECT * FROM kb_document WHERE id = ?", ROW_MAPPER_WITH_CONTENT, id);
        if (cur.isEmpty()) throw new IllegalStateException("文档不存在");
        KbDocDto d = cur.get(0);
        Path file = Paths.get(storageDir, d.getFileName());
        if (!Files.exists(file)) {
            throw new IllegalStateException("原始文件已不在存储目录，无法重新解析");
        }
        TextExtractor.ExtractResult ex = TextExtractor.extract(d.getExt(), Files.readAllBytes(file));
        String text = ex.text == null ? "" : ex.text;
        jdbcTemplate.update(
                "UPDATE kb_document SET content = ?, has_content = ?, page_count = ? WHERE id = ?",
                ex.text, !text.isBlank(), ex.pageCount == null ? 0 : ex.pageCount, id);
        // 待审核的还没进索引，不必重推——审核通过时会按新内容推
        if (REVIEW_APPROVED.equals(d.getReviewStatus())) {
            callPythonDocumentSync(id, d.getTitle(), d.getOriginalName(), text);
        }
        List<KbDocDto> after = jdbcTemplate.query(
                "SELECT * FROM kb_document WHERE id = ?", ROW_MAPPER_WITH_CONTENT, id);
        return after.isEmpty() ? d : after.get(0);
    }

    /**
     * 把一份资料改属到另一个库。
     *
     * <p>自动归类是按扩展名猜的，会猜错（比如一份官方指南存成了 md，其实该算原文）。
     * 改完**必须重新同步检索索引**：分区是随同步下发的，不重推一次，AI 侧还按老分区检索——
     * 界面上已经搬过去了、检索行为却没变，这种「改了没生效」最难查。</p>
     *
     * <p>代价是重推会重新切片并重新向量化整份文档（数秒）。这是管理员偶尔做一次的动作，
     * 与审核通过时的补推是同一条路径，不值得为它另开一条轻量通道。</p>
     */
    public void movePartition(String id, String partition) throws IOException {
        if (!PARTITION_RAW.equals(partition) && !PARTITION_INTEGRATED.equals(partition)) {
            throw new IllegalArgumentException("未知的知识库分区：" + partition);
        }
        int n = jdbcTemplate.update("UPDATE kb_document SET partition = ? WHERE id = ?", partition, id);
        if (n == 0) throw new IllegalStateException("文档不存在");
        List<KbDocDto> cur = jdbcTemplate.query(
                "SELECT * FROM kb_document WHERE id = ?", ROW_MAPPER_WITH_CONTENT, id);
        if (cur.isEmpty()) return;
        KbDocDto d = cur.get(0);
        // 待审核的还没进索引，不必重推——审核通过时会按新分区推
        if (!REVIEW_APPROVED.equals(d.getReviewStatus())) return;
        callPythonDocumentSync(id, d.getTitle(), d.getOriginalName(),
                d.getContent() == null ? "" : d.getContent());
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 文献补录工作台：把攒下的若干<b>片段</b>合成一份整合文档入库（进待审核）。
     *
     * <p>与 {@link #importText} 的关系：那个是「管理员粘贴一段正文」，这里是把多个来源的片段
     * 按管理员排好的顺序渲染成文档，再走同一条 {@code store} 路——切分、抽证据、索引同步、
     * 审核闸门全都一样，不必再写一套。</p>
     *
     * <p><b>一律进待审核</b>（显式传 requireReview）：这是要作为「依据」进回答的内容，
     * 必须有人核对过。审核通过且索引同步成功后，缺口才由 {@code review} 结掉。</p>
     */
    public KbDocDto supply(SupplyRequest req, Long userId) throws IOException {
        Prepared prep = prepare(req);
        SourceInfo src = SourceInfo.forGap(userId, SOURCE_OFFICIAL, "文献补录", null, req.gapId());
        return store(prep.markdown.getBytes(StandardCharsets.UTF_8), sanitize(prep.title) + ".md",
                "text/markdown", prep.title, MetadataExtractor.DEFAULT_CATEGORY,
                null, null, null, null, null, supplyEvidenceJson(req), src, true);
    }

    /** 渲染的中间产物：标题 + Markdown 正文 + Word 版 HTML（预览与入库共用同一份） */
    private record Prepared(String title, String markdown, String html) {}

    /**
     * 数据校验 + 渲染。
     *
     * <p>预览与入库必须走同一段渲染，否则「界面给管理员看的」和「真正进库的」会漂移——
     * 本项目已经因为同一份措辞两处维护吃过亏。审核界面上看到的必须是真会被存进去的那份。</p>
     */
    public Map<String, Object> supplyPreview(SupplyRequest req) {
        Prepared prep = prepare(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", prep.title);
        out.put("markdown", prep.markdown);
        out.put("html", prep.html);
        out.put("count", dataRows(req).size());
        return out;
    }

    private Prepared prepare(SupplyRequest req) {
        List<SupplyDataRow> rows = dataRows(req);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("请至少填写一行带数值的数据");
        }
        List<String> risks = cleanList(req.risks());
        List<String> advice = cleanList(req.advice());
        String ethnicity = req.ethnicity().trim();
        String disease = req.disease().trim();
        String title = SupplyRenderer.titleOf(ethnicity, disease, req.intent(), req.title());
        return new Prepared(title,
                SupplyRenderer.render(ethnicity, disease, req.intent(),
                        req.region(), req.ageRange(), req.sampleSize(), req.year(),
                        rows, risks, advice),
                SupplyRenderer.renderHtml(ethnicity, disease, req.intent(),
                        req.region(), req.ageRange(), req.sampleSize(), req.year(),
                        rows, risks, advice));
    }

    /** 丢掉没有数值的行：管理员习惯先占个位，但空行进了文档就是噪音 */
    private static List<SupplyDataRow> dataRows(SupplyRequest req) {
        if (req.rows() == null) return List.of();
        return req.rows().stream().filter(r -> r != null && r.hasValue()).toList();
    }

    /** 列表项去空白、去重、保序（模型与手工输入都可能出现重复条目） */
    private static List<String> cleanList(List<String> in) {
        if (in == null) return List.of();
        return in.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 把这次补录的结构化数据存进 evidence_json，供日后核对「补了什么、出处写没写」。
     *
     * <p>这个字段同时被补录与 AI 证据抽取两个流程写，所以这里只放自己那几个键、
     * 不冒充证据结构：{@code callPythonEvidencePush} 读的是 {@code fragments} 键，
     * 本 JSON 没有它，会直接跳过——不会把数据行误当成结构化证据推给检索索引。</p>
     */
    private static String supplyEvidenceJson(SupplyRequest req) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("origin", "supply");
            root.put("ethnicity", req.ethnicity());
            root.put("disease", req.disease());
            root.put("intent", req.intent());
            root.put("region", req.region());
            root.put("ageRange", req.ageRange());
            root.put("sampleSize", req.sampleSize());
            root.put("year", req.year());
            root.put("rows", dataRows(req));
            root.put("risks", cleanList(req.risks()));
            root.put("advice", cleanList(req.advice()));
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            // 存证据失败不该挡住入库：正文（即那份整合文档）已经把同样的信息写进去了
            System.err.println("[KbService.supply] evidence_json 生成失败：" + e);
            return null;
        }
    }

    /**
     * 后台处理：LLM 文章总结 → LLM 结构化证据 → 同步检索索引 → 标记完成。
     * 每一步均 best-effort（对应调用方法内部已捕获异常），失败保留启发式结果；
     * 无论结果如何最终都标记为 done，避免前端无限轮询。
     */
    private void processAsync(String id, String title, String cleanText, String originalName,
                              String textWithPages, String initialEvidenceJson,
                              EvidenceExtractor.ExtractedEvidence evidence,
                              boolean needSummarize, boolean needEvidence, boolean searchable) {
        String finalEvidenceJson = initialEvidenceJson;
        try {
            if (needSummarize) {
                updateJob(id, 20, "正在生成文章总结");
                String llmSummary = callPythonSummarize(title, cleanText);
                if (llmSummary != null && !llmSummary.isBlank()) {
                    jdbcTemplate.update(
                            "UPDATE kb_document SET article_summary = ? WHERE id = ?", llmSummary, id);
                }
            }
            if (needEvidence) {
                updateJob(id, 55, "正在提取结构化证据");
                List<EvidenceExtractor.Fragment> llmFrags = callPythonEvidenceExtract(
                        id, title, evidence.ethnicity, evidence.disease, cleanText);
                if (llmFrags != null && !llmFrags.isEmpty()) {
                    evidence.fragments = llmFrags;
                }
                finalEvidenceJson = evidence.toJson();
                jdbcTemplate.update(
                        "UPDATE kb_document SET evidence_json = ? WHERE id = ?", finalEvidenceJson, id);
            }
            // 未通过审核的文档不进检索索引：否则 AI 可能引用到还没审过的用户上传内容。
            // 审核通过时由 review() 补推（见该方法）。
            if (searchable) {
                updateJob(id, 80, "正在同步检索索引");
                callPythonDocumentSync(id, title, originalName, textWithPages);
            } else {
                updateJob(id, 80, "等待审核");
            }
            callPythonEvidencePush(id, title, originalName, finalEvidenceJson);
            jdbcTemplate.update("UPDATE kb_document SET status = 'done' WHERE id = ?", id);
        } catch (Exception ex) {
            System.err.println("[KbService.async] 后台处理失败 id=" + id + "：" + ex);
            try {
                jdbcTemplate.update("UPDATE kb_document SET status = 'done' WHERE id = ?", id);
            } catch (Exception ignored) {
            }
        } finally {
            jobStates.remove(id);
        }
    }

    private void updateJob(String id, int progress, String stage) {
        jobStates.put(id, new JobState(progress, stage));
    }

    /** 文档后台处理状态（供前端轮询进度条；无内存任务则按库内 status 返回 done） */
    public Map<String, Object> status(String id) {
        Map<String, Object> r = new LinkedHashMap<>();
        JobState js = jobStates.get(id);
        if (js != null) {
            r.put("status", "processing");
            r.put("progress", js.progress);
            r.put("stage", js.stage);
            return r;
        }
        List<String> st = jdbcTemplate.query(
                "SELECT status FROM kb_document WHERE id = ?",
                (rs, i) -> rs.getString("status"), id);
        String dbStatus = (st.isEmpty() || st.get(0) == null) ? "done" : st.get(0);
        r.put("status", dbStatus);
        r.put("progress", 100);
        r.put("stage", null);
        return r;
    }

    /**
     * 解析但不落库：抽取文本 + 自动识别元数据 + 结构化证据（含大模型辅助），返回预览供前端确认/编辑。
     * 与 upload 共用抽取逻辑，但不写文件、不写库。
     */
    public KbParsePreview parse(MultipartFile file, String title, String category,
                                String source, String author, Integer publishYear) throws IOException {
        String originalName = file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename();
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) ext = originalName.substring(dot + 1).toLowerCase();
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型：." + (ext.isEmpty() ? "未知" : ext));
        }
        byte[] bytes = file.getBytes();

        TextExtractor.ExtractResult ex = TextExtractor.extract(ext, bytes);
        boolean hasContent = ex.text != null && !ex.text.isBlank();
        String cleanText = cleanForText(ex.text);

        MetadataExtractor.DocMeta meta = MetadataExtractor.extract(cleanText, originalName);

        String safeTitle = (title == null || title.trim().isEmpty())
                ? (meta.title != null ? meta.title : originalName) : title.trim();
        String safeCategory = (category == null || category.trim().isEmpty())
                ? (meta.category != null ? meta.category : MetadataExtractor.DEFAULT_CATEGORY) : category.trim();
        String safeSource = (source == null || source.trim().isEmpty()) ? meta.source : source.trim();
        String safeAuthor = (author == null || author.trim().isEmpty()) ? meta.author : author.trim();
        Integer safeYear = (publishYear == null) ? meta.publishYear : publishYear;

        EvidenceExtractor.ExtractedEvidence evidence = EvidenceExtractor.extract(cleanText, originalName);
        List<EvidenceExtractor.Fragment> llmFrags = callPythonEvidenceExtract(
                "preview-" + System.nanoTime(), safeTitle, evidence.ethnicity, evidence.disease, cleanText);
        if (llmFrags != null && !llmFrags.isEmpty()) {
            evidence.fragments = llmFrags;
        }

        String llmSummary = callPythonSummarize(safeTitle, cleanText);
        // 摘要 = 文档真实/自动识别的摘要（无则不填，绝不捏造）；文章总结 = LLM 概括的主要内容
        String previewSummary = (meta.summary != null && !meta.summary.isBlank()) ? meta.summary : null;
        String previewArticleSummary = (llmSummary != null && !llmSummary.isBlank()) ? llmSummary : null;

        return new KbParsePreview(safeTitle, safeCategory, safeSource, safeAuthor,
                safeYear, previewSummary, previewArticleSummary, evidence.toJson(), ex.pageCount, hasContent);
    }

    /**
     * 文档列表。
     *
     * @param currentUserId 当前登录用户：决定能否看到待审核队列
     * @param sourceLevel   按来源等级筛选（null / 非法值 = 不筛）
     * @param reviewStatus  按审核状态筛选；**仅对官方账号生效**
     */
    public List<KbDocDto> list(Long currentUserId, String sourceLevel, String reviewStatus) {
        StringBuilder sql = new StringBuilder("SELECT * FROM kb_document WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (isOfficial(currentUserId)) {
            String rv = normalizeReview(reviewStatus);
            if (rv != null) {
                sql.append(" AND review_status = ?");
                args.add(rv);
            }
        } else {
            // 普通用户：只看得到已通过的，外加**自己那份待审核的**。
            // 不含后半句的话，用户上传完就石沉大海——列表里找不到，也不知道在等审核。
            sql.append(" AND (review_status = ? OR (review_status = ? AND user_id = ?))");
            args.add(REVIEW_APPROVED);
            args.add(REVIEW_PENDING);
            args.add(currentUserId);
        }
        String lv = normalizeLevel(sourceLevel);
        if (lv != null) {
            sql.append(" AND source_level = ?");
            args.add(lv);
        }
        sql.append(" ORDER BY created_at DESC");

        List<KbDocDto> docs = jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
        for (KbDocDto d : docs) {
            JobState js = jobStates.get(d.getId());
            if (js != null && "processing".equals(d.getStatus())) {
                d.setProgress(js.progress);
                d.setStage(js.stage);
            }
        }
        return docs;
    }

    public KbDocDto get(String id) {
        List<KbDocDto> list = jdbcTemplate.query(
                "SELECT * FROM kb_document WHERE id = ?", ROW_MAPPER_WITH_CONTENT, id);
        if (list.isEmpty()) throw new IllegalStateException("文档不存在");
        KbDocDto d = list.get(0);
        JobState js = jobStates.get(d.getId());
        if (js != null && "processing".equals(d.getStatus())) {
            d.setProgress(js.progress);
            d.setStage(js.stage);
        }
        return d;
    }

    public void delete(String id) throws IOException {
        List<KbDocDto> list = jdbcTemplate.query(
                "SELECT id, stored_name FROM kb_document WHERE id = ?", (rs, i) -> {
                    KbDocDto d = new KbDocDto();
                    d.setId(rs.getString("id"));
                    d.setFileName(rs.getString("stored_name"));
                    return d;
                }, id);
        if (list.isEmpty()) throw new IllegalStateException("文档不存在");
        // 必须在 DELETE 之前读：删完就查不到关联了
        Long gapId = gapIdOf(id);
        Path target = Paths.get(storageDir, list.get(0).getFileName());
        Files.deleteIfExists(target);
        jdbcTemplate.update("DELETE FROM kb_document WHERE id = ?", id);
        // 补录来的文档被删掉时，缺口要退回「待补充」——那份资料已经不在索引里了，
        // 留着一条「已补充」的假记录会让榜单撒谎
        if (gapId != null) gapDao.reopen(gapId);
        // 同步删除 AI 服务检索索引中的切片与动态证据（best-effort）
        callPythonDocumentRemove(id);
    }

    /**
     * 标题唯一化：base 未被占用则原样返回；已存在则追加序号 1、2、3……直到不重复
     * （查全表，含待审核/已驳回——占用过名字的都算，避免后续同名再度撞车）。
     */
    private String uniquifyTitle(String base) {
        Set<String> used = new HashSet<>(
                jdbcTemplate.query("SELECT title FROM kb_document",
                        (rs, i) -> rs.getString("title")));
        if (!used.contains(base)) return base;
        int i = 1;
        while (used.contains(base + i)) i++;
        return base + i;
    }

    public Path filePath(String id) throws IOException {
        List<String> names = jdbcTemplate.query(
                "SELECT stored_name FROM kb_document WHERE id = ?",
                (rs, i) -> rs.getString("stored_name"), id);
        if (names.isEmpty()) throw new IllegalStateException("文档不存在");
        Path p = Paths.get(storageDir, names.get(0));
        if (!Files.exists(p)) throw new IllegalStateException("文件已丢失");
        return p;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 去掉页分隔符，供启发式/LLM 使用（保留换行，避免控制字符干扰行级正则）。 */
    private static String cleanForText(String text) {
        return text == null ? "" : text.replace(String.valueOf(PDF_PAGE_SEP), "\n");
    }

    /** 渲染 PDF 第 page 页为 PNG 字节（带缓存）。非 PDF / 页码越界 / 空白页返回 null。 */
    public byte[] renderPage(String id, int page) throws IOException {
        String key = id + "#" + page;
        byte[] cached = pageCache.get(key);
        if (cached != null) return cached;
        Path p = filePath(id);
        String name = p.getFileName().toString().toLowerCase();
        if (!name.endsWith(".pdf")) return null;
        byte[] docBytes = Files.readAllBytes(p);
        try (PDDocument doc = Loader.loadPDF(docBytes)) {
            if (page < 1 || page > doc.getNumberOfPages()) return null;
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage img = renderer.renderImageWithDPI(page - 1, 120, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            byte[] png = baos.toByteArray();
            if (png.length < 400) return null; // 全空白页
            pageCache.put(key, png);
            return png;
        }
    }

    /** 把整份文本拆成小切片（保留页号），供推送到 AI 服务检索索引。
     *  PDF 按页分隔符切页；每页/每篇再按段落聚合成约 targetLen 字符的切片，
     *  避免把整篇/整页当成一个证据「整篇搬过来」。 */
    private List<Map<String, Object>> splitChunks(String textWithPages) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        if (textWithPages == null || textWithPages.isBlank()) return chunks;
        String[] pages = textWithPages.split(String.valueOf(PDF_PAGE_SEP), -1);
        int pageNo = 1;
        for (String pageText : pages) {
            String pt = pageText.trim();
            if (!pt.isEmpty()) {
                for (String piece : splitByParagraph(pt, 600)) {
                    if (piece.isEmpty()) continue;
                    Map<String, Object> ch = new LinkedHashMap<>();
                    ch.put("index", chunks.size());
                    ch.put("pageNo", pageNo);
                    ch.put("section", null);
                    ch.put("content", piece);
                    chunks.add(ch);
                }
            }
            pageNo++;
        }
        return chunks;
    }

    /** 把文本按换行/段落边界聚合成约 targetLen 字符的切片（不截断句子，尽量按行断）。 */
    private static List<String> splitByParagraph(String text, int targetLen) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String[] lines = text.split("\n");
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) {
                if (cur.length() >= targetLen * 0.6) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                }
                continue;
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(l);
            if (cur.length() >= targetLen) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    /**
     * 推送文档切片到 AI 服务检索索引（RAG_CHUNK），使上传资料可被智能对话检索。
     * best-effort：失败仅记录，不中断上传。
     */
    /** 更新文档的标题 / 分类 / 文章总结 / 结构化证据（管理员审核编辑与修复回填；JSON 体走 Jackson UTF-8 解码，避免 multipart 文本字段乱码） */
    public void updateMeta(String id, String title, String category,
                           String articleSummary, String evidenceJson) {
        boolean titleChanged = false;
        String newTitle = null;
        if (title != null && !title.isBlank()) {
            newTitle = title.trim();
            if (!newTitle.equals(get(id).getTitle())) {
                titleChanged = true;
                newTitle = uniquifyTitle(newTitle);
            }
        }
        if (titleChanged && newTitle != null) {
            // 存储文件名跟随标题同步改名，保持「标题 = 文件名」一致
            try {
                List<String> stored = jdbcTemplate.query(
                        "SELECT stored_name FROM kb_document WHERE id = ?",
                        (rs, i) -> rs.getString("stored_name"), id);
                if (!stored.isEmpty() && stored.get(0) != null) {
                    String ext = get(id).getExt();
                    String newName = id + "_" + sanitize(newTitle + (ext == null || ext.isEmpty() ? "" : "." + ext));
                    Path oldP = Paths.get(storageDir, stored.get(0));
                    Path newP = Paths.get(storageDir, newName);
                    if (Files.exists(oldP)) {
                        Files.move(oldP, newP, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    jdbcTemplate.update("UPDATE kb_document SET stored_name = ? WHERE id = ?", newName, id);
                }
            } catch (IOException ioEx) {
                System.err.println("[updateMeta] 存储文件改名失败 id=" + id + "：" + ioEx);
            }
            jdbcTemplate.update("UPDATE kb_document SET title = ? WHERE id = ?", newTitle, id);
        }
        if (category != null && !category.isBlank()) {
            jdbcTemplate.update("UPDATE kb_document SET category = ? WHERE id = ?", category.trim(), id);
        }
        if (articleSummary != null || evidenceJson != null) {
            String as = articleSummary == null ? "" : articleSummary;
            String ev = evidenceJson == null ? "" : evidenceJson;
            jdbcTemplate.update(
                    "UPDATE kb_document SET article_summary = ?, evidence_json = ? WHERE id = ?",
                    as, ev, id);
        }
        // 标题/证据变更后重推 AI 检索索引，让引用溯源显示新文件名（best-effort，失败仅记录）
        if (titleChanged || (evidenceJson != null && !evidenceJson.isBlank())) {
            try {
                KbDocDto d = get(id);
                callPythonDocumentSync(id, d.getTitle(), d.getOriginalName(),
                        d.getContent() == null ? "" : d.getContent());
                if (d.getEvidenceJson() != null && !d.getEvidenceJson().isBlank()) {
                    callPythonEvidencePush(id, d.getTitle(), d.getOriginalName(), d.getEvidenceJson());
                }
            } catch (Exception e) {
                System.err.println("[updateMeta] 重推索引失败 id=" + id + "：" + e.getMessage());
            }
        } else if (evidenceJson != null && !evidenceJson.isBlank()) {
            try {
                KbDocDto d = get(id);
                callPythonEvidencePush(id, d.getTitle(), d.getOriginalName(), evidenceJson);
            } catch (Exception e) {
                System.err.println("[updateMeta] 重推证据失败 id=" + id + "：" + e.getMessage());
            }
        }
    }

    /**
     * 取文档的来源维度三字段，供推送 AI 索引时附带。
     *
     * <p>单独查这三列而不复用 {@code SELECT *} 的 ROW_MAPPER：kb_document 带 TEXT 全文列，
     * 为了三个短字段把整篇正文再拉回来不划算。</p>
     */
    private Map<String, Object> sourceMetaOf(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        // RowCallbackHandler（void）而不是 RowMapper：这里只为取值，不需要映射成对象
        jdbcTemplate.query(
                "SELECT source_level, source_org, source_url, partition FROM kb_document WHERE id = ?",
                rs -> {
                    out.put("sourceLevel", rs.getString("source_level"));
                    out.put("sourceOrg", rs.getString("source_org"));
                    out.put("sourceUrl", rs.getString("source_url"));
                    // 分区随同步一起下发：AI 侧据此决定检索时谁优先。
                    // 老数据可能还没归类（列刚加），这里兜一个默认值，别让它变成 None。
                    String p = rs.getString("partition");
                    out.put("partition", p == null || p.isBlank() ? PARTITION_INTEGRATED : p);
                }, id);
        return out;
    }

    /**
     * 调 AI 服务抓取网页正文并清洗。
     *
     * <p>与其它 {@code callPython*} 不同，这个方法**不吞异常**：抓取失败的原因
     * （目标站 403、需要登录、跳转到白名单外）必须原样告诉管理员，
     * 否则界面上只剩「导入失败」四个字，没法排查。</p>
     */
    private Map<String, Object> callPythonFetchUrl(String url) {
        HttpResponse<String> resp;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("url", url);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(AI_BASE_URL + "/api/ai/kb/fetch-url"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            resp = AI_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("抓取被中断");
        } catch (IOException e) {
            throw new IllegalStateException("连不上抓取服务——AI 服务（:8000）是否在运行？");
        }

        if (resp.statusCode() != 200) {
            throw new IllegalStateException("抓取服务返回 " + resp.statusCode());
        }
        try {
            JsonNode node = JSON.readTree(resp.body());
            if (node == null || !node.path("ok").asBoolean(false)) {
                String err = node == null ? "" : node.path("error").asText("");
                throw new IllegalStateException(err.isBlank() ? "抓取失败" : err);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", node.path("text").asText(""));
            out.put("title", node.path("title").asText(""));
            out.put("finalUrl", node.path("finalUrl").asText(""));
            out.put("publishedAt", node.path("publishedAt").asText(""));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("抓取服务返回了无法解析的内容");
        }
    }

    /**
     * 推送文档切片到 AI 服务建检索索引。
     *
     * @return <b>真的进了索引</b>才为 true。改成返回布尔值而不是继续 fire-and-forget，
     *         是因为「文献补录」要靠它判断缺口算不算补上了：推送失败（AI 服务没起、
     *         扫描件没抽出文字、切片全被判为非中文而丢弃）时若照样标「已补充」，
     *         缺口会从榜上消失、而用户再检索**依然未命中** —— 那比不标更糟，等于把问题藏起来。
     */
    private boolean callPythonDocumentSync(String id, String title, String fileName, String textWithPages) {
        try {
            List<Map<String, Object>> chunks = splitChunks(textWithPages);
            // 没有可索引的切片（扫描件、或正文过短）。这不是异常，但确实没进索引。
            if (chunks.isEmpty()) {
                System.out.println("[KbService.documentSync] 无可索引切片，未入索引 id=" + id);
                return false;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("documentId", Math.abs(id.hashCode()));
            body.put("title", title == null ? "" : title);
            body.put("fileName", fileName);
            body.put("chunks", chunks);
            // 同步全文供 Python 侧「左全文·右证据」浏览模式使用
            body.put("fullText", cleanForText(textWithPages));
            // 来源维度：从库里读，不靠调用方一路传下来——调用点有两处
            // （上传后的后台任务、审核通过时的补推），从库取能保证两处一致，
            // 也免得再往方法签名上加三个位置参数。
            body.putAll(sourceMetaOf(id));
            String reqJson = JSON.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(AI_BASE_URL + "/api/ai/kb/documents"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(reqJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = AI_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("[KbService.documentSync] 非 200：status=" + resp.statusCode()
                        + " body=" + resp.body());
                return false;
            }
            // 只看 HTTP 状态不够：AI 侧会把不含中文的切片整批丢掉，那时仍回 {"ok":true,"added":0}。
            // 以 added（真正写进去的切片数）为准。
            int added = JSON.readTree(resp.body()).path("added").asInt(0);
            if (added <= 0) {
                System.err.println("[KbService.documentSync] 未写入任何切片 added=0 body=" + resp.body());
                return false;
            }
            System.out.println("[KbService.documentSync] ok id=" + id + " added=" + added);
            return true;
        } catch (Exception ex) {
            System.err.println("[KbService.documentSync] EXCEPTION: " + ex);
            return false;
        }
    }

    /**
     * 推送文档的 AI/启发式结构化证据到 AI 服务检索索引（KB_EVIDENCE，动态）。
     * best-effort：失败仅记录，不中断上传。
     */
    private void callPythonEvidencePush(String id, String title, String fileName, String evidenceJson) {
        try {
            if (evidenceJson == null || evidenceJson.trim().isEmpty()) return;
            JsonNode root = JSON.readTree(evidenceJson);
            JsonNode frags = root.get("fragments");
            if (frags == null || !frags.isArray()) return;
            List<Map<String, Object>> evs = new ArrayList<>();
            for (JsonNode f : frags) {
                String content = str(f.get("content"));
                if (content == null || content.isBlank()) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("topic", str(f.get("topic")));
                e.put("content", content);
                e.put("pageNo", null);
                e.put("section", null);
                e.put("ethnicity", str(f.get("ethnicity")));
                e.put("disease", str(f.get("disease")));
                evs.add(e);
            }
            if (evs.isEmpty()) return;
            Map<String, Object> paper = new LinkedHashMap<>();
            paper.put("doc_id", Math.abs(id.hashCode()));
            paper.put("title", title == null ? "" : title);
            paper.put("fileName", fileName);
            paper.put("ethnicity", str(root.get("ethnicity")));
            paper.put("disease", str(root.get("disease")));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("paper", paper);
            body.put("evidences", evs);
            String reqJson = JSON.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(AI_BASE_URL + "/api/ai/kb/evidence"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(reqJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = AI_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[KbService.evidencePush] status=" + resp.statusCode()
                    + " body=" + (resp.body() == null ? "null" : resp.body()));
        } catch (Exception ex) {
            System.err.println("[KbService.evidencePush] EXCEPTION: " + ex);
        }
    }

    /** 删除文档时移除其在 AI 服务检索索引中的切片与动态证据（best-effort）。 */
    private void callPythonDocumentRemove(String id) {
        int docId = Math.abs(id.hashCode());
        try {
            deleteAi(URI.create(AI_BASE_URL + "/api/ai/kb/documents/" + docId));
        } catch (Exception ignored) {}
        try {
            deleteAi(URI.create(AI_BASE_URL + "/api/ai/kb/evidence/document/" + docId));
        } catch (Exception ignored) {}
    }

    private void deleteAi(URI uri) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(30))
                .DELETE()
                .build();
        HttpResponse<String> resp = AI_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[KbService.aiDelete] " + uri + " status=" + resp.statusCode());
    }

    /**
     * 调用 Python AI 服务的大模型证据提取端点（/api/ai/kb/evidence/extract）。
     * best-effort：任何异常（网络不可达 / 未配置 LLM / 返回空）均返回 null，由调用方降级为 Java 启发式结果。
     */
    private List<EvidenceExtractor.Fragment> callPythonEvidenceExtract(
            String docId, String title, String ethnicity, String disease, String text) {
        if (text == null || text.isBlank()) return null;
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("index", 0);
            chunk.put("pageNo", null);
            chunk.put("section", null);
            chunk.put("content", text);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("documentId", Math.abs(docId.hashCode()));
            body.put("title", title);
            body.put("fileName", null);
            body.put("ethnicity", ethnicity);
            body.put("disease", disease);
            body.put("chunks", Collections.singletonList(chunk));
            String reqJson = JSON.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(AI_BASE_URL + "/api/ai/kb/evidence/extract"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(AI_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(reqJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = AI_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) return null;

            JsonNode root = JSON.readTree(resp.body());
            JsonNode evs = root.get("evidences");
            if (evs == null || !evs.isArray()) return null;
            List<EvidenceExtractor.Fragment> out = new ArrayList<>();
            for (JsonNode e : evs) {
                String topic = str(e.get("topic"));
                String content = str(e.get("content"));
                if (content == null || content.isBlank()) continue;
                out.add(new EvidenceExtractor.Fragment(topic, content, str(e.get("ethnicity")), str(e.get("disease"))));
            }
            return out.isEmpty() ? null : out;
        } catch (Exception ex) {
            return null; // 调用失败：保留 Java 启发式结果
        }
    }

    private static String str(JsonNode n) {
        if (n == null || n.isNull()) return null;
        return n.asText();
    }

    /**
     * 调用 Python AI 服务的大模型全文摘要端点（/api/ai/kb/summarize）。
     * best-effort：任何异常（网络不可达 / 未配置 LLM / 返回空）均返回 null，由调用方降级为启发式摘要。
     */
    private String callPythonSummarize(String title, String text) {
        if (text == null || text.isBlank()) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", title == null ? "" : title);
            body.put("text", text);
            String reqJson = JSON.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(AI_BASE_URL + "/api/ai/kb/summarize"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(AI_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(reqJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = AI_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) {
                System.err.println("[KbService.summarize] status=" + resp.statusCode()
                        + " body=" + (resp.body() == null ? "null" : resp.body()));
                return null;
            }

            JsonNode root = JSON.readTree(resp.body());
            String summary = str(root.get("summary"));
            return (summary == null || summary.isBlank()) ? null : summary.trim();
        } catch (Exception ex) {
            System.err.println("[KbService.summarize] EXCEPTION: " + ex);
            return null; // 调用失败：保留启发式摘要
        }
    }
}
