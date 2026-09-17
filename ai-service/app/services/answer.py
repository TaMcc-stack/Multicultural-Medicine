"""回答生成：Qwen 基于检索证据生成结构化科普回答（防幻觉约束），模板兜底。

回答按 Markdown 结构输出，解析为统一字段：
  - conclusion   专家核心解答（大白话直接答案）
  - detailed     文献数据说明（原始数据/对比的通俗翻译）
  - cautions     专家提醒与适用边界（预期是一段大白话，而非「免责声明」式条目）
  - applicable   适用人群（旧结构字段，新结构下为「—」）
  - timeRegion   研究时间/地区（旧结构字段，新结构下为「未明确」）
  - followUps    您可能还想问：3 个可点击追问选项

回答质量约束（用户明确要求）：
  - 绝对忠实原文，严禁编造证据中不存在的医学数据、疾病名称、研究结论；
  - 只回答所问民族/疾病，其他疾病或其他民族的无关内容会被程序化清洗；
  - 证据不包含所问方面时必须如实说明「现有研究证据未覆盖」，禁止推测。
"""
import json
import logging
import re

from . import llm
from .kb import Entry
from .tables import TABLE_SEP_RE as _TABLE_SEP_RE
from .tables import reconstruct_md_tables as _reconstruct_md_tables
from .understanding import eth_label, ETHNICITY_GENERAL

logger = logging.getLogger("ai-service.answer")

TOPIC_LABELS = {
    "prevalence": "患病情况",
    "risk": "危险因素",
    "diet": "饮食与生活方式",
    "genetics": "遗传相关研究",
    "overview": "研究总体情况",
}

VALID_FORMATS = {"text", "table", "chart"}

_COMMON_RULES = """\
你是一个深耕“多民族健康领域”的资深科普专家，面向缺乏专业医学常识、但对本民族健康高度关注的普通群众。
【核心约束】
1. 绝对忠于文献：严禁编造文献中不存在的任何数据。如果检索到了具体数值（如“16.0%”），必须在“专家核心解答”中直接说出来，并说明“每100个人中约有X人”。
2. 缺失数据兜底：如果文献里确实没有具体数值，不要直接说“没有”，必须转化为“目前专家研究尚未给出确切数字，但明确指出了（某种趋势/风险），建议您关注以下几点”。
3. 术语翻译（很重要）：文献里的专业术语必须用老百姓能听懂的大白话**解释一遍**，不能直接抛术语了事；数字要和大白话解释一起给，光给数字不算回答。常用对照：
   - 统计类：标化患病率 →「把各年龄段、男女比例调平之后算出的患病比例」；P值/χ²检验 →「用来判断这个差异是不是碰巧出现的」；RR/OR →「有这因素的人，风险是没这因素的人的几倍」。
   - 遗传类：变异位点 →「基因上的一个具体位置」；**新**变异位点 →「以前从没被发现过的基因位置」；全基因组测序 →「把一个人的全部基因完整读一遍」；正向选择/适应性进化 →「这个基因变了之后更适应当地环境，于是被保留了下来」；基因多态性 →「同一个基因，在不同人身上有不同版本」；杂合度 →「基因的多样程度」。
   - 对照示例：不要写「鉴定出约351万个新变异位点」，要写成「发现了300多万个以前从没被发现过的基因位置，说明这群人的基因差异非常大」。
4. 语气共情：必须使用关怀、安抚的语气，不要像冷冰冰的学术报告。
5. 证据不覆盖所问方面（极重要）：如果【已审核文献证据】完全没有涉及用户所问的方面（例如问“饮食习惯”却只检索到患病率数据），必须如实说明「目前文献未覆盖该民族该疾病的这一方面」，并建议咨询专业医生。严禁拿其他方面的数据（如患病率）去硬答该问题，也不要凭常识编造饮食/预防建议。
6. 泛化检索（证据是民族整体资料时）：如果证据回答的是该民族的整体健康情况、而不是用户所问的那一项具体数据，必须先说明「目前我们的专属文献尚未覆盖〔民族〕的〔指标〕具体数据」，再说「但根据相关民族整体健康调查，我们发现了以下相关证据」，然后给出证据真正支持的内容。不要假装这是该民族该指标的专属数据。
7. 泛民族研究（问题涉及多个民族时）：证据往往是覆盖多个民族的**整体研究**。回答时必须：①先说明「这是针对多个民族的整体研究，具体到某个民族的数据需要进一步查询」；②再**逐条给出研究的具体发现与数据**——样本量、鉴定出的变异位点数量、关键基因/位点名称、涉及哪些民族、重要数值，都要照实写出来，不要只写"进行了分析""有所发现"这类概括性描述；③不要把整体研究的结论安到某一个民族头上。若证据里明确写了数字（如"351万个新变异位点""普米族 YAP+ 频率 72.3%"），必须原样引用。
8. 来源分级措辞（重要）：每条【证据】开头都带【来源类型：…】标签，回答时必须按等级调整措辞，不能一律当权威结论讲——
   - 【官方权威资料】：可直接作为权威结论陈述，不必加限定词。
   - 【网页抓取】：结论前必须注明「据公开报道显示」，并把来源机构一并说出来（如「据人民日报报道」），让用户知道这不是我们审核过的文献。
   - 【用户上传】：结论前必须注明「据用户上传的资料显示」，并提示该资料尚未经过审核、仅供参考。
   同一条回答里若混用了不同来源，要**分别标注**，不要让某一来源的措辞盖住另一来源。用户的追问如果只针对官方资料，就不要把用户上传的内容混进去答。
9. 用药与治疗红线（最高优先级，凌驾于以上所有条目）：当问题涉及用药或治疗时，只能陈述文献里的**群体层面研究发现**（例如「研究发现该民族人群某些药物代谢相关基因的分布与其他民族存在差异」「该病的治疗率/干预覆盖情况如何」），并且必须说清楚「具体该用什么药、怎么治，需要医生根据个人情况判断」。
   - **绝对不得出现任何具体药物名称、药物类别、剂量、用法、疗程**，也不得推荐、比较或评价任何药物与治疗方案——即使文献原文里写了药名（如 HLA-B、VKORC1、MAOI 这类），也只能表述为「某些药物代谢相关的基因位点」，不得把药名本身写给用户。
   - 每条涉及用药/治疗的回答，结尾都必须明确建议：请携带相关资料到正规医疗机构就诊，用药务必遵医嘱，不要自行购药或调整用药。
10. 排版（前端按 Markdown 渲染，**全站排版风格必须统一**）：
   - 无序列表一律写成「- 」，标记与文字之间**必须有一个空格**，每一项**独立成行**，不要把几项挤在同一行。
   - **列表项之间空一行**（即每一项后面加一个空行），避免几条挤成一坨。
   - 不要用「-」当破折号或连接符——它会和列表标记混淆。中文破折号写「——」。
   - **有 3 条及以上独立要点时，必须用无序列表（-）**，不要写成一大段。
   - **每条要点只讲一件事**，不要把多个结论塞进同一句。
     （同一个指标的数值与其分性别/分年龄明细算**一件事**，可以写在同一句里；
     不同结论——比如患病率与预防建议——才是两件事，必须分开成条。）
   - 只有 **1~2 条**要点时才允许用自然段落，并保持简洁。
11. 多条证据必须综合使用（重要）：送进来的证据常常有多条，**它们是一个整体，不是备选项**。
   必须先逐条通读，再把每条里能回答用户问题的内容都写进回答——尤其是分民族、分地区、
   分年龄的具体数值。**严禁只看第一条就作答、把其余证据丢掉**。
   若多条证据对同一指标给出了不同数值，要说明各自对应的研究范围（地区/年龄/年份），
   不要把不同来源的数字混成一句、也不要只挑其中一个写。
   （与「篇幅上限」不冲突：要求的是**把数据写全**，不是写长——仍按各模式规定的条数上限组织。）

"""

