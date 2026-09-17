package com.backend.kb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文献「证据片段」结构化提取器。
 *
 * <p>支持两类输入：
 * <ol>
 *   <li><b>单篇标签格式</b>（【出处】【民族/疾病】【研究类型】【研究人群】【主要结论】【证据片段】…）：
 *       精确解析每块字段，逐条解析【证据片段】下的 - [topic] 文本。</li>
 *   <li><b>自由版式多民族综述报告</b>（如各章节+表格+列表的流行病学/遗传学综述）：
 *       识别全部涉及民族与疾病，将段落表格的行解析为「患病率」片段（带民族/疾病标签），
 *       将含 RR/OR/遗传/基因 的列表项解析为「危险/遗传」片段，对齐 papers.json 的证据结构。</li>
 * </ol>
 *
 * <p>大模型辅助：Spring Boot 上传时还会调用 Python AI 服务（/api/ai/kb/evidence/extract）用大模型抽取
 * 更高质量的片段并覆盖本提取器的 fragments；本器作为无 LLM 时的兜底。</p>
 */
public final class EvidenceExtractor {

    private EvidenceExtractor() {
    }

    /** 一条证据片段：主题标签 + 文本 + 可选的民族/疾病归属（对齐 papers.json 的 evidence 结构） */
    public static final class Fragment {
        public final String topic;
        public final String text;
        public final String ethnicity;   // 可选：该片段归属民族
        public final String disease;     // 可选：该片段归属疾病/主题
        Fragment(String topic, String text) {
            this(topic, text, null, null);
        }
        Fragment(String topic, String text, String ethnicity, String disease) {
            this.topic = topic;
            this.text = text;
            this.ethnicity = ethnicity;
            this.disease = disease;
        }
    }

