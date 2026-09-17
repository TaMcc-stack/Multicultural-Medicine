package com.backend.kb;

import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文献元数据自动识别：从已提取的「全文」与文件名中推断
 * 标题、作者、出处（期刊/出版社）、发表年份、所属类别与摘要。
 *
 * <p>采用纯启发式规则（正则 + 关键词），不依赖外部 NLP 服务。
 * 识别不到的字段返回 null，前端按“有就显示，没有就不写”处理。</p>
 *
 * <p><b>提取范围：</b>对整篇文档全文进行扫描，而非仅头部若干行，
 * 以最大限度从全部内容中识别元数据（作者/出处可能出现在文末、
 * 关键词可能散布全文、类别需统计全文词频）。</p>
 */
public final class MetadataExtractor {

    private MetadataExtractor() {
    }

    /** 自动识别出的文献元数据；未能识别的字段为 null */
    public static final class DocMeta {
        public final String title;
        public final String author;
        public final String source;
        public final Integer publishYear;
        public final String category;
        public final String summary;

        DocMeta(String title, String author, String source, Integer publishYear,
                String category, String summary) {
            this.title = title;
            this.author = author;
            this.source = source;
            this.publishYear = publishYear;
            this.category = category;
            this.summary = summary;
        }
    }

    /** 默认归类：任何关键词都没命中时使用 */
    public static final String DEFAULT_CATEGORY = "其他";

    // ---------------- 类别关键词表 ----------------
    private static final Map<String, List<String>> CATEGORY_KEYWORDS = new LinkedHashMap<>();
    static {
        CATEGORY_KEYWORDS.put("民族疾病类", Arrays.asList(
                "疾病", "病症", "糖尿病", "高血压", "临床", "病例", "疗效", "诊断", "患病",
                "发病率", "并发症", "症状", "治疗", "观察", "综合征", "感染",
                "disease", "diabetes", "clinical", "patient", "syndrome"));
        CATEGORY_KEYWORDS.put("药物方剂类", Arrays.asList(
                "药物", "方剂", "处方", "草药", "藏药", "蒙药", "维药", "苗药", "彝药", "傣药",
                "药材", "汤剂", "丸", "散", "膏", "用药", "本草", "药理", "成分",
                "herb", "formula", "prescription", "medicine", "pharmacol"));
        CATEGORY_KEYWORDS.put("诊疗技术类", Arrays.asList(
                "针灸", "拔罐", "推拿", "放血", "手法", "诊法", "脉诊", "舌诊", "灸法", "针法",
                "疗法", "技术", "操作", "外治", "正骨",
                "acupuncture", "moxibustion", "massage", "therapy", "technique"));
        CATEGORY_KEYWORDS.put("养生保健类", Arrays.asList(
                "养生", "保健", "食疗", "康复", "预防", "体质", "四季", "起居", "延年",
                "营养", "调理", "治未病",
                "health", "wellness", "rehabilitation", "prevent", "nutrition"));
        CATEGORY_KEYWORDS.put("医学理论类", Arrays.asList(
                "理论", "学说", "体系", "经典", "文献", "综述", "历史", "文化", "哲学",
                "基础理论", "源流", "考证",
                "theory", "classic", "review", "history", "culture"));
    }

