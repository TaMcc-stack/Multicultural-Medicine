"""结构化字段提取：把一篇文献的正文/摘要整理成「民族 × 指标 = 数值」的数据行。

**与 evidence_extract 的分工**（两个都在抽东西，容易混）：
  · `evidence_extract` 抽的是「**可溯源的证据句**」，直接进检索索引（KB_EVIDENCE）；
  · 这里抽的是「**表格里的一行**」（民族/疾病/指标类型/数值/来源/年份/地区/样本量/备注），
    供「文献补录工作台」拼成《数据资料》那样的汇编文档。
    这些行**不进索引**——最终入索引的是工作台拼好的那份文档。

**为什么一篇要出多行**：一段摘要常写成「白族16.0%、傣族7.6%、哈尼族5.0%」，
一行只装得下一个民族，硬塞进一个「数值」格后续还得人工拆。所以每个
「民族 × 指标」组合各占一行。

**为什么数值要求照抄**：这些数字会成为回答里的「依据」。任何换算、四舍五入、
推算都会让溯源链失真——用户拿着回答去核对原文时会对不上。
"""
import json
import logging
import re

from . import llm

logger = logging.getLogger(__name__)

# 单次喂给 LLM 的正文上限。摘要几百字，整篇论文也远不到；超了截断而不是分批——
# 分批会让同一张表的表头与数据行分到两次调用里，反而更容易抽错。
_MAX_INPUT = 12000

_SYSTEM_PROMPT = (
    "你是医学文献数据整理助手。任务是把一篇研究文献的正文或摘要，整理成三部分："
    "(a) 数据行 rows；(b) 危险因素列表 risks；(c) 专家建议列表 advice。请严格遵守：\n"
    "1. rows 里一行 = 一个「民族 + 指标 + 数值」的组合。一段文字里同时报了多个民族"
    "或多个指标时，必须拆成多行——例如「白族16.0%、傣族7.6%、哈尼族5.0%」要拆成三行。\n"
    "2. 数值必须照抄原文（含百分号、单位、OR/RR 等前缀），不得换算、不得四舍五入、不得推算。\n"
    "3. 原文没有的信息填 null，绝对不要编造。宁可留空。特别注意 **source（来源文献）**：\n"
    "   只有原文里真的写了期刊名或作者时才填，否则一律 null —— 编一个期刊名出来会让整份资料\n"
    "   看起来有据可查，比留空有害得多。例如原文只写「摘要：……白族患病率16.0%……」而没有期刊名，\n"
    "   则 source 必须是 null，不许写「中华流行病学杂志」这类常见刊名。\n"
    "4. risks：原文提到的危险因素 / 影响因素，逐条列出（如「糖尿病家族史（OR=2.88）」）。"
    "只摘录原文写了的，原文没提就输出空数组。\n"
    "5. advice：原文提到的防治建议 / 干预方向，逐条列出。**只摘录原文里写的**——"
    "你是整理者不是医生，绝不要自己给出任何医疗建议、用药建议或诊疗方案；原文没写就输出空数组。\n"
    "6. 只提取数据与结论类内容；研究方法、统计口径说明、致谢、参考文献一律不提取。\n"
    "7. 只输出一个 JSON 对象，不要输出任何其他文字，格式：\n"
    '{"rows":[{"ethnicity":"白族","disease":"糖尿病","metric":"标化患病率","value":"16.0%",'
    '"source":"中华流行病学杂志","year":"2019","region":"云南省","ageRange":"≥18岁",'
    '"sampleSize":"1234","note":null}],"risks":["..."],"advice":["..."]}\n'
    "   没有可提取内容时输出 {\"rows\":[],\"risks\":[],\"advice\":[]}。\n"
    "   字段含义：metric=指标类型（患病率/发病率/OR值/等位基因频率/突变位点…）；"
    "source=来源文献（期刊名，或作者+年份；原文有才填）；year=发表年份；"
    "region=研究地区；ageRange=研究对象的年龄范围（如「≥18岁」「60岁以上」，原文有才填）；"
    "sampleSize=样本量；note=备注（如「标化率」「P<0.001」）。"
)

_FIELDS = ("ethnicity", "disease", "metric", "value",
           "source", "year", "region", "ageRange", "sampleSize", "note")

# 模型有时用这些字符串表示「没有」，一律收敛成 None
_NULLISH = {"", "null", "none", "n/a", "na", "-", "—", "无", "未提及", "未报告"}


def _clean(value: object) -> str | None:
    if value is None:
        return None
    s = str(value).strip()
    return None if s.lower() in _NULLISH or s in _NULLISH else s


def _clean_list(value: object) -> list[str]:
    """把模型给的字符串数组收敛成「去空、去重、保序」的列表。

    去重是必要的：模型列危险因素时经常把同一条换个说法列两遍，进了文档就是重复的列表项。
    """
    if not isinstance(value, list):
        return []
    out: list[str] = []
    for item in value:
        s = _clean(item)
        if s and s not in out:
            out.append(s)
    return out