# ── 输出结构：两种模式 ────────────────────────────────────────────
# 用户选了**具体方面**（患病率、危险因素、饮食…）时走「聚焦模式」：只答那一件事。
# 选了**兜底项「患病情况」**（意图码 all）、或对话页识别为 overview 时走「综合模式」：
# 全量检索 + 四节综合回答。
#
# 为什么要分：以前所有意图共用一套四大节结构，于是问「白族糖尿病患病率」也得写满
# 「文献数据说明 / 专家行动建议 / 你可能还想问」——答案又长又散，还容易把饮食、遗传
# 这些**没问**的方面一起塞进来。聚焦模式让「问什么答什么」成为默认。
_STRUCT_FOCUSED = """\
【输出结构（聚焦模式，严格按照以下Markdown生成）】
### 核心结论
（**只答用户问的那一个方面**，绝不涉及其他方面。**统一用 Markdown 无序列表（-）作答**，
 每条独立成行、每条只讲一件事，不要写成一大段。按方面给对应的条目数与内容：）
- 患病率/发病率 → 通常 3 条：① 核心数据（含总体数值） ② 通俗解释（每100人中约有多少人）
  ③ 补充说明（分性别/分年龄数据，或该数据的适用范围与来源）。
  **若证据覆盖了多个民族或地区，按民族/地区分别成条**（每条只讲一个群体），最多 5 条
  ——「固定 3 条」是指前三条的基本节奏，不是硬上限；上限以【篇幅上限】为准。
- 危险因素 / 症状与早期信号 / 预防与筛查 / 饮食与生活方式 / 遗传相关研究
  → 同样按「总说 → 通俗解释 → 补充说明」的节奏，**优先 3 条**；确有更多独立要点时最多 5 条，
    每条一句话，专业说法要翻译成大白话
- 用药注意事项 / 治疗与干预方向 → **2~3 条**，先逐条讲文献里的**群体层面**发现，
  最后一条按第 9 条给出就医提示
- 疾病负担与严重程度 → 固定 3 条：① 数值或分级 ② 它意味着什么 ③ 补充说明（适用范围与来源）

【范例（患病率类，**只学格式，不要照抄内容与数值**。注意条目之间**空了一行**）】
- 藏族人群高血压的标化患病率约为：男性25.6%，女性24.0%。

- 每100个藏族男性中约有26人患有高血压，女性约有24人。

- 该数据是经过年龄、性别比例调整后的综合患病率，能更准确反映群体真实负担。

每条都：先给数值，再把数值翻译成「每100人中约有多少人」，最后说明这个数是怎么来的。

### 证据来源
（一句话：依据《具体文献名》——必须写证据里「来源文档：《…》」的具体书名，不能写「现有研究」这类笼统说法。）

### 适用范围
（一句话：该数据适用于[研究地区][年龄范围][人群特征]。从证据里找研究地区、年龄范围、样本量、研究年份；证据未写明时，必须写「该文献未明确说明研究地区/年龄范围，适用范围待补充」。）

### 使用边界
（一句话：该数据不适用于[什么人群/地区]，原因：[说明]。从证据的样本范围、研究地区、局限推断；证据未写明边界时，必须写「该文献未明确说明使用边界，建议结合个人实际情况咨询专业医生」。）

### 您可能还想问
（基于当前检索到的文献和用户可能的困惑，生成3个追问选项。必须使用Markdown无序列表（-），每个选项必须是一个完整的用户会问的问题，且能被前端直接点击触发新对话。问题需聚焦在预防、饮食、就医等具体行动上。生成的问题必须是知识库里的文献能回答的，或者至少能给出建议的。）

【篇幅上限】
- 「核心结论」列表 **3~5 条，最多 5 条**，总长度不超过 5 行。
- **不要**输出「文献数据说明」「专家行动建议」这两节——用户只问了这一个方面。
- 整个回答控制在 30 秒内读完。
"""

_STRUCT_FULL = """\
【篇幅上限（直接决定响应速度，务必遵守）】
- 「专家核心解答」**不超过 3 句话**：先给结论与数值，不要展开铺陈。
- 「专家行动建议」**不超过 5 条**，每条一句话，用 Markdown 无序列表（-）。
- 回答里若出现表格，**数据行不超过 8 行**；超过就只保留最相关的 8 行，并在表下注明「仅列出前 8 项」。
- 不要在不同章节里重复同一件事。整体控制在让人一分钟内读完。

【输出结构规定（严格按照以下Markdown生成）】
### 专家核心解答
（有数值说数值，没数值说趋势，并安抚用户。）

### 文献数据说明（大白话版）
（将枯燥的统计数据翻译成白话文，说明研究来源和背景。）

### 专家行动建议
（这是重点！根据该民族和疾病的特征，结合文献中的危险因素，给出具体的筛查、饮食、就医建议。例如：“既然文献提到白族男性风险较高，建议男性同胞每年查一次空腹血糖；同时注意少吃高盐腌制食品。”）

### 证据来源
（一句话：依据《具体文献名》——必须写证据里「来源文档：《…》」的具体书名，不能写「现有研究」这类笼统说法。）

### 适用范围
（一句话：该数据适用于[研究地区][年龄范围][人群特征]。从证据里找研究地区、年龄范围、样本量、研究年份；证据未写明时，必须写「该文献未明确说明研究地区/年龄范围，适用范围待补充」。）

### 使用边界
（一句话：该数据不适用于[什么人群/地区]，原因：[说明]。从证据的样本范围、研究地区、局限推断；证据未写明边界时，必须写「该文献未明确说明使用边界，建议结合个人实际情况咨询专业医生」。）

### 您可能还想问
（基于当前检索到的文献和用户可能的困惑，生成3个追问选项。必须使用Markdown无序列表（-），每个选项必须是一个完整的用户会问的问题，且能被前端直接点击触发新对话。问题需聚焦在预防、饮食、就医等具体行动上。生成的问题必须是知识库里的文献能回答的，或者至少能给出建议的。）"""

