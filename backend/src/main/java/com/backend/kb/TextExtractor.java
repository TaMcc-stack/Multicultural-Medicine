package com.backend.kb;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.text.TextPosition;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档全文提取工具：PDF（PDFBox 3.x）/ DOCX（JDK zip 解析 word/document.xml）/
 * 纯文本（txt/md/csv/tsv/json/rtf 按文本读取）。
 * 不依赖 POI，降低依赖体积。
 */
public final class TextExtractor {

    /** <w:t ...>文本</w:t> */
    private static final Pattern P_WT =
            // 只匹配 <w:t> 或 <w:t 属性...>；禁止匹配 <w:tbl*>、<w:top>、<w:tab> 等（避免表格 XML 泄漏进正文）
            Pattern.compile("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", Pattern.DOTALL);

    // ---- docx 结构保真：段落 <w:p> / 表格 <w:tbl>，行 <w:tr> / 单元格 <w:tc> ----
    private static final Pattern P_OPEN_P = Pattern.compile("<w:p(?:\\s[^>]*)?>", Pattern.DOTALL);
    private static final Pattern P_OPEN_TBL = Pattern.compile("<w:tbl(?:\\s[^>]*)?>", Pattern.DOTALL);
    private static final Pattern P_TR = Pattern.compile("<w:tr(?:\\s[^>]*)?>.*?</w:tr>", Pattern.DOTALL);
    private static final Pattern P_TC = Pattern.compile("<w:tc(?:\\s[^>]*)?>.*?</w:tc>", Pattern.DOTALL);
    private static final Pattern P_STYLE = Pattern.compile("<w:pStyle w:val=\"([^\"]+)\"");

    private TextExtractor() {
    }

    /** 提取结果：全文文本 + 页数（仅 PDF 有效，其余为 null） */
    public static final class ExtractResult {
        public final String text;
        public final Integer pageCount;
        ExtractResult(String text, Integer pageCount) {
            this.text = text;
            this.pageCount = pageCount;
        }
    }