    /** 结构化证据 */
    public static final class ExtractedEvidence {
        public String source;       // 出处
        public String author;       // 作者
        public String ethnicity;    // 民族（多民族时用「、」连接或「多民族」）
        public String disease;      // 疾病/主题（多病种时连接或「多疾病」）
        public String studyType;    // 研究类型
        public String studyYear;    // 研究年份
        public String population;   // 研究人群
        public String conclusion;   // 主要结论/概述
        public String limitations;  // 局限性
        public List<Fragment> fragments = new ArrayList<>();

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append(jsonKV("source", source)).append(',');
            sb.append(jsonKV("author", author)).append(',');
            sb.append(jsonKV("ethnicity", ethnicity)).append(',');
            sb.append(jsonKV("disease", disease)).append(',');
            sb.append(jsonKV("studyType", studyType)).append(',');
            sb.append(jsonKV("studyYear", studyYear)).append(',');
            sb.append(jsonKV("population", population)).append(',');
            sb.append(jsonKV("conclusion", conclusion)).append(',');
            sb.append(jsonKV("limitations", limitations)).append(',');
            sb.append("\"fragments\":[");
            for (int i = 0; i < fragments.size(); i++) {
                if (i > 0) sb.append(',');
                Fragment f = fragments.get(i);
                sb.append('{').append(jsonKV("topic", f.topic));
                if (f.ethnicity != null) sb.append(',').append(jsonKV("ethnicity", f.ethnicity));
                if (f.disease != null) sb.append(',').append(jsonKV("disease", f.disease));
                sb.append(',').append(jsonKV("content", f.text)).append('}');
            }
            sb.append(']');
            sb.append('}');
            return sb.toString();
        }
    }

    private static final Pattern LABEL_PAT = Pattern.compile("^【([^】]{1,16})】\\s*(.*)$");
    private static final Pattern BULLET_PAT = Pattern.compile(
            "^(?:[-—–·•●]|\\(\\d+\\)|（\\d+）|\\d+[、.．])\\s*(?:\\[([^\\]]{1,30})\\])?\\s*(.*)$");
    private static final Pattern YEAR_PAT = Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)");
    private static final Pattern PERCENT_PAT = Pattern.compile("\\d+(\\.\\d+)?%");
    private static final Pattern SECTION_PAT = Pattern.compile("^(?:[一二三四五六七八九十]+|[0-9]+)\\s*[、.．、]\\s*(.+)$");

    private static final String[] ETHNICITIES = {
            "傣族", "白族", "彝族", "藏族", "蒙古族", "维吾尔族", "苗族", "回族", "壮族", "满族",
            "朝鲜族", "瑶族", "土家族", "哈尼族", "哈萨克族", "黎族", "佤族", "纳西族", "景颇族",
            "布依族", "侗族", "水族", "仡佬族", "畲族", "拉祜族", "傈僳族", "羌族", "土族",
            "撒拉族", "东乡族", "保安族", "裕固族", "俄罗斯族", "鄂温克族", "鄂伦春族", "赫哲族",
            "门巴族", "珞巴族", "基诺族", "独龙族", "普米族", "阿昌族", "德昂族", "布朗族",
            "怒族", "京族", "塔塔尔族", "塔吉克族", "乌孜别克族", "柯尔克孜族", "锡伯族"
    };
    private static final String[] DISEASES = {
            "糖尿病", "高血压", "冠心病", "脑卒中", "肥胖", "贫血", "乙肝", "结核病", "哮喘",
            "慢阻肺", "风湿", "痛风", "肿瘤", "癌症", "白内障", "骨质疏松", "抑郁症", "肾病", "肝病"
    };
    // 自由版式章节里的「主题/疾病提示词」（用于给表格/片段标注当前主题）
    private static final String[] TOPIC_HINTS = {
            "CKM", "心血管", "代谢综合征", "综合征", "糖尿病", "高血压", "肥胖", "脂肪肝",
            "心脏瓣膜病", "遗传病", "遗传", "NAFLD", "非酒精性", "结核"
    };

    public static ExtractedEvidence extract(String text, String originalName) {
        ExtractedEvidence ev = new ExtractedEvidence();
        List<String> lines = splitLines(text);

        // 1) 显式【标签】块解析（整篇扫描）
        boolean labeledFragments = parseLabeledBlocks(lines, ev);

        // 2) 自由版式增强（多民族/多病种识别 + 表格/列表→片段）
        enrichFreeform(lines, text, ev, labeledFragments);

        // 3) 兜底：未识别字段用启发式补充
        MetadataExtractor.DocMeta meta = MetadataExtractor.extract(text, originalName);
        if (isEmpty(ev.author)) ev.author = meta.author;
        if (isEmpty(ev.source)) ev.source = meta.source;
        if (isEmpty(ev.studyYear) && meta.publishYear != null) {
            ev.studyYear = String.valueOf(meta.publishYear);
        }
        if (isEmpty(ev.conclusion) && !isEmpty(meta.summary)) ev.conclusion = meta.summary;
        if (isEmpty(ev.ethnicity)) ev.ethnicity = summarize(detectAll(text, ETHNICITIES));
        if (isEmpty(ev.disease)) ev.disease = summarize(detectAll(text, DISEASES));
        if (ev.fragments.isEmpty() && !isEmpty(ev.conclusion)) {
            ev.fragments.add(new Fragment("overview", ev.conclusion));
        }
        return ev;
    }

    /** @return 是否解析到显式【证据片段】块 */
    private static boolean parseLabeledBlocks(List<String> lines, ExtractedEvidence ev) {
        int n = lines.size();
        boolean foundFragments = false;
        for (int i = 0; i < n; i++) {
            Matcher lm = LABEL_PAT.matcher(lines.get(i));
            if (!lm.find()) continue;
            String rawLabel = lm.group(1).trim();
            String inline = lm.group(2);

            if (rawLabel.contains("民族") && rawLabel.contains("疾病")) {
                String[] parts = inline.split("[·•/／、]");
                if (parts.length >= 2) {
                    ev.ethnicity = parts[0].trim();
                    ev.disease = parts[1].trim();
                } else if (!inline.isEmpty()) {
                    ev.ethnicity = inline.trim();
                }
                continue;
            }

            String field = mapLabel(rawLabel);
            if (field == null) continue;

            if ("fragments".equals(field)) {
                foundFragments = true;
                for (int j = i + 1; j < n; j++) {
                    String l = lines.get(j);
                    if (LABEL_PAT.matcher(l).find()) break;
                    Matcher bm = BULLET_PAT.matcher(l);
                    if (bm.find()) {
                        String topic = bm.group(1);
                        String txt = bm.group(2);
                        if (!isEmpty(txt)) {
                            ev.fragments.add(new Fragment(canonicalTopic(topic), txt.trim()));
                        }
                    }
                }
                continue;
            }

            StringBuilder sb = new StringBuilder();
            if (!isEmpty(inline)) sb.append(inline);
            for (int j = i + 1; j < n; j++) {
                String l = lines.get(j);
                if (LABEL_PAT.matcher(l).find()) break;
                if (sb.length() > 0) sb.append(" ");
                sb.append(l);
            }
            String val = sb.toString().trim();
            if (!val.isEmpty()) assignField(ev, field, val);
        }
        return foundFragments;
    }

    /** 自由版式增强：多民族/多病种 + 表格行/列表→结构化片段 */
    private static void enrichFreeform(List<String> lines, String text, ExtractedEvidence ev, boolean labeledFragments) {
        List<String> allEth = detectAll(text, ETHNICITIES);
        List<String> allDis = detectAll(text, DISEASES);
        if (isEmpty(ev.ethnicity)) ev.ethnicity = summarize(allEth);
        if (isEmpty(ev.disease)) ev.disease = summarize(allDis);

        // 显式【证据片段】已提供时，保持原行为，不再做自由版式片段解析
        if (labeledFragments) return;

        String currentTopic = null;
        for (String line : lines) {
            // 章节标题 → 更新当前主题（用于给片段标注疾病/主题）
            Matcher sm = SECTION_PAT.matcher(line);
            if (sm.find()) {
                String topicText = sm.group(1);
                for (String h : TOPIC_HINTS) {
                    if (topicText.contains(h)) { currentTopic = h; break; }
                }
            }

            // 表格数据行：以民族名开头且含百分比 → 患病率片段
            String ethPrefix = matchEthnicityPrefix(line);
            if (ethPrefix != null && PERCENT_PAT.matcher(line).find()) {
                String txt = line.replace("\t", " ").replaceAll("\\s+", " ").trim();
                ev.fragments.add(new Fragment("prevalence", txt, ethPrefix, currentTopic));
                continue;
            }

            // 含「患病率/率 + 百分比」的普通句（如 NAFLD、肥胖行）→ 患病率片段（民族可能为空）
            if (PERCENT_PAT.matcher(line).find()
                    && (line.contains("患病率") || line.contains("标化") || line.contains("检出率") || line.contains("率分别为"))) {
                String eth = matchEthnicityIn(line);
                ev.fragments.add(new Fragment("prevalence",
                        line.replace("\t", " ").replaceAll("\\s+", " ").trim(), eth, currentTopic));
                continue;
            }

            // 列表/编号项：含危险/遗传/基因/RR/OR → 危险或遗传片段
            Matcher bm = BULLET_PAT.matcher(line);
            String body = bm.find() ? bm.group(2) : line;
            if (containsAny(body, new String[]{"危险", "因素", "RR", "OR", "遗传", "基因", "突变", "多态", "变异"})) {
                String eth = matchEthnicityIn(body);
                ev.fragments.add(new Fragment(canonicalTopicFromText(body),
                        body.replace("\t", " ").replaceAll("\\s+", " ").trim(), eth, currentTopic));
            }
        }
    }

    private static void assignField(ExtractedEvidence ev, String field, String val) {
        switch (field) {
            case "source":      ev.source = val; break;
            case "author":      ev.author = val; break;
            case "ethnicity":   ev.ethnicity = val; break;
            case "disease":     ev.disease = val; break;
            case "studyType":
                ev.studyType = val;
                Matcher m = YEAR_PAT.matcher(val);
                if (m.find()) ev.studyYear = m.group(1);
                break;
            case "studyYear":   ev.studyYear = val; break;
            case "population":  ev.population = val; break;
            case "conclusion":  ev.conclusion = val; break;
            case "limitations": ev.limitations = val; break;
            default: break;
        }
    }

    /** 标签名 → 字段 */
    private static String mapLabel(String raw) {
        if (raw.contains("出处") || raw.contains("来源") || raw.contains("刊名")
                || raw.contains("期刊") || raw.contains("文献来源")) return "source";
        if (raw.contains("作者") || raw.contains("著者")) return "author";
        if (raw.contains("民族") && raw.contains("疾病")) return "ethdis";
        if (raw.contains("民族")) return "ethnicity";
        if (raw.contains("疾病") || raw.contains("病种")) return "disease";
        if (raw.contains("研究类型") || raw.contains("研究设计") || raw.contains("设计")) return "studyType";
        if (raw.contains("研究年份") || raw.contains("发表年份") || raw.contains("年份")) return "studyYear";
        if (raw.contains("研究人群") || raw.contains("对象") || raw.contains("人群") || raw.contains("样本")) return "population";
        if (raw.contains("结论") || raw.contains("主要发现") || raw.contains("结果")) return "conclusion";
        if (raw.contains("局限") || raw.contains("不足") || raw.contains("缺陷")) return "limitations";
        if (raw.contains("证据片段") || raw.contains("证据") || raw.contains("片段")) return "fragments";
        return null;
    }

    /** 证据主题归一化（来自显式标签如 [prevalence]） */
    private static String canonicalTopic(String t) {
        if (t == null) return "overview";
        String s = t.trim().toLowerCase();
        if (s.contains("prevalence") || s.contains("患病") || s.contains("流行") || s.contains("率")) return "prevalence";
        if (s.contains("risk") || s.contains("危险") || s.contains("因素")) return "risk";
        if (s.contains("diet") || s.contains("膳食") || s.contains("饮食") || s.contains("营养")) return "diet";
        if (s.contains("genetic") || s.contains("遗传")) return "genetics";
        if (s.contains("overview") || s.contains("综述") || s.contains("总体") || s.contains("概况")) return "overview";
        return s.isEmpty() ? "overview" : s;
    }

    /** 由正文文本推断主题（用于自由版式列表项） */
    private static String canonicalTopicFromText(String t) {
        if (t == null) return "overview";
        if (t.contains("遗传") || t.contains("基因") || t.contains("突变") || t.contains("多态") || t.contains("变异")) return "genetics";
        if (t.contains("膳食") || t.contains("饮食") || t.contains("营养") || t.contains("脂肪") || t.contains("蛋白")) return "diet";
        if (t.contains("危险") || t.contains("因素") || t.contains("RR") || t.contains("OR")) return "risk";
        return "overview";
    }

    // ---------------- 识别工具 ----------------

    private static List<String> detectAll(String text, String[] keywords) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String kw : keywords) {
            if (text.contains(kw) && !out.contains(kw)) out.add(kw);
        }
        return out;
    }

    /** 行首是否以某民族名开头（用于表格数据行） */
    private static String matchEthnicityPrefix(String line) {
        for (String e : ETHNICITIES) {
            if (line.startsWith(e)) return e;
        }
        return null;
    }

    /** 文本中出现的第一民族（用于给片段标注归属民族） */
    private static String matchEthnicityIn(String text) {
        if (text == null) return null;
        for (String e : ETHNICITIES) {
            if (text.contains(e)) return e;
        }
        return null;
    }

    private static boolean containsAny(String text, String[] subs) {
        if (text == null) return false;
        for (String s : subs) if (text.contains(s)) return true;
        return false;
    }

    /** 多值汇总：空→null；1个→原值；≤3个→「、」连接；更多→「多X」 */
    private static String summarize(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);
        if (list.size() <= 3) return String.join("、", list);
        return "多" + (list.get(0).endsWith("族") ? "民族" : "疾病");
    }

    // ---------------- 通用工具 ----------------

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String raw : text.split("\\R")) {
            String t = raw.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String jsonKV(String k, String v) {
        if (v == null || v.trim().isEmpty()) return "\"" + k + "\":null";
        return "\"" + k + "\":\"" + escape(v) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