# 兜底意图：不指向某个方面，走综合模式。overview 是对话页的泛化意图，
# all 是高级检索里那个「患病情况」兜底选项——两者行为一致。
_FULL_MODE_INTENTS = ("overview", "all")


def _system_prompt(intent: str | None) -> str:
    """按意图拼系统提示词：具体方面走聚焦模式，兜底档走综合模式。

    拼两种结构而不是把两套都塞进去：LLM 一次只该看到一套结构，
    同时看到「只答一个方面」和「写满四节」两条指令，它必然左右摇摆。
    """
    focused = bool(intent) and intent not in _FULL_MODE_INTENTS
    return _COMMON_RULES + (_STRUCT_FOCUSED if focused else _STRUCT_FULL)


def _sanitize_structured(parsed: dict) -> dict:
    """校验并清洗 LLM 输出的 format / table / chart，非法数据一律降级为 text"""
    fmt = parsed.get("format") if isinstance(parsed.get("format"), str) else None
    fmt = fmt if fmt in VALID_FORMATS else "text"
    table = parsed.get("table")
    chart = parsed.get("chart")

    # 表格校验：columns 非空字符串列表；rows 为二维字符串列表（允许数值字符串），行长度不作硬性要求
    if fmt == "table":
        ok = (isinstance(table, dict)
              and isinstance(table.get("columns"), list) and table["columns"]
              and all(isinstance(c, str) and c.strip() for c in table["columns"])
              and isinstance(table.get("rows"), list) and table["rows"]
              and all(isinstance(r, list) and all(isinstance(c, str) for c in r)
                      for r in table["rows"]))
        if not ok:
            fmt, table = "text", None

    # 图表校验：data 为非空 {label, value} 列表，value 必须为有限数值
    if fmt == "chart":
        ok = (isinstance(chart, dict)
              and isinstance(chart.get("data"), list) and len(chart["data"]) >= 2
              and all(isinstance(d, dict) and isinstance(d.get("label"), str) and d.get("label")
                      and isinstance(d.get("value"), (int, float)) and str(d["value"]) not in ("nan", "inf")
                      for d in chart["data"]))
        if not ok:
            fmt, chart = "text", None

    if fmt != "table":
        table = None
    if fmt != "chart":
        chart = None
    return {"format": fmt, "table": table, "chart": chart}


def _refine_eth_text(text: str, eth: str | None) -> str:
    """多民族同段切片（如「傣族：… 哈尼族：… 白族：…」）中只保留所问民族的子段，
    避免把其他民族内容混入回答。仅当切片确实含所问民族、且存在多个「X族：」锚点时才切分。"""
    if not (eth and eth in (text or "")):
        return text or ""
    bounds = [m.start() for m in re.finditer(r"(?:^|\s)([一-龥]{1,4}族)[:：]", text or "")]
    if len(bounds) < 2:
        return text or ""
    segs = []
    for k, b in enumerate(bounds):
        e = bounds[k + 1] if k + 1 < len(bounds) else len(text)
        segs.append(text[b:e])
    kept = [s for s in segs if eth in s]
    return "".join(kept) if kept else (text or "")


_LEVEL_LABEL = {
    "official": "官方权威资料",
    "web_crawl": "网页抓取",
    "user_upload": "用户上传",
}


def _source_tag(e: Entry) -> str:
    """
    证据行首的来源标签。

    三种等级**都**标出来，而不是只标非官方的——格式统一，大模型才容易按系统提示词里的
    规则区分措辞；只标异常项会让「官方」变成隐式默认，模型反而容易忽略这条轴。
    """
    label = _LEVEL_LABEL.get(e.source_level or "official", "官方权威资料")
    org = (e.source_org or "").strip()
    return f"【来源类型：{label}{' | 来源机构：' + org if org else ''}】"


# 单条证据送进提示词的字数上限。
#
# 切片本按约 600 字切，但**整段没有换行的长文**（一篇论文的摘要就是）会切成一千多字，
# 带长表格的资料更可能到几千字。长块塞进去有两个坏处：拖慢生成（prompt 越长越慢），
# 而且大模型容易整段复述进答案，答出来的东西又长又散。
_EVIDENCE_MAX_CHARS = 2000


def _cap_evidence(text: str, question: str = "") -> str:
    """把过长的证据截到上限，保留**最相关的窗口**而不是简单掐头。

    定位方式用问句里的二字片段（第一个能命中的）：问句短、噪声低，而它的开头通常是
    民族名——检索层也是按「民族名所在的那个子段」划相关范围的，同一套直觉。
    找不到就从头截。截断处都留省略标记，让模型知道这里断了，别把半截话当完整结论。
    """
    t = (text or "").strip()
    if len(t) <= _EVIDENCE_MAX_CHARS:
        return t
    q = re.sub(r"\s+", "", question or "")
    center = -1
    for n in range(len(q) - 1):
        i = t.find(q[n:n + 2])
        if i >= 0:
            center = i
            break
    if center < 0:
        return t[:_EVIDENCE_MAX_CHARS] + "……（本段过长，已截断）"
    half = _EVIDENCE_MAX_CHARS // 2
    start = max(0, min(center - half, len(t) - _EVIDENCE_MAX_CHARS))
    end = min(len(t), start + _EVIDENCE_MAX_CHARS)
    prefix = "……（前文略）" if start > 0 else ""
    suffix = "……（后文略）" if end < len(t) else ""
    return prefix + t[start:end] + suffix