    // ---------------- 正则 ----------------
    private static final Pattern AUTHOR_PATTERNS = Pattern.compile(
            "(?:作\\s*者|著\\s*者|编\\s*著|主\\s*编|作者姓名|Author[s]?)\\s*[:：]?\\s*([^\\n\\r]{1,80})");
    /** 姓名 + 著/编 形式（无显式标签时的兜底） */
    private static final Pattern AUTHOR_SUFFIX = Pattern.compile(
            "^([\\u4e00-\\u9fa5·]{2,24}(?:[、,，;；\\s]+[\\u4e00-\\u9fa5·]{2,24})*)\\s*(?:著|编|主编|编著)");
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "(?:题\\s*名|标\\s*题|篇\\s*名|Title)\\s*[:：]\\s*([^\\n\\r]{1,160})");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)");
    private static final Pattern SOURCE_LINE = Pattern.compile(
            "[^\\n\\r]*(?:杂志|学报|期刊|出版社|论文集|会议|大学|医院|研究所|年鉴|"
                    + "Journal|Conference|Proceedings|University|Press|Transactions)[^\\n\\r]*");
    private static final Pattern BOOK_TITLE = Pattern.compile("《([^》]{2,80})》");
    /** 明显不是标题的行 */
    private static final Pattern NOT_TITLE = Pattern.compile(
            "^(?:摘\\s*要|Abstract|关键词|Key\\s*words?|中图分类号|文献标识码|文章编号|"
                    + "doi|DOI|收稿日期|基金项目|通信作者|通讯作者|引用本文)",
            Pattern.CASE_INSENSITIVE);

    private static final int SUMMARY_MAX = 300;

    /**
     * 执行元数据识别。对「整篇全文」进行扫描，从全部内容中提取元数据。
     *
     * @param text         已提取的全文（可为 null/空）
     * @param originalName 原始文件名（用于兜底标题）
     * @return 识别结果（各字段可能为 null）
     */
    public static DocMeta extract(String text, String originalName) {
        List<String> lines = splitLines(text);
        String body = text == null ? "" : text;

        // 整篇扫描：不再局限于前 40 行，作者/出处/年份/类别/摘要均从全部内容识别。
        // 顺序：先标题（通常有显式标签），其余字段相互无依赖。
        String title = detectTitle(lines, originalName);
        String author = detectAuthor(lines);
        String source = detectSource(lines);
        Integer year = detectYear(lines);
        String category = detectCategory(title, body);
        String summary = detectSummary(lines, body);

        return new DocMeta(title, author, source, year, category, summary);
    }

    // ---------------- 各字段识别 ----------------

    /** 在整篇内容中定位作者：先按“作者/著者”标签，再按“姓名+著/编”后缀。 */
    private static String detectAuthor(List<String> lines) {
        for (String line : lines) {
            Matcher m = AUTHOR_PATTERNS.matcher(line);
            if (m.find()) {
                String v = cleanValue(m.group(1));
                if (isPlausibleAuthor(v)) return v;
            }
        }
        for (String line : lines) {
            Matcher m = AUTHOR_SUFFIX.matcher(line);
            if (m.find()) {
                String v = cleanValue(m.group(1));
                if (isPlausibleAuthor(v)) return v;
            }
        }
        return null;
    }

    private static boolean isPlausibleAuthor(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.length() < 2 || s.length() > 80) return false;
        // 排除把整句当作者
        return !s.matches(".*[。；;.!！?？]{2,}.*");
    }

    /** 在整篇内容中定位出处：期刊/出版社/会议等行，或《》书名/刊名。 */
    private static String detectSource(List<String> lines) {
        List<String> bookFallback = new ArrayList<>();
        for (String line : lines) {
            if (NOT_TITLE.matcher(line).find()) continue;
            Matcher m = SOURCE_LINE.matcher(line);
            if (m.find()) {
                String v = stripSourceLabel(cleanValue(m.group(0)));
                if (v != null && v.length() >= 3) return truncate(v, 120);
            }
            Matcher bt = BOOK_TITLE.matcher(line);
            if (bt.find()) {
                String t = stripSourceLabel(cleanValue(bt.group(1)));
                if (t != null) bookFallback.add(truncate(t, 120));
            }
        }
        // 兜底：《》书名/刊名（取首个）
        if (!bookFallback.isEmpty()) return bookFallback.get(0);
        return null;
    }

    /** 去掉出处行首的“出处/来源/刊名”等标签，仅保留机构名 */
    private static String stripSourceLabel(String v) {
        if (v == null) return null;
        return v.replaceFirst("^(出处|来源|刊名|载于|见|引自|发表于)\\s*[:：]?", "").trim();
    }

    /** 在整篇内容中定位发表年份：优先首个合理年份（通常接近正文，越靠前越可信）。 */
    private static Integer detectYear(List<String> lines) {
        int maxYear = Year.now().getValue() + 1;
        for (String line : lines) {
            Matcher m = YEAR_PATTERN.matcher(line);
            if (m.find()) {
                int y = Integer.parseInt(m.group(1));
                if (y >= 1900 && y <= maxYear) return y;
            }
        }
        return null;
    }

    /** 在整篇内容中定位标题：先按显式标签，再取首个“像标题”的行，最后文件名兜底。 */
    private static String detectTitle(List<String> lines, String originalName) {
        // 1) 显式标题标签（整篇扫描）
        for (String line : lines) {
            Matcher m = TITLE_PATTERN.matcher(line);
            if (m.find()) {
                String v = cleanValue(m.group(1));
                if (v != null && v.length() >= 2) return truncate(v, 160);
            }
        }
        // 2) 首个“像标题”的行（排除摘要/作者/出处等元信息行）
        for (String line : lines) {
            if (NOT_TITLE.matcher(line).find()) continue;
            if (AUTHOR_PATTERNS.matcher(line).find()) continue;
            if (SOURCE_LINE.matcher(line).find()) continue;
            int len = line.length();
            if (len < 4 || len > 120) continue;
            // 标题通常不含句末句号
            if (line.endsWith("。") || line.endsWith(".")) continue;
            return truncate(line, 160);
        }
        // 3) 文件名兜底
        return titleFromFileName(originalName);
    }

    private static String titleFromFileName(String originalName) {
        if (originalName == null) return null;
        String n = originalName;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("[_\\[\\]()（）]+", " ").replaceAll("\\s+", " ").trim();
        // 去掉尾部的年份/随机串
        n = n.replaceAll("[-\\s]+(19|20)\\d{2}$", "").trim();
        return n.isEmpty() ? null : truncate(n, 160);
    }

    /**
     * 类别识别：在「整篇全文」中统计各类别关键词词频（标题命中权重更高）。
     * 不再截断正文，确保长文档也能基于全部内容归类。
     */
    private static String detectCategory(String title, String body) {
        String titleLc = title == null ? "" : title.toLowerCase();
        String bodyLc = body == null ? "" : body.toLowerCase();

        String best = DEFAULT_CATEGORY;
        int bestScore = 0;
        for (Map.Entry<String, List<String>> e : CATEGORY_KEYWORDS.entrySet()) {
            int score = 0;
            for (String kw : e.getValue()) {
                String k = kw.toLowerCase();
                if (!k.isEmpty() && titleLc.contains(k)) score += 3; // 标题命中权重更高
                score += countOccurrences(bodyLc, k);
            }
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        return best;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 在整篇内容中定位摘要：优先取“摘要/Abstract”块，兜底取首个较长段落。 */
    private static String detectSummary(List<String> lines, String body) {
        int n = lines.size();
        // 1) 优先“摘要/Abstract”之后的内容（整篇扫描）
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            if (line.matches("^(摘\\s*要|Abstract|ABSTRACT)[:：]?.*")) {
                String rest = line.replaceFirst("^(摘\\s*要|Abstract|ABSTRACT)\\s*[:：]?\\s*", "");
                StringBuilder sb = new StringBuilder(rest);
                for (int j = i + 1; j < n && sb.length() < SUMMARY_MAX; j++) {
                    String nxt = lines.get(j);
                    if (NOT_TITLE.matcher(nxt).find() && !nxt.startsWith("摘")) break;
                    sb.append(nxt);
                }
                String s = cleanValue(sb.toString());
                if (s != null && s.length() >= 10) return truncate(s, SUMMARY_MAX);
            }
        }
        // 2) 兜底：不再从“首个较长段落/正文”凭空生成摘要——无自带“摘要/Abstract”块时返回 null，
        //    避免把表格行头/正文片段误当成文献摘要（数据资料类文档通常无摘要，此处应留空）。
        return null;
    }

    // ---------------- 工具 ----------------

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String raw : text.split("\\R")) {
            String t = raw.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 去掉行首行尾的分隔符、括号内容与多余空白 */
    private static String cleanValue(String v) {
        if (v == null) return null;
        String s = v.trim();
        s = s.replaceAll("^[\\s:：,，、;；.。]+", "");
        s = s.replaceAll("[\\s,，、;；]+$", "");
        s = s.replaceAll("\\s*\\([^)]*\\)\\s*", " ");
        s = s.replaceAll("\\s*（[^）]*）\\s*", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