    public static ExtractResult extract(String ext, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return new ExtractResult("", null);
        }
        String lower = (ext == null ? "" : ext.toLowerCase());
        switch (lower) {
            case "pdf":
                return extractPdf(bytes);
            case "docx":
                return new ExtractResult(extractDocx(bytes), null);
            default:
                // txt / md / csv / tsv / json / rtf 等按文本读取（容错）
                return new ExtractResult(readAsText(bytes), null);
        }
    }

    private static ExtractResult extractPdf(byte[] bytes) throws IOException {
        // 受保护的 PDF 先用空密码尝试解密；失败则按普通文档加载
        PDDocument doc;
        try {
            doc = Loader.loadPDF(bytes);
        } catch (IOException e) {
            doc = Loader.loadPDF(bytes, "");
        }
        try {
            if (doc.isEncrypted()) {
                try {
                    doc.setAllSecurityToBeRemoved(true);
                } catch (Exception ignored) {
                    // 无法解密：尽量返回已可读文本（部分内容可能为空）
                }
            }
            int pages = doc.getNumberOfPages();
            // 双栏感知抽取：检测每页是否双栏（期刊/学位论文常见），是则按「左栏→右栏」重排，
            // 否则按 (y→x) 顺序抽取，避免双栏下摘要/正文被逐行穿插错乱。
            ColumnAwareStripper stripper = new ColumnAwareStripper();
            String text = stripper.getText(doc);
            return new ExtractResult(text == null ? "" : text, pages);
            
            /* 原实现：sortByPosition(true) 在双栏下会把左右栏逐行穿插，导致摘要/正文错乱 */
        } finally {
            doc.close();
        }
    }

    /**
     * 双栏感知 PDF 抽取器：对每页收集「文字块 + 坐标」，检测页面上是否有一道靠近中央的
     * 空白栏缝（双栏版式）。若有，按「左栏整列 → 右栏整列」重排；否则退回单栏 (y→x) 顺序。
     * 解决期刊/学位论文双栏排版导致文本穿插、摘要/元数据识别错乱的问题。
     */
    private static final class ColumnAwareStripper extends PDFTextStripper {
        private static final double GUTTER_MIN = 0.035;  // 栏缝宽度至少占内容宽度的比例（双栏页缝通常 ~6%，防误判取小些）

        private static final class Word {
            final float x0, x1, y;
            final String text;
            Word(float x0, float x1, float y, String text) { this.x0 = x0; this.x1 = x1; this.y = y; this.text = text; }
        }

        private final List<Word> pageWords = new ArrayList<>();

        ColumnAwareStripper() {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions == null || positions.isEmpty()) return;
            TextPosition first = positions.get(0);
            TextPosition last = positions.get(positions.size() - 1);
            // PDFBox 默认按版面切块，一个「块」往往就是一行；用首/末字符坐标估算整行左右边界
            float x0 = first.getXDirAdj();
            float x1 = last.getXDirAdj() + last.getWidthDirAdj();
            pageWords.add(new Word(x0, x1, first.getYDirAdj(), text));
        }

        @Override
        protected void writePageStart() throws IOException {
            pageWords.clear();
        }

        @Override
        protected void writePageEnd() throws IOException {
            String assembled = assemble(pageWords);
            if (assembled != null && !assembled.isEmpty()) {
                output.write(assembled);
                output.write("\n");
            }
            // 每页都写页分隔符（\u000c，换页符），阅读器据此切页、在每页正文后插入该页原图；
            // 即使某页只有图片没有文字，也要写分隔符以保持「页数==分隔符数」的对应关系。
            output.write("\u000c");
            pageWords.clear();
        }

        /** 按页组装：检测双栏成立则「通栏行 + 左栏 + 右栏」，否则按 (y→x) 顺序拼接。 */
        private String assemble(List<Word> words) {
            if (words.isEmpty()) return "";
            float minX = Float.MAX_VALUE, maxX1 = Float.MIN_VALUE;
            for (Word w : words) {
                minX = Math.min(minX, w.x0);
                maxX1 = Math.max(maxX1, w.x1);
            }
            float contentW = Math.max(1f, maxX1 - minX);
            float center = minX + contentW / 2f;
            // 只取内容中段的行首坐标，找「距页面中心最近」的明显空白 → 栏缝
            List<Float> xs = new ArrayList<>();
            for (Word w : words) {
                if (w.x0 >= minX + contentW * 0.08f && w.x0 <= minX + contentW * 0.92f) xs.add(w.x0);
            }
            Collections.sort(xs);
            float boundary = -1f;
            double bestDist = Double.MAX_VALUE;
            double bestGap = 0;
            for (int i = 1; i < xs.size(); i++) {
                float gap = xs.get(i) - xs.get(i - 1);
                float mid = (xs.get(i) + xs.get(i - 1)) / 2f;
                if (gap > contentW * GUTTER_MIN) {
                    double dist = Math.abs(mid - center);
                    if (dist < bestDist - 1.0 || (Math.abs(dist - bestDist) <= 1.0 && gap > bestGap)) {
                        bestDist = dist;
                        bestGap = gap;
                        boundary = mid;
                    }
                }
            }
            if (boundary <= 0) return joinParas(paragraphTexts(words));

            // 双栏：按整行左右边界归入 通栏 / 左栏 / 右栏
            float SPAN = 5f;
            List<Word> full = new ArrayList<>();
            List<Word> left = new ArrayList<>();
            List<Word> right = new ArrayList<>();
            for (Word w : words) {
                if (w.x0 < boundary - SPAN && w.x1 > boundary + SPAN) full.add(w);
                else if (w.x0 >= boundary - SPAN) right.add(w);   // 起笔在栏缝及右侧 → 右栏
                else left.add(w);                                 // 止于栏缝左侧 → 左栏
            }
            StringBuilder sb = new StringBuilder();
            appendParas(sb, paragraphTexts(full));
            if (!left.isEmpty()) { if (sb.length() > 0) sb.append("\n"); appendParas(sb, paragraphTexts(left)); }
            if (!right.isEmpty()) { if (sb.length() > 0) sb.append("\n"); appendParas(sb, paragraphTexts(right)); }
            return sb.toString();
        }

        /** 按 (y→x) 排序整行。 */
        private List<Word> sortByY(List<Word> ws) {
            ws.sort((a, b) -> {
                int c = Float.compare(a.y, b.y);
                return c != 0 ? c : Float.compare(a.x0, b.x0);
            });
            return ws;
        }

        /** 把一列内的「物理行」按 y 空隙聚合成自然段落（段落间以 \n 分隔），避免阅读器一行一段、乱糟糟。 */
        private List<String> paragraphTexts(List<Word> ws) {
            ws.sort((a, b) -> {
                int c = Float.compare(a.y, b.y);
                return c != 0 ? c : Float.compare(a.x0, b.x0);
            });
            List<String> paras = new ArrayList<>();
            if (ws.isEmpty()) return paras;
            List<Float> gaps = new ArrayList<>();
            for (int i = 1; i < ws.size(); i++) gaps.add(ws.get(i).y - ws.get(i - 1).y);
            Collections.sort(gaps);
            float median = gaps.isEmpty() ? 0f : gaps.get(gaps.size() / 2);
            float thr = Math.max(median * 1.6f, 3.5f);
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < ws.size(); i++) {
                String t = ws.get(i).text.trim();
                if (t.isEmpty()) continue;
                if (i > 0 && (ws.get(i).y - ws.get(i - 1).y) > thr && cur.length() > 0) {
                    paras.add(cur.toString());
                    cur.setLength(0);
                }
                if (cur.length() > 0 && needsSpace(cur.toString(), t)) cur.append(' ');
                cur.append(t);
            }
            if (cur.length() > 0) paras.add(cur.toString());
            return paras;
        }

        /** 中英交界处加一空格，避免 "2026"+"Jan" → "2026Jan" 粘连；中文间不加。 */
        private static boolean needsSpace(CharSequence a, String b) {
            if (a.length() == 0 || b.isEmpty()) return false;
            return isAlnum(a.charAt(a.length() - 1)) && isAlnum(b.charAt(0));
        }

        private static boolean isAlnum(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
        }

        private void appendParas(StringBuilder sb, List<String> paras) {
            for (String p : paras) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p);
            }
        }

        private String joinParas(List<String> paras) {
            StringBuilder sb = new StringBuilder();
            for (String p : paras) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p);
            }
            return sb.toString();
        }
    }

    /** 解析 .docx（OOXML）：解压取 word/document.xml，抽取所有 <w:t> 文本 */
    private static String extractDocx(byte[] bytes) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = readAll(zis);
                    return extractDocxBody(xml);
                }
            }
        }
        return "";
    }

    /** 解析 docx 正文：按文档顺序输出 <w:p> 段落与 <w:tbl> 表格（表格转 Markdown），保留原始结构 */
    private static String extractDocxBody(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        List<String> blocks = new ArrayList<>();
        int i = 0;
        int n = xml.length();
        while (i < n) {
            int pp = nextOpen(xml, i, P_OPEN_P);
            int tt = nextOpen(xml, i, P_OPEN_TBL);
            if (pp < 0 && tt < 0) break;
            boolean isTable = (tt >= 0 && (pp < 0 || tt < pp));
            int start = isTable ? tt : pp;
            int end = isTable ? findTableEnd(xml, start) : findParaEnd(xml, start);
            if (end < 0) break;
            String block = xml.substring(start, end);
            if (isTable) {
                String md = tableToMarkdown(block);
                if (!md.trim().isEmpty()) blocks.add(md);
            } else {
                String line = inlineText(block).trim();
                if (!line.isEmpty()) {
                    int lvl = detectHeadingLevel(line, pStyleOf(block));
                    if (lvl == 1) line = "# " + line;
                    else if (lvl == 2) line = "## " + line;
                    else if (lvl == 3) line = "### " + line;
                    blocks.add(line);
                }
            }
            i = end;
        }
        return joinBlocks(normalizeBlocks(blocks));
    }

    /**
     * 把块拼成 Markdown：**块与块之间空一行**。
     *
     * <p>单个换行在 Markdown 里等于同一段。之前只用 {@code \n} 分隔，粘出来的正文在任何标准
     * 渲染器里都会并成一大块——这正是「导入的 docx 内容看起来是一整块文字」的成因。
     * （前端阅读器是按行切块的，所以当时在自家界面上看不出来，但存进库的文本本身必须是
     * 合法 Markdown，否则一换渲染器就露馅。）</p>
     */
    private static String joinBlocks(List<String> blocks) {
        StringBuilder sb = new StringBuilder();
        for (String b : blocks) {
            String t = b.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
        }
        return sb.toString();
    }

    /** 题名行的长度上限：超过这个长度就不是题名，是正文 */
    private static final int TITLE_MAX_LEN = 60;
    /** 「紧跟其后的正文」的长度下限：短行后面还是短行，那多半是列表项，不是题名 */
    private static final int BODY_MIN_LEN = 120;
    /**
     * 汇编里「一篇文献的题名」的长度下限，用来把题名与真小节标题分开。
     * 「方法 / 结果 / 结论」这类小节标题只有 2~4 字，而文献题名动辄二三十字。
     */
    private static final int ENTRY_TITLE_MIN_LEN = 12;

    /** 只有井号的空标题块（手写汇编里偶尔会留下孤零零一个 `###`，还可能带不换行空格） */
    private static boolean isBareHeading(String t) {
        return t.matches("^#{1,6}[\\s\\u00a0]*$");
    }

    /**
     * 整理块序列：丢弃空标题、统一题名层级、补认题名。
     *
     * <p>为什么要「统一题名层级」：{@link #detectHeadingLevel} 会把一部分题名判成 `###`
     * （它的「短标签」规则），而兜底规则补出来的是 `##`。同一份汇编里于是有的条目是 `##`、
     * 有的是 `###`——看着像层级不同，其实它们是一回事。这里统一成 `##`
     * （`#` 留给「这份汇编讲什么」级别的标题，导入的 docx 里没有这一层）。</p>
     *
     * <p>为什么还要「补认题名」：那套规则认的是 Word 的 Heading 样式与「一、」「1、」编号，
     * 而实际导入的汇编是一篇篇题名直接跟着摘要，既没有样式也没有编号——于是同一份文档里
     * 只有零星几个题名被认出来，比一个都不认更乱。这里按**结构**补一道：题名后面必然
     * 跟着一大段正文，正文前面那一行短文本就是题名。</p>
     */
    private static List<String> normalizeBlocks(List<String> raw) {
        List<String> blocks = new ArrayList<>();
        for (String b : raw) {
            String t = b.trim();
            // 孤立的 `###` 会挡住它前面那行题名的兜底判断（下一块以 # 开头就不认题名），
            // 所以先清掉，再判断
            if (t.isEmpty() || isBareHeading(t)) continue;
            blocks.add(t);
        }
        for (int k = 0; k < blocks.size(); k++) {
            String cur = blocks.get(k);
            if (cur.startsWith("|")) continue;                       // 表格
            // ① 长题名即使被判成 ###，也应当是 ##
            if (cur.startsWith("### ") && cur.length() > ENTRY_TITLE_MIN_LEN) {
                blocks.set(k, "## " + cur.substring(4));
                continue;
            }
            if (cur.startsWith("#")) continue;                       // 其余已是标题，不动
            // ② 题名兜底：短行、不含句末标点，且**前一块或后一块是长正文**
            //
            // 判定「长正文」要看两边而不是只看后面：汇编里绝大多数题名紧跟着自己的摘要
            // （后一块是长正文），但少数条目（先给一句总述、再分「方法/结果/结论」小节）
            // 后面跟的是一句短结论，只看后面就漏掉了。这时候它前面那块正好是上一篇的摘要。
            // 第一个块没有前文，按题名处理（文档通常以标题开头）。
            boolean prevLong = k > 0 && !blocks.get(k - 1).startsWith("#")
                    && blocks.get(k - 1).length() >= BODY_MIN_LEN;
            boolean nextIsBody = k + 1 < blocks.size()
                    && !blocks.get(k + 1).startsWith("#") && !blocks.get(k + 1).startsWith("|")
                    && blocks.get(k + 1).length() >= BODY_MIN_LEN;
            if (k > 0 && !prevLong && !nextIsBody) continue;
            if (cur.length() > TITLE_MAX_LEN) continue;
            // 以句末标点结尾的一定是句子，不是题名
            if ("。！？；.!?;".indexOf(cur.charAt(cur.length() - 1)) >= 0) continue;
            blocks.set(k, "## " + cur);
        }
        return blocks;
    }

    /** 从 from 起找到下一个匹配标签的起始位置 */
    private static int nextOpen(String xml, int from, Pattern p) {
        Matcher m = p.matcher(xml);
        if (m.find(from)) return m.start();
        return -1;
    }

    /** 段落 <w:p ...>...</w:p> 的结束位置（含 </w:p> 之后） */
    private static int findParaEnd(String xml, int start) {
        int c = xml.indexOf("</w:p>", start);
        return c < 0 ? -1 : c + "</w:p>".length();
    }

    /** 表格 <w:tbl ...>...</w:tbl> 的结束位置（含 </w:tbl> 之后），支持嵌套计数 */
    private static int findTableEnd(String xml, int start) {
        int depth = 0;
        int pos = start;
        while (true) {
            int o = nextOpen(xml, pos, P_OPEN_TBL);
            int c = xml.indexOf("</w:tbl>", pos);
            if (c < 0) return -1;
            if (o >= 0 && o < c) {
                depth++;
                pos = o + 1;
            } else {
                depth--;
                pos = c + "</w:tbl>".length();
                if (depth == 0) return pos;
            }
        }
    }

    /** 取段落样式名（Word 标题样式，如 Heading1/标题 1），无则返回 null */
    private static String pStyleOf(String block) {
        Matcher m = P_STYLE.matcher(block);
        return m.find() ? m.group(1) : null;
    }

    /** 识别标题层级：优先 pStyle（Word 标题样式），否则按文字编号/短标签启发式。返回 0 表示正文段落 */
    private static int detectHeadingLevel(String text, String pStyle) {
        String t = text.trim();
        if (pStyle != null) {
            String ps = pStyle.toLowerCase();
            if (ps.startsWith("heading") || ps.contains("\u6807\u9898")) {
                String num = ps.replaceAll("\\D", "");
                try {
                    int n = Integer.parseInt(num);
                    return Math.max(1, Math.min(n, 3));
                } catch (Exception e) {
                    return 1;
                }
            }
        }
        // 中文数字编号：一、二、三…
        if (t.matches("^[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341\u767e]+[\u3001].*")) return 1;
        // 阿拉伯数字编号：1、 2、 …
        if (t.matches("^\\d+[\\u3001].*")) return 2;
        // 短标签（表前小标题/章节小标题）：短、无句末标点、不含逗号句号、无数字
        if (t.length() <= 24
                && !t.endsWith("\u3002") && !t.endsWith(".")
                && !t.endsWith("\uff01") && !t.endsWith("\uff1f") && !t.endsWith("?")
                && !t.endsWith("\uff1b") && !t.endsWith(";")
                && !t.contains("\uff0c") && !t.contains(",")
                && !t.matches(".*\\d.*")) {
            return 3;
        }
        return 0;
    }

    /** 段落/单元格内联文本：抽取所有 <w:t> 并拼接 */
    private static String inlineText(String block) {
        StringBuilder line = new StringBuilder();
        Matcher m = P_WT.matcher(block);
        while (m.find()) line.append(m.group(1));
        return unescapeXml(line.toString()).trim();
    }

    /** 单元格文本：一个 <w:tc> 内可能有多个段落，用空格连接 */
    private static String cellText(String cellBlock) {
        StringBuilder sb = new StringBuilder();
        String[] ps = cellBlock.split("</w:p>");
        for (String p : ps) {
            String t = inlineText(p);
            if (!t.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /** 将 <w:tbl> 块转成 Markdown 表格（首行作为表头） */
    private static String tableToMarkdown(String tblBlock) {
        List<List<String>> rows = new ArrayList<>();
        Matcher tr = P_TR.matcher(tblBlock);
        while (tr.find()) {
            List<String> cells = new ArrayList<>();
            Matcher tc = P_TC.matcher(tr.group(0));
            while (tc.find()) cells.add(cellText(tc.group(0)));
            if (!cells.isEmpty()) rows.add(cells);
        }
        if (rows.isEmpty()) return "";
        int cols = 0;
        for (List<String> r : rows) cols = Math.max(cols, r.size());
        StringBuilder md = new StringBuilder();
        // 表头行
        md.append('|');
        for (String c : rows.get(0)) md.append(' ').append(c).append(" |");
        for (int k = rows.get(0).size(); k < cols; k++) md.append("  |");
        md.append('\n');
        // 分隔行
        md.append('|');
        for (int k = 0; k < cols; k++) md.append(" --- |");
        md.append('\n');
        // 数据行
        for (int r = 1; r < rows.size(); r++) {
            md.append('|');
            for (int k = 0; k < cols; k++) {
                String c = k < rows.get(r).size() ? rows.get(r).get(k) : "";
                md.append(' ').append(c).append(" |");
            }
            md.append('\n');
        }
        return md.toString();
    }

    private static String readAsText(byte[] bytes) {
        // 逐行读取，避免超长行导致 String 处理异常
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            // 非 UTF-8 文本：退化为 ISO-8859-1
            try {
                return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            } catch (Exception ignored) {
                return "";
            }
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        // 先整体读入字节，再一次性按 UTF-8 解码，避免多字节汉字在 8192 边界被切坏产生乱码
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String unescapeXml(String s) {
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'");
    }
}