def _evidence_blocks(entries: list[Entry], ethnicity: str | None = None,
                     question: str = "") -> str:
    lines = []
    for i, e in enumerate(entries, 1):
        tag = _source_tag(e)
        if e.source_type == "KB_EVIDENCE" and e.paper:
            p = e.paper
            lines.append(
                f"[{i}] {tag}来源论文：《{p.get('title','')}》"
                f"（{p.get('journal','')}, {p.get('year','')}; "
                f"{p.get('ethnicity','')}{p.get('disease','')}研究; "
                f"研究对象：{p.get('population','—')}）\n"
                f"    证据内容：{_cap_evidence(e.text, question)}"
            )
        else:
            page = f"第{e.page_no}页" if e.page_no else "页码未知"
            content = _refine_eth_text(_cap_evidence(e.text, question), ethnicity)
            lines.append(
                f"[{i}] {tag}来源文档：《{e.doc_title}》（{page}"
                f"{' · ' + e.section if e.section else ''}）\n"
                f"    证据内容：{content}"
            )
    return "\n".join(lines)


def _parse_json(text: str) -> dict | None:
    text = (text or "").strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    try:
        return json.loads(text)
    except Exception:
        match = re.search(r"\{[\s\S]*\}", text)
        if match:
            try:
                return json.loads(match.group(0))
            except Exception:
                return None
    return None


def _first_complete_sentence(text: str, min_len: int = 12) -> str:
    """提炼证据首条完整句：取第一个以句号/分号结尾的完整句。

    用户要求回答中不得出现省略号截断——此函数保证提取的是语义完整的句子
    （如「云南农村4229名60岁及以上老年人中……白族患病率最高」整句），
    而不是硬切 90 字加「…」。找不到完整句边界时返回整段。
    """
    text = (text or "").strip()
    if not text:
        return text
    for sep in ("。", "；", ";"):
        if sep in text:
            seg = text[:text.find(sep) + 1].strip()
            return seg if len(seg) >= min_len else text
    if "，" in text:
        seg = text.split("，", 1)[0] + "，"
        return seg if len(seg) >= 8 else text
    return text


async def _llm_answer(question: str, understanding: dict,
                      entries: list[Entry]) -> dict | None:
    if not llm.available():
        return None
    user = (
        f"已识别槽位：民族={eth_label(understanding.get('ethnicity')) or '未指定'}，"
        f"疾病={understanding.get('disease') or '未指定'}，"
        f"意图={understanding.get('intent') or '未指定'}\n\n"
        f"检索到的证据（共{len(entries)}条，编号[n]对应下方顺序。"
        f"这{len(entries)}条是一个整体，必须逐条通读后**综合使用**，不要只用其中一条）：\n"
        f"{_evidence_blocks(entries, understanding.get('ethnicity'), question)}\n\n"
        f"用户问题：{question}\n\n"
        "请基于以上【证据】按系统提示词规定的 Markdown 结构输出回答。"
        "注意：只围绕用户问题所问的疾病和意图回答；若证据不包含问题所问的方面，"
        "必须如实说明「现有研究证据未覆盖」，禁止推测或编造建议。"
    )
    content = await llm.chat(
        [{"role": "system", "content": _system_prompt((understanding or {}).get("intent"))}, {"role": "user", "content": user}],
        temperature=0.2, json_mode=False, max_tokens=4096)
    return _parse_markdown_answer(content, engine=llm.llm_engine())


def _parse_answer(content: str, engine: str) -> dict | None:
    """把 LLM 返回内容解析为统一 answer dict，非法结构返回 None"""
    parsed = _parse_json(content)
    if not parsed or not parsed.get("conclusion"):
        return None
    sections = parsed.get("sections") or []
    if not isinstance(sections, list):
        sections = []
    structured = _sanitize_structured(parsed)
    return {
        "conclusion": str(parsed.get("conclusion", "")),
        "sections": [
            {"title": str(s.get("title", "")), "content": str(s.get("content", ""))}
            for s in sections if isinstance(s, dict)
        ],
        "applicable": str(parsed.get("applicable", "") or "—"),
        "cautions": str(parsed.get("cautions", "") or "—"),
        "actions": str(parsed.get("actions", "") or ""),
        "format": structured["format"],
        "table": structured["table"],
        "chart": structured["chart"],
        "engine": engine,
    }


def _parse_markdown_answer(content: str, engine: str) -> dict | None:
    """把 LLM 返回的 Markdown 结构（专家核心解答/文献数据说明/专家行动建议/
    证据来源/适用范围/使用边界/您可能还想问）解析为统一 answer dict；核心结构缺失时返回 None。

    标题按关键词匹配，并保留旧标题（核心结论/详细解读/证据来源与适用边界/衍生探讨，
    以及「文献来源与专家提醒」整段）作兼容键，使历史会话里存下的旧结构回答仍能正常回放。
    注：早期提示词在这些标题前带 emoji（👨‍⚕️/💬），关键词匹配天然兼容，无需特殊处理。"""
    text = (content or "").strip()
    text = re.sub(r"^```(?:markdown)?\s*|\s*```$", "", text, flags=re.S).strip()
    if not text:
        return None

    # 按「### 标题」切分各模块
    blocks: dict[str, str] = {}
    cur: str | None = None
    buf: list[str] = []
    for line in text.splitlines():
        m = re.match(r"^###\s*(.+)", line)
        if m:
            if cur is not None:
                blocks[cur] = "\n".join(buf).strip()
            cur = m.group(1).strip()
            buf = []
        else:
            buf.append(line)
    if cur is not None:
        blocks[cur] = "\n".join(buf).strip()

    def _get(*keys: str) -> str:
        for k in keys:
            for h, v in blocks.items():
                if k in h:
                    return v
        return ""

    conclusion = _get("专家核心解答", "核心结论")
    detailed = _get("文献数据说明", "详细解读")
    actions = _get("专家行动建议", "行动建议")

    # 新三件套：证据来源 / 适用范围 / 使用边界是三个独立模块，直接取整段。
    sources = _get("证据来源").strip()
    scope_text = _get("适用范围").strip()
    boundary = _get("使用边界").strip()

    # 旧结构兼容：整段是一个「文献来源与专家提醒」模块，逐行拆出
    # 「- **适用人群**：…」这类条目。新模块缺失时用它兜底。
    old_scope = _get("文献来源与专家提醒", "专家提醒", "文献来源", "适用边界")
    applicable = ""
    time_region = ""
    cautions = ""
    scope_prose: list[str] = []
    for raw in old_scope.splitlines():
        s = raw.strip()
        if not s:
            continue
        if s.startswith("-"):
            s = s[1:].strip()
        if s.startswith("**适用人群**"):
            applicable = s[len("**适用人群**"):].strip().lstrip("：:").strip()
        elif s.startswith("**研究时间/地区**"):
            time_region = s[len("**研究时间/地区**"):].strip().lstrip("：:").strip()
        elif s.startswith("**局限性与注意**"):
            cautions = s[len("**局限性与注意**"):].strip().lstrip("：:").strip()
        elif s.startswith("**特别提醒**"):
            cautions = s[len("**特别提醒**"):].strip().lstrip("：:").strip()
        else:
            scope_prose.append(s)
    if not cautions and scope_prose:
        cautions = "\n".join(scope_prose).strip()

    # 新模块优先覆盖旧结构拆出的字段：适用范围 > 旧「适用人群」，使用边界 > 旧「特别提醒」。
    if scope_text:
        applicable = scope_text
    if boundary:
        cautions = boundary

    follow_ups: list[str] = []
    for raw in _get("您可能还想问", "衍生探讨").splitlines():
        s = raw.strip()
        if not s:
            continue
        s = re.sub(r"^[-*]\s*", "", s).strip()
        s = re.sub(r"^选项\d+[:：]\s*", "", s).strip()
        s = re.sub(r"^\d+[.、)）]\s*", "", s).strip()
        # 提示词里的示例写成「例如：…」，模型常原样照抄这个前缀，会一路显示到追问按钮上
        s = re.sub(r"^(?:例如|比如|例)[:：]\s*", "", s).strip()
        s = s.strip("“”\"'").strip()
        if s:
            follow_ups.append(s)

    if not conclusion and not detailed:
        return None

    return {
        "conclusion": conclusion,
        "detailed": detailed,
        "actions": actions,
        "sources": sources or "—",
        "scope": applicable or "—",
        "applicable": applicable or "—",
        "timeRegion": time_region or "未明确",
        "cautions": cautions or "—",
        "followUps": follow_ups[:3],
        "format": "text",
        "table": None,
        "chart": None,
        "engine": engine,
    }


