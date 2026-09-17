package com.backend.kb;

import java.util.List;
import java.util.Map;

/**
 * 把工作台上核对好的结构化数据渲染成一份 Markdown（以及 Word 能直接打开的 HTML）文档。
 *
 * <p>四个刻意的设计：</p>
 * <ol>
 *   <li><b>固定模板：数据表 + 两份列表</b>。之前的版本把粘贴的论文原文原样排进文档，
 *       读者看到的是一堵文字墙——分不清哪条是患病率、哪条是危险因素。现在按读者要回答的
 *       问题来分节，数据进表格、因素与建议进列表。</li>
 *   <li><b>表格里逐行带来源文献</b>。整合资料的价值就是每条数据都能追到具体文献；
 *       把出处集中放文末，切片检索时很容易与正文分离（按约 600 字切），那就等于没写。</li>
 *   <li><b>数值原样照抄</b>。渲染层一旦改写一个字（哪怕修标点、换算单位），溯源链就断了。
 *       出错了由管理员回去改数据行，不在这里偷偷「优化」。</li>
 *   <li><b>标题写明「民族 + 疾病 + 方面」</b>。这三个词同时出现在标题与正文里，
 *       检索时用任何一个词都能锚到这份文档。</li>
 * </ol>
 */
final class SupplyRenderer {

    private SupplyRenderer() {
    }

    /**
     * 意图码 → 中文「方面」名。与前端 {@code INTENT_LABELS} 一致。
     *
     * <p>之所以在后端也留一份：文档标题是入库后才生成的（服务端渲染才是唯一真相），
     * 不能指望调用方每次都传 title。渲染口自己拼，拼出来的标题才稳定。</p>
     */
    private static final Map<String, String> ASPECTS = Map.of(
            "prevalence", "患病情况",
            "risk", "危险因素",
            "prevention", "预防与筛查",
            "diet", "饮食与生活方式",
            "symptoms", "症状与早期信号",
            "medication", "用药注意事项",
            "treatment", "治疗与干预方向",
            "burden", "疾病负担与严重程度",
            "genetics", "遗传相关研究",
            "overview", "研究概况");

    /** 意图码转「方面」中文名；不认识的码原样返回（宁可长得怪，也不要丢信息） */
    static String aspectOf(String intent) {
        if (intent == null || intent.isBlank()) return "相关";
        return ASPECTS.getOrDefault(intent.trim(), intent.trim());
    }

    /** 文档标题：「白族 + 糖尿病 + 患病情况」→「白族糖尿病患病情况 资料整合」 */
    static String titleOf(String ethnicity, String disease, String intent, String provided) {
        if (provided != null && !provided.isBlank()) return provided.trim();
        String aspect = aspectOf(intent);
        return ethnicity.trim() + disease.trim() + ("相关".equals(aspect) ? "" : aspect) + " 资料整合";
    }

    /**
     * 第一节的小标题。
     *
     * <p>补录「患病情况」时写「患病率数据」——那个词本身就是检索该方面时的锚点词，
     * 写在标题里能帮上忙；其他方面（遗传、用药…）写「患病率数据」就不对了，用中性的说法。</p>
     */
    private static String dataSectionTitle(String intent) {
        return "prevalence".equals(intent == null ? "" : intent.trim()) ? "患病率数据" : "核心数据";
    }

    /** 表格单元格：竖线会撑破表格结构，换行会撑破行；空值写成「—」而不是留白 */
    private static String cell(String v) {
        if (v == null || v.isBlank()) return "—";
        return v.trim().replace("|", "\\|").replace("\n", " ");
    }

