package com.backend.kb;

/**
 * 解析预览结果（仅抽取、不落库，供前端展示与用户确认/编辑）。
 */
public class KbParsePreview {
    public String title;
    public String category;
    public String source;
    public String author;
    public Integer publishYear;
    public String summary;
    public String articleSummary;
    public String evidenceJson;
    public Integer pageCount;
    public boolean hasContent;

    public KbParsePreview(String title, String category, String source, String author,
                          Integer publishYear, String summary, String articleSummary, String evidenceJson,
                          Integer pageCount, boolean hasContent) {
        this.title = title;
        this.category = category;
        this.source = source;
        this.author = author;
        this.publishYear = publishYear;
        this.summary = summary;
        this.articleSummary = articleSummary;
        this.evidenceJson = evidenceJson;
        this.pageCount = pageCount;
        this.hasContent = hasContent;
    }
}