async def _llm_revise(question: str, understanding: dict, entries: list[Entry],
                      draft: dict, missing: list[int]) -> dict | None:
    """LLM 修正：把已生成的草稿与遗漏证据一起反馈，要求综合全部证据重写。

    用于回答生成后校验发现证据编号缺失时，让 LLM 把遗漏证据的关键数据
    提炼进「依据」分节或表格/图表，而不是靠程序截断原文硬贴。
    """
    if not llm.available() or not missing:
        return None
    missing_entries = [entries[i - 1] for i in missing if 1 <= i <= len(entries)]
    if not missing_entries:
        return None
    user = (
        f"已识别槽位：民族={eth_label(understanding.get('ethnicity')) or '未指定'}，"
        f"疾病={understanding.get('disease') or '未指定'}，"
        f"意图={understanding.get('intent') or '未指定'}\n\n"
        f"以下是已生成的回答草稿（JSON）：\n{json.dumps(draft, ensure_ascii=False)}\n\n"
        f"当前草稿遗漏了以下证据编号：{missing}，其完整内容为：\n"
        f"{_evidence_blocks(missing_entries, understanding.get("ethnicity"), question)}\n\n"
        f"用户问题：{question}\n\n"
        "请综合全部证据重新输出完整 JSON 回答：专家核心解答（conclusion）保持综合总结形态；"
        "把遗漏证据的关键数据（患病率、样本量、人群对比结论等）提炼进「依据」分节（sections）或"
        "表格/图表中，正文必须引用全部证据编号 [n]，禁止使用省略号（…）截断原文，禁止整段粘贴证据原文。"
    )
    content = await llm.chat(
        [{"role": "system", "content": _system_prompt((understanding or {}).get("intent"))}, {"role": "user", "content": user}],
        temperature=0.2, json_mode=True, max_tokens=4096)
    return _parse_answer(content, engine=llm.llm_engine())


def _append_missing_sentences(answer: dict, entries: list[Entry],
                              missing: list[int]) -> dict:
    """兜底（无 LLM 或修正失败）：把遗漏证据的完整关键句提炼追加到「依据」分节。

    不使用省略号、不截断原文；句子不完整时直接采用证据全文（证据通常较短）。
    追加到 sections 而非专家核心解答——该节保持「综合总结」形态，不逐条贴原文。
    """
    parts = []
    for idx in missing:
        if not (1 <= idx <= len(entries)):
            continue
        e = entries[idx - 1]
        sentence = _first_complete_sentence(e.text)
        src = (e.paper.get("title") if e.paper and e.source_type == "KB_EVIDENCE"
               else e.doc_title)
        src = (src or "该研究").strip()
        parts.append(f"[{idx}] {src}：{sentence}")
    if not parts:
        return answer
    sections = list(answer.get("sections") or [])
    merged = "\n".join(parts)
    # 不另起「补充依据」之类的离题标题：把遗漏证据的关键句合并进已有的最后一个相关小节，
    # 保持回答围绕所问民族/疾病/意图，避免出现堆砌离题材料的独立小节。
    if sections:
        last = sections[-1]
        last["content"] = (last.get("content") or "") + "\n" + merged
    else:
        sections.append({"title": "相关依据", "content": merged})
    answer["sections"] = sections
    return answer


def _percent_range(entries: list[Entry]) -> str | None:
    """跨证据提炼百分比数值范围（仅限 prevalence 主题，避免混入 OR 置信区间等）。
    找不到可对比数值时返回 None（由调用方给出概括性描述）。"""
    values: list[float] = []
    for e in entries:
        topic = (e.topic or "").lower()
        if topic not in ("prevalence", "患病情况"):
            continue
        for m in re.finditer(r"(\d+(?:\.\d+)?)\s*%", e.text or ""):
            try:
                values.append(float(m.group(1)))
            except ValueError:
                continue
    if not values:
        return None
    lo, hi = min(values), max(values)
    if len(values) == 1:
        return f"关键比例数值为 {lo:g}%"
    return f"关键比例数值大致在 {lo:g}%~{hi:g}% 之间"