    static String render(String ethnicity, String disease, String intent,
                         String region, String ageRange, String sampleSize, String year,
                         List<SupplyDataRow> rows, List<String> risks, List<String> advice) {
        String aspect = aspectOf(intent);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(ethnicity.trim()).append(disease.trim()).append(aspect)
                .append(" 资料整合\n\n");

        // 研究信息（适用范围）：回答据此说明「该数据适用于哪个地区、什么年龄段」，是证据里的硬元数据。
        sb.append("## 研究信息（适用范围）\n\n");
        sb.append("- **研究地区**：").append(cell(region)).append('\n');
        sb.append("- **年龄范围**：").append(cell(ageRange)).append('\n');
        sb.append("- **样本量**：").append(cell(sampleSize)).append('\n');
        sb.append("- **研究年份**：").append(cell(year)).append("\n\n");

        sb.append("## 一、").append(dataSectionTitle(intent)).append("\n\n");
        if (rows.isEmpty()) {
            sb.append("（本次补录未填写数据行）\n\n");
        } else {
            sb.append("| 民族 | 研究地区 | 年龄范围 | 样本量 | 指标类型 | 数值 | 来源文献 |\n");
            sb.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
            for (SupplyDataRow r : rows) {
                sb.append("| ").append(cell(r.eth(ethnicity)))
                        .append(" | ").append(cell(r.region()))
                        .append(" | ").append(cell(r.ageRange()))
                        .append(" | ").append(cell(r.sampleSize()))
                        .append(" | ").append(cell(r.metric()))
                        .append(" | ").append(cell(r.value()))
                        .append(" | ").append(cell(r.source()))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 二、危险因素\n\n");
        appendList(sb, risks, false);
        sb.append("## 三、专家建议\n\n");
        appendList(sb, advice, true);
        return sb.toString();
    }

    /**
     * 列表渲染：危险因素用无序列表，专家建议用有序列表（读者按顺序读的是一套做法）。
     * 空列表不静默跳过——写明「原文未提及」，读者才知道这一节不是漏掉了。
     */
    private static void appendList(StringBuilder sb, List<String> items, boolean ordered) {
        if (items == null || items.isEmpty()) {
            sb.append("（原文未提及）\n\n");
            return;
        }
        int i = 0;
        for (String s : items) {
            i++;
            if (ordered) sb.append(i).append(". ");
            else sb.append("- ");
            sb.append(s.trim()).append('\n');
        }
        sb.append('\n');
    }

    /**
     * Word 版（HTML 套壳）。
     *
     * <p>管理员拿到的是「可以直接发出去给人看」的文件：Markdown 给技术同事看，Word 给不看
     * Markdown 的临床专家看。两者内容必须逐字一致，所以共用同一份结构化数据。</p>
     */
    static String renderHtml(String ethnicity, String disease, String intent,
                             String region, String ageRange, String sampleSize, String year,
                             List<SupplyDataRow> rows, List<String> risks, List<String> advice) {
        String aspect = aspectOf(intent);
        StringBuilder sb = new StringBuilder();
        sb.append("<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" ")
                .append("xmlns:w=\"urn:schemas-microsoft-com:office:word\">")
                .append("<head><meta charset=\"utf-8\"></head><body>");
        sb.append("<h1>").append(esc(ethnicity.trim() + disease.trim() + aspect))
                .append(" 资料整合</h1>");

        sb.append("<h2>研究信息（适用范围）</h2>");
        sb.append("<ul>")
                .append("<li><b>研究地区</b>：").append(esc(region == null || region.isBlank() ? "—" : region.trim())).append("</li>")
                .append("<li><b>年龄范围</b>：").append(esc(ageRange == null || ageRange.isBlank() ? "—" : ageRange.trim())).append("</li>")
                .append("<li><b>样本量</b>：").append(esc(sampleSize == null || sampleSize.isBlank() ? "—" : sampleSize.trim())).append("</li>")
                .append("<li><b>研究年份</b>：").append(esc(year == null || year.isBlank() ? "—" : year.trim())).append("</li>")
                .append("</ul>");

        sb.append("<h2>一、").append(esc(dataSectionTitle(intent))).append("</h2>");
        if (rows.isEmpty()) {
            sb.append("<p>（本次补录未填写数据行）</p>");
        } else {
            sb.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
                    .append("<tr><th>民族</th><th>研究地区</th><th>年龄范围</th><th>样本量</th>")
                    .append("<th>指标类型</th><th>数值</th><th>来源文献</th></tr>");
            for (SupplyDataRow r : rows) {
                sb.append("<tr>")
                        .append(td(r.eth(ethnicity))).append(td(r.region()))
                        .append(td(r.ageRange())).append(td(r.sampleSize()))
                        .append(td(r.metric())).append(td(r.value()))
                        .append(td(r.source()))
                        .append("</tr>");
            }
            sb.append("</table>");
        }

        sb.append("<h2>二、危险因素</h2>");
        appendHtmlList(sb, risks, false);
        sb.append("<h2>三、专家建议</h2>");
        appendHtmlList(sb, advice, true);
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void appendHtmlList(StringBuilder sb, List<String> items, boolean ordered) {
        if (items == null || items.isEmpty()) {
            sb.append("<p>（原文未提及）</p>");
            return;
        }
        String tag = ordered ? "ol" : "ul";
        sb.append('<').append(tag).append('>');
        for (String s : items) {
            sb.append("<li>").append(esc(s.trim())).append("</li>");
        }
        sb.append("</").append(tag).append('>');
    }

    /** 表格单元格：空值写成「—」，与 Markdown 版保持一致 */
    private static String td(String v) {
        return "<td>" + esc(v == null || v.isBlank() ? "—" : v.trim()) + "</td>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