def _parse_payload(content: str) -> tuple[list[dict], list[str], list[str]]:
    """从模型输出里取 JSON，返回 (rows, risks, advice)。

    json_mode 下通常就是纯 JSON，但它偶尔会裹一层代码块，所以两条路都试。
    """
    text = (content or "").strip()
    if not text:
        return [], [], []
    fenced = re.search(r"```(?:json)?\s*(.+?)```", text, re.S)
    if fenced:
        text = fenced.group(1).strip()
    obj: object = None
    try:
        obj = json.loads(text)
    except Exception:
        brace = re.search(r"\{.*\}", text, re.S)
        if brace:
            try:
                obj = json.loads(brace.group(0))
            except Exception:
                return [], [], []
    if not isinstance(obj, dict):
        return [], [], []
    rows: list[dict] = []
    raw = obj.get("rows")
    if isinstance(raw, list):
        for item in raw:
            if not isinstance(item, dict):
                continue
            row = {f: _clean(item.get(f)) for f in _FIELDS}
            # 一个数值都没有的行没有意义：不写数值的行不是数据行，是叙述
            if not row["value"]:
                continue
            rows.append(row)
    return rows, _clean_list(obj.get("risks")), _clean_list(obj.get("advice"))


# 「必须能在原文里找到」的字段。这几个是**出处类**信息，编造出来最有害——
# 一个假期刊名会让整份汇编看起来有据可查，比不写出处更糟。
#
# 为什么不能只靠提示词：实测确认模型会编。喂进一段完全没提期刊名的摘要，
# 它照样把 source 填成了「中华流行病学杂志」——那是它见过的常见刊名，不是原文里的。
# 所以这里再加一道**确定性**的核对：原文里找不到的，一律置空。
_MUST_APPEAR = ("source", "year", "region", "sampleSize")

_NUM_RE = re.compile(r"\d+(?:\.\d+)?")
# 归一化时去掉的排版符号：空白、中英标点、百分号。百分号要去掉，
# 否则「16.0%」在原文里的写法稍有差异就判不中。
_STRIP_RE = re.compile(r"[\s，。；：、（）()\[\]【】|·,.;:%％\-—－–/]")


def _norm(s: str) -> str:
    return _STRIP_RE.sub("", s or "")


def _drop_fabricated(row: dict, body_norm: str) -> bool:
    """把原文里找不到的字段置空；返回该行是否应当丢弃。

    数值单独处理：要求它的每个数字都能在原文里找到。编出来的数值尤其危险——
    它会成为回答里的「依据」。
    """
    for key in _MUST_APPEAR:
        v = row.get(key)
        if v and _norm(v) not in body_norm:
            row[key] = None
    nums = _NUM_RE.findall(row.get("value") or "")
    if nums and not all(_norm(n) in body_norm for n in nums):
        return True
    return False


async def extract_rows(text: str, hint_ethnicity: str | None = None,
                       hint_disease: str | None = None) -> dict:
    """抽取数据行；LLM 不可用或失败时返回空结果（best-effort）。

    返回 `{"rows": [...], "risks": [...], "advice": [...], "dropped": n}`。
    `dropped` 是**被丢弃的行数**——那些行的数值或出处对不上原文（见 `_drop_fabricated`）。
    把它们报出来而不是静默丢掉：管理员看到「3 行被丢弃」才知道模型抽歪了，
    而不是以为这篇文献只有 1 条数据。

    hint_* 是当前缺口已知的民族/疾病：原文只写「该人群」而没点名时，
    给模型一个参照，免得它把研究对象猜成别的民族。
    """
    empty = {"rows": [], "risks": [], "advice": [], "dropped": 0}
    if not llm.available():
        return empty
    body = (text or "").strip()
    if not body:
        return empty
    if len(body) > _MAX_INPUT:
        body = body[:_MAX_INPUT]
    hint = ""
    if hint_ethnicity or hint_disease:
        hint = (f"（本次补录针对：{hint_ethnicity or '未知民族'} × {hint_disease or '未知疾病'}，"
                "可作为判断研究对象的参照）\n")
    user = f"{hint}文献正文：\n{body}\n\n请按上述格式输出数据行。"
    try:
        content = await llm.chat_struct(
            [{"role": "system", "content": _SYSTEM_PROMPT},
             {"role": "user", "content": user}],
            temperature=0.1, json_mode=True, max_tokens=2000)
        parsed_rows, risks, advice = _parse_payload(content)
        body_norm = _norm(body)
        kept: list[dict] = []
        dropped = 0
        for row in parsed_rows:
            if _drop_fabricated(row, body_norm):
                dropped += 1
            else:
                kept.append(row)
        if dropped:
            logger.warning("字段抽取丢弃了 %d 行：数值或出处无法在原文中核对", dropped)
        return {"rows": kept, "risks": risks, "advice": advice, "dropped": dropped}
    except Exception:
        logger.exception("字段抽取失败")
        return empty