def _template_answer(question: str, understanding: dict,
                     entries: list[Entry]) -> dict:
    """模板兜底（无 Key / LLM 失败）：按 Markdown 结构字段组装保守回答"""
    eth = eth_label(understanding.get("ethnicity")).strip()
    dis = (understanding.get("disease") or "").strip()

    parts = []
    for i, e in enumerate(entries, 1):
        sentence = _first_complete_sentence(e.text)
        src = (e.paper.get("title") if e.paper and e.source_type == "KB_EVIDENCE"
               else e.doc_title)
        parts.append(f"[{i}] {src or '该研究'}：{sentence}")

    n = len(entries)
    pct = _percent_range(entries)
    summary = [pct] if pct else []
    summary.append(f"现有证据主要围绕「{eth}{dis}」的相关研究。")
    conclusion = (
        f"综合检索到的 {n} 条研究证据，针对「{eth}{dis}」："
        + "；".join(s for s in summary if s)
    )

    follow_ups = []
    if eth and dis:
        follow_ups = [
            f"{eth}{dis}的常见危险因素有哪些？",
            f"{eth}{dis}在饮食和生活上有哪些需要注意的地方？",
            f"{eth}{dis}与其他民族相比，发病或诊疗上有何差异？",
        ]

    # 证据来源：取第一条证据的文献名；模板兜底没有结构化适用范围，老实写「未明确」。
    first_src = ""
    for e in entries:
        if e.paper and e.source_type == "KB_EVIDENCE" and e.paper.get("title"):
            first_src = e.paper.get("title")
            break
        if e.doc_title:
            first_src = e.doc_title
            break

    # 模板兜底也给行动建议（不依赖 LLM）：只说「去筛查、听医生的」，不引入文献外的医学判断
    actions = (
        f"建议您带着对「{eth}{dis}」的关注，到当地乡镇卫生院或社区卫生服务中心做一次基础筛查，"
        "并请医生结合您的家族史与体检指标给出个体化判断。"
        if eth and dis else
        "建议您就关心的健康问题，到当地基层医疗机构做一次基础筛查，并请医生给出个体化判断。"
    )

    return {
        "conclusion": conclusion[:1500],
        "detailed": ("；".join(parts)[:4000]
                     if parts else "现有证据暂未检索到可直接支撑的详细信息。"),
        "actions": actions,
        "sources": f"依据《{first_src}》" if first_src else "—",
        "scope": "该文献未明确说明研究地区/年龄范围，适用范围待补充。",
        "applicable": "以上结论均来自公开发表的研究资料，适用人群以各证据来源的研究样本为准。",
        "timeRegion": "未明确",
        "cautions": "各证据均为观察性研究结果，存在样本范围与研究设计局限；本回答不构成个体诊疗建议。",
        "followUps": follow_ups,
        "format": "text",
        "table": None,
        "chart": None,
        "engine": "template",
    }


def _fix_literal_markers(answer: dict, n_entries: int) -> dict:
    """把 LLM 偶尔字面输出的「[n]」占位符替换成真实证据编号（按出现顺序循环 1..n）。"""
    if n_entries <= 0:
        return answer

    def _fix(text):
        if not isinstance(text, str) or "[n]" not in text:
            return text
        counter = {"i": 0}

        def _repl(_m):
            counter["i"] += 1
            return f"[{(counter['i'] - 1) % n_entries + 1}]"
        return re.sub(r"\[n\]", _repl, text)

    answer["conclusion"] = _fix(answer.get("conclusion"))
    for s in answer.get("sections") or []:
        if isinstance(s, dict):
            s["content"] = _fix(s.get("content"))
            s["title"] = _fix(s.get("title"))
    return answer


# ---------- 生成后校验：紧扣问题疾病 + 数字必须有据 ----------
# 与问题疾病无关的其他疾病（问糖尿病时不得在回答里出现这些疾病的统计数据）。
# 注意：不含「代谢综合征/CKM」等与糖尿病直接关联的疾病名，避免误删关联论述。
_OFFTOPIC_DISEASES = [
    "高血压", "心脏瓣膜病", "脂肪肝", "NAFLD", "痛风", "高尿酸血症",
    "视网膜病变", "脑卒中", "冠心病", "高脂血症", "骨质疏松",
]


def _offtopic_disease_hits(text: str, question_disease: str | None) -> list[str]:
    """检测文本中出现的问题疾病以外的疾病名。"""
    if not text:
        return []
    qd = question_disease or ""
    return [d for d in _OFFTOPIC_DISEASES if d in text and d not in qd]


# 其他民族名（用于「只回答所问民族」的兜底清洗，避免回答混入他族内容）
_OTHER_ETHNICS = [
    "傣族", "哈尼族", "汉族", "蒙古族", "维吾尔族", "藏族", "回族", "苗族", "彝族",
    "景颇族", "傈僳族", "佤族", "普米族", "布朗族", "纳西族", "土家族", "满族",
    "布依族", "壮族", "畲族", "哈萨克族",
]


def _offtopic_ethnicity_hits(text: str, question_ethnicity: str | None) -> list[str]:
    """检测文本中出现的、非所问民族的其他民族名（用于「问白族不答傣族/蒙古族」兜底）。

    泛民族查询不做此判定：问题本就不指向单一民族，答案里出现白族/傣族/普米族等
    正是**应该**有的内容（泛民族研究本身就在讲各个民族）。若不跳过，这些句子会被
    当成「混入他族」整句删掉——表现为回答里「研究发现：」后面一片空白。
    """
    if not text or not question_ethnicity:
        return []
    qe = question_ethnicity.strip()
    if qe == ETHNICITY_GENERAL:
        return []
    return [e for e in _OTHER_ETHNICS if e != qe and e in text]


def _strip_md_table_lines(text: str) -> str:
    """去掉 Markdown 表格行（含 | 的行），返回纯正文。

    用户明确要求「对比数据表格原样保留」——表格里出现他族是合法的数据对比，
    民族越界检测时不应把表格行当作「混入他族内容」而清洗掉。
    """
    if not text:
        return text
    return "\n".join(line for line in text.splitlines() if "|" not in line)


def _evidence_comparison_tables(entries: list[Entry],
                                ethnicity: str | None) -> list[str]:
    """把检索证据（与送 LLM 相同口径）里的对比表格提取出来。"""
    text = "\n".join(_refine_eth_text(e.text or "", ethnicity) for e in entries)
    return _reconstruct_md_tables(text)


def _table_data_rows(table_str: str) -> list[list[str]]:
    """解析重建后的表格字符串，返回数据行（跳过表头与分隔行），每行为单元格列表。"""
    rows: list[list[str]] = []
    saw_header = False
    for line in table_str.splitlines():
        s = line.strip()
        if not s.startswith("|"):
            continue
        cells = [c for c in s.strip("|").split("|") if c.strip() != ""]
        cells = [c.strip() for c in cells]
        if not cells:
            continue
        if all(_TABLE_SEP_RE.match(c) for c in cells):
            continue  # 分隔行
        if not saw_header:
            saw_header = True  # 首行非分隔 = 表头
            continue
        rows.append(cells)
    return rows


def _table_leads(text: str) -> set[str]:
    """提取一段文本里所有表格的数据行首列（民族名），用于判断表格是否完整。"""
    leads: set[str] = set()
    for t in _reconstruct_md_tables(text):
        for r in _table_data_rows(t):
            if r and r[0]:
                leads.add(r[0])
    return leads


