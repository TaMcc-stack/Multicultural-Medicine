package com.backend.kb;

/**
 * 知识库文档 DTO（与前端 KbDoc / KbDocDetail 字段对齐）。
 * Jackson 按 getter 名称序列化：getFileName() -> fileName 等。
 */
public class KbDocDto {

    private String id;
    private String title;
    private String category;
    private String fileName;
    private String originalName;
    private long size;
    private String ext;
    private String mime;
    private long uploadedAt;      // 毫秒时间戳
    private Integer pageCount;    // 仅 PDF 有值，其余为 null
    private boolean hasContent;   // 是否已提取全文
    private String content;       // 全文（列表接口为 null，详情接口有值）
    /** 自动识别的作者（可为空） */
    private String author;
    /** 自动识别的出处/期刊/出版社（可为空） */
    private String source;
    /** 自动识别的发表年份（可为空） */
    private Integer publishYear;
    /** 自动生成的摘要/概要（可为空） */
    private String summary;
    /** 文章总结（AI 概括主要内容，可编辑，可为空） */
    private String articleSummary;
    /** 结构化证据片段 JSON（出处/作者/民族疾病/研究类型/研究人群/结论/局限性/证据片段） */
    private String evidenceJson;
    /** 后台处理状态：processing（解析中）/ done（完成）/ error（失败） */
    private String status = "done";
    /** 后台处理进度 0-100（处理中时有效） */
    private int progress = 100;
    /** 后台处理阶段文案（如「正在生成文章总结」，处理中时有效） */
    private String stage;

    // ── 来源维度（一级分类）────────────────────────────────────────
    /** 来源等级：official（官方上传）/ web_crawl（网页抓取）/ user_upload（用户上传） */
    private String sourceLevel = "official";
    /**
     * 来源机构：国家卫健委 / 人民日报 / 用户上传 等。
     *
     * <p>与上面的 {@link #source} 是**两条不同的轴**：那条记「资料本身出自哪里」（期刊/出版社，
     * 由正文识别）；这条记「这份资料是怎么进来的」（渠道）。命名上刻意错开，避免混淆。</p>
     */
    private String sourceOrg;
    /** 原始链接（网页抓取时记录，供溯源与去重） */
    private String sourceUrl;
    /** 审核状态：approved（通过）/ pending（待审核）/ rejected（已驳回） */
    private String reviewStatus = "approved";
    /** 上传者用户 ID；鉴权修复之前入库的历史数据为 null */
    private Long userId;

    /**
     * 知识库分区：integrated（整合资料库，优先检索）/ raw（原始文献库，降级检索）。
     *
     * <p>与上面的 {@link #sourceLevel} 是**两条正交的轴**：那条说「可不可信」，
     * 这条说「是原文还是提炼过的」。入库时按扩展名判定（见
     * {@code KbService.partitionOf}），管理员可在知识库页面上手工改。</p>
     */
    private String partition = "integrated";

    public String getPartition() {
        return partition;
    }

    public void setPartition(String partition) {
        this.partition = partition;
    }

    public KbDocDto() {
    }

    public KbDocDto(String id, String title, String category, String fileName,
                   String originalName, long size, String ext, String mime,
                   long uploadedAt, Integer pageCount, boolean hasContent, String content) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.fileName = fileName;
        this.originalName = originalName;
        this.size = size;
        this.ext = ext;
        this.mime = mime;
        this.uploadedAt = uploadedAt;
        this.pageCount = pageCount;
        this.hasContent = hasContent;
        this.content = content;
    }

    public KbDocDto(String id, String title, String category, String fileName,
                   String originalName, long size, String ext, String mime,
                   long uploadedAt, Integer pageCount, boolean hasContent, String content,
                   String author, String source, Integer publishYear, String summary,
                   String articleSummary, String evidenceJson) {
        this(id, title, category, fileName, originalName, size, ext, mime,
                uploadedAt, pageCount, hasContent, content);
        this.author = author;
        this.source = source;
        this.publishYear = publishYear;
        this.summary = summary;
        this.articleSummary = articleSummary;
        this.evidenceJson = evidenceJson;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getExt() { return ext; }
    public void setExt(String ext) { this.ext = ext; }

    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }

    public long getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(long uploadedAt) { this.uploadedAt = uploadedAt; }

    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }

    public boolean getHasContent() { return hasContent; }
    public void setHasContent(boolean hasContent) { this.hasContent = hasContent; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Integer getPublishYear() { return publishYear; }
    public void setPublishYear(Integer publishYear) { this.publishYear = publishYear; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getArticleSummary() { return articleSummary; }
    public void setArticleSummary(String articleSummary) { this.articleSummary = articleSummary; }

    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getSourceLevel() { return sourceLevel; }
    public void setSourceLevel(String sourceLevel) { this.sourceLevel = sourceLevel; }

    public String getSourceOrg() { return sourceOrg; }
    public void setSourceOrg(String sourceOrg) { this.sourceOrg = sourceOrg; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}