def _ensure_comparison_table(answer: dict, entries: list[Entry],
                             understanding: dict) -> dict:
    """确定性兜底：把检索证据里的原始对比数据表格原样补进「文献数据说明」下方。

    用户要求「检索结果有原始对比数据表格时原样保留，不要转换成文字描述」。
    仅靠提示词不可靠——LLM 常把多民族对比表截断成只剩所问民族一行。此处从证据
    重建完整表格：若文献数据说明中的表格缺少其中任一行，则去掉残缺表格行后补全完整表格。
    """
    eth = (understanding or {}).get("ethnicity")
    disease = (understanding or {}).get("disease")
    tables = _evidence_comparison_tables(entries, eth)
    # 只注入与所问疾病相关的对比表（表头/内容含疾病名），避免把同一切片里
    # 其他疾病的表格混入回答（如问高血压却注入心脏瓣膜病表）。
    if disease:
        tables = [t for t in tables if disease in t]
    else:
        tables = []
    if not tables:
        return answer
    detailed = (answer.get("detailed") or "").strip()
    present = _table_leads(detailed)
    for t in tables:
        rows = _table_data_rows(t)
        if len(rows) < 2:
            continue
        leads = [r[0] for r in rows if r and r[0]]
        if not leads:
            continue
        if detailed and set(leads) <= present:
            continue
        clean = _strip_md_table_lines(detailed).strip()
        answer["detailed"] = (clean + "\n\n" + t).strip() if clean else t
        break
    return answer


def _body_text_fields(answer: dict) -> list[str]:
    """回答正文各文本字段（不含「您可能还想问」追问——追问允许跨民族/疾病对比，不参与越界清洗）。"""
    fields = [answer.get("conclusion"), answer.get("detailed"), answer.get("actions"),
              answer.get("sources"), answer.get("scope"),
              answer.get("applicable"), answer.get("timeRegion"),
              answer.get("cautions")]
    for s in answer.get("sections") or []:
        if isinstance(s, dict):
            fields.append(s.get("title"))
            fields.append(s.get("content"))
    return [x for x in fields if isinstance(x, str) and x]


def _unsupported_percents(answer: dict, entries: list[Entry]) -> set[str]:
    """回答中出现的百分比数值若不在任何证据原文中，视为无据数字（LLM 编造）。"""
    ev_text = "".join(e.text or "" for e in entries)
    found: set[str] = set()
    for t in _body_text_fields(answer):
        for m in re.finditer(r"(\d+(?:\.\d+)?)\s*%", t):
            if m.group(1) not in ev_text:
                found.add(m.group(1) + "%")
    return found


def _answer_issues(answer: dict, entries: list[Entry],
                   understanding: dict) -> list[str]:
    """收集回答的越界问题（其他疾病混入 / 无据数字），供修正与兜底。"""
    issues: list[str] = []
    texts = _body_text_fields(answer)
    qd = (understanding or {}).get("disease")
    if qd:
        hits: set[str] = set()
        for t in texts:
            hits.update(_offtopic_disease_hits(t, qd))
        if hits:
            issues.append(
                f"回答中出现了与问题疾病「{qd}」无关的其他疾病内容：{'、'.join(sorted(hits))}，"
                "必须全部删除（除非证据直接论述其与问题疾病的关联且服务于回答问题）")
    qe = (understanding or {}).get("ethnicity")
    if qe:
        ehits: set[str] = set()
        for t in texts:
            ehits.update(_offtopic_ethnicity_hits(_strip_md_table_lines(t), qe))
        if ehits:
            issues.append(
                f"回答中出现了与问题民族「{qe}」无关的其他民族内容：{'、'.join(sorted(ehits))}，"
                "必须全部删除（只围绕所问民族作答，除非是证据直接给出的、服务于回答问题的对比）")
    unsup = _unsupported_percents(answer, entries)
    if unsup:
        issues.append(
            f"回答中出现了证据原文里不存在的数字：{'、'.join(sorted(unsup))}，"
            "必须删除或改为证据中的原始数值")
    return issues


def _strip_sentences(text: str, keep) -> str:
    """按句切分文本，仅保留 keep(sentence) 为真的句子。"""
    if not text:
        return text
    parts = [p for p in re.split(r"(?<=[。！？\n])", text) if p.strip()]
    kept = [p for p in parts if keep(p)]
    return "".join(kept) or text


def _force_on_topic(answer: dict, entries: list[Entry],
                    understanding: dict) -> dict:
    """程序化兜底（无 LLM / LLM 修正失败）：删除越界句子与含无据数字的句子。

    仅作用于回答正文（专家核心解答/文献数据说明/专家提醒与适用边界）；「您可能还想问」
    追问允许跨民族/疾病对比，不受越界清洗。"""
    qd = (understanding or {}).get("disease")
    ev_text = "".join(e.text or "" for e in entries)

    def _clean(text: str) -> str:
        if not text:
            return text

        def ok(sent: str) -> bool:
            if qd and _offtopic_disease_hits(sent, qd):
                return False
            qe = (understanding or {}).get("ethnicity")
            # 表格行（含 |）是用户要求保留的对比数据，不参与民族越界清洗
            if qe and "|" not in sent and _offtopic_ethnicity_hits(sent, qe) and qe not in sent:
                return False
            for m in re.finditer(r"(\d+(?:\.\d+)?)\s*%", sent):
                if m.group(1) not in ev_text:
                    return False
            return True
        return _strip_sentences(text, ok)

    for key in ("conclusion", "detailed", "actions", "sources", "scope",
                "applicable", "timeRegion", "cautions"):
        answer[key] = _clean(answer.get(key))
    sections = []
    for s in answer.get("sections") or []:
        if not isinstance(s, dict):
            continue
        title, content = s.get("title") or "", s.get("content") or ""
        if qd and (_offtopic_disease_hits(title, qd)
                   and _offtopic_disease_hits(content, qd)):
            continue
        cleaned = _clean(content)
        if cleaned.strip():
            sections.append({"title": title, "content": cleaned})
    answer["sections"] = sections
    return answer


async def _llm_on_topic_revise(question: str, understanding: dict,
                               entries: list[Entry], draft: dict,
                               issues: list[str]) -> dict | None:
    """LLM 修正：反馈越界问题，要求删除无关疾病内容与无据数字后重写。"""
    if not llm.available() or not issues:
        return None
    user = (
        f"已识别槽位：民族={eth_label(understanding.get('ethnicity')) or '未指定'}，"
        f"疾病={understanding.get('disease') or '未指定'}，"
        f"意图={understanding.get('intent') or '未指定'}\n\n"
        f"用户问题：{question}\n\n"
        f"以下是已生成的回答草稿（Markdown）：\n{json.dumps(draft, ensure_ascii=False)}\n\n"
        f"该草稿存在以下问题，必须全部修复：\n"
        + "\n".join(f"- {i}" for i in issues)
        + f"\n\n完整证据内容（编号对应 [n]）：\n{_evidence_blocks(entries, understanding.get('ethnicity'), question)}\n\n"
        "请按系统提示词规定的 Markdown 结构重新输出完整回答：删除所有与问题疾病无关的其他疾病内容；"
        "删除证据中没有依据的数字与建议；修复后若证据不足以支撑回答，在专家核心解答中如实说明"
        "「现有研究证据未覆盖该问题」，禁止推测或编造。"
    )
    content = await llm.chat(
        [{"role": "system", "content": _system_prompt((understanding or {}).get("intent"))}, {"role": "user", "content": user}],
        temperature=0.2, json_mode=False, max_tokens=4096)
    return _parse_markdown_answer(content, engine=llm.llm_engine())


async def ensure_on_topic(answer: dict, entries: list[Entry], question: str,
                         understanding: dict | None = None,
                         _depth: int = 0) -> dict:
    """确保回答紧扣问题疾病、数字有据可依。

    用户明确要求：问某疾病就不要出现其他疾病的情况；所有回答都要有具体证据来源。
    LLM 有时会掺入证据里其他疾病的患病率（问糖尿病答高血压/脂肪肝），或输出证据里
    不存在的数字。此处生成后校验：
      1. 有 LLM：把越界问题反馈给 LLM 重写（递归校验一次）；
      2. 无 LLM 或修正失败：程序化删除越界句子与含无据数字的句子。
    """
    understanding = understanding or {}
    issues = _answer_issues(answer, entries, understanding)
    if not issues:
        return answer
    if _depth == 0:
        revised = await _llm_on_topic_revise(question, understanding, entries, answer, issues)
        if revised:
            return await ensure_on_topic(
                revised, entries, question, understanding, _depth=1)
    return _force_on_topic(answer, entries, understanding)


async def generate_answer(question: str, understanding: dict,
                          entries: list[Entry]) -> dict:
    answer: dict | None = None
    try:
        result = await _llm_answer(question, understanding, entries)
        if result:
            answer = await ensure_on_topic(result, entries, question, understanding)
        else:
            logger.warning("_llm_answer returned None (llm_available=%s)", llm.available())
    except Exception as e:
        logger.exception("_llm_answer raised: %r", e)
    if answer is None:
        answer = _template_answer(question, understanding, entries)
        answer = await ensure_on_topic(answer, entries, question, understanding)
    return _ensure_comparison_table(answer, entries, understanding)


async def generate_fallback_answer(understanding: dict,
                                   available_diseases: list[str]) -> dict | None:
    """检索为空时的 LLM 兜底回答：诚实告知「数据暂缺」，并用「您可能还想问」
    引导用户询问其他有数据的疾病（available_diseases 为该民族在知识库中已有数据的疾病）。

    返回 None 表示 LLM 不可用或失败，由调用方退回 _no_evidence_answer 模板。
    """
    if not llm.available():
        return None
    eth = (understanding.get("ethnicity") or "").strip()
    dis = (understanding.get("disease") or "").strip()
    intent = (understanding.get("intent") or "").strip()
    aspect = TOPIC_LABELS.get(intent) or understanding.get("metric") or ""
    # 泛民族的哨兵值 "GENERAL" 不能直接出现在给用户/模型的文案里
    eth_show = eth_label(eth) or "未指定"
    topic = f"{eth_show}{dis}".strip() or "该问题"
    scope = f"【{eth_show}】的【{dis}】" + (f"在「{aspect}」方面" if aspect else "")
    parts = [
        f"已识别槽位：民族={eth_show}，疾病={dis or '未指定'}，"
        f"询问方面={aspect or '未指定'}。",
        "",
        f"系统未检索到 {scope} 的可靠已审核资料。",
        "请如实告知用户：目前文献未覆盖该民族该疾病的这一方面，并建议咨询专业医生；"
        "不要拿其他方面（如患病率）的数据替代回答，也不要凭常识编造饮食/预防建议。",
        "随后用【您可能还想问】引导用户询问其他相关疾病。",
    ]
    if available_diseases:
        parts.append(f"知识库中「{eth}」目前已有数据的疾病：{'、'.join(available_diseases)}。")
        parts.append(f"（您可能还想问 示例：目前有{eth}{available_diseases[0]}的数据，请问需要了解吗？）")
    parts.append("")
    parts.append(
        f"请按系统提示词规定的 Markdown 结构输出回答：专家核心解答与文献数据说明都要"
        f"如实说明「{topic}」数据暂缺，禁止编造任何数据、疾病名称或研究结论。"
    )
    user = "\n".join(parts)
    content = await llm.chat(
        [{"role": "system", "content": _system_prompt((understanding or {}).get("intent"))}, {"role": "user", "content": user}],
        temperature=0.2, json_mode=False, max_tokens=4096)
    return _parse_markdown_answer(content, engine=llm.llm_engine())


async def ensure_citation_coverage(answer: dict, entries: list[Entry],
                                   question: str = "", understanding: dict | None = None,
                                   _depth: int = 0) -> dict:
    """确保回答正文覆盖全部证据编号 [n]，专家核心解答保持总结、依据分节完整。

    用户要求「证据来源列了哪些，回答就要涉及哪些」——LLM 有时会遗漏个别证据，
    此处在生成后强制补齐。补齐策略（杜绝省略号截断与整段贴原文）：
      1. 有 LLM：把草稿 + 遗漏证据反馈给 LLM，要求把遗漏数据提炼进依据分节（递归校验一次）；
      2. 无 LLM 或修正失败：把遗漏证据的完整关键句（不截断、无省略号）合并进已有的相关小节，
         不再另起「补充依据」之类的独立标题，专家核心解答始终保持综合总结形态，不逐条贴原文。
    """
    if not entries:
        return answer
    all_text = answer.get("conclusion") or ""
    for s in answer.get("sections") or []:
        all_text += " " + (s.get("content") or "")
    cited = set()
    for m in re.findall(r"\[(\d+)\]", all_text):
        try:
            cited.add(int(m))
        except ValueError:
            pass
    covered = set(range(1, len(entries) + 1))
    missing = sorted(covered - cited)
    if not missing:
        return answer

    understanding = understanding or {}
    if _depth == 0:
        revised = await _llm_revise(question, understanding, entries, answer, missing)
        if revised:
            return await ensure_citation_coverage(
                revised, entries, question, understanding, _depth=1)
    return _append_missing_sentences(answer, entries, missing)
