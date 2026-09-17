"""被压平的 Markdown 表格解析 + 表格键值（民族 × 疾病 × 数值）检索。

上传文档（docx / pdf）抽取正文时，Markdown 表格的换行会退化成空格，整张表被压成
一行，且一份资料里往往连着好几张表：

    ### 各民族糖尿病患病率： | 民族 | 糖尿病标化患病率 | | --- | --- | | 白族 | 16.0% | | 傣族 | 7.6% |

向量 / BM25 检索判断的是「整段主题相关性」，看不住表格里的单元格。同一份资料里的
综述句（「白族最高，哈尼族最低」）和数值表常被切进不同切片，按来源去重后数值表往往
被丢掉——用户问「白族糖尿病患病率」时，只会拿到那句没有数字的结论。

本模块只做两件确定性的事：
  1. 把压平的文本还原成**所有**规整的 Markdown 表格（旧实现「取首个即止」，
     会把第二张表嚼碎成第一张表的行）；
  2. 给定「民族 + 疾病」，直接命中表格里那一行（如「白族 | 16.0%」）并产出聚焦片段，
     供检索层作为最高优先级证据送进 LLM。
"""
import re

# 表格分隔行：| --- | :--: |
_TABLE_SEP_RE = re.compile(r"^:?-{2,}:?$")
# 数据行首列：民族名等短标签。句读、井号、竖线出现即说明这行是正文而非表格行，
# 用它来判定「表格到哪里结束」——压平后表格与后文粘在一起，没有别的边界信号。
_ROW_LEAD_RE = re.compile(r"^[^\s。！？：；，、#|]{1,12}$")
# 百分比数值（表格里的核心载荷）
PCT_RE = re.compile(r"\d+(?:\.\d+)?\s*%")

TABLE_SEP_RE = _TABLE_SEP_RE


def _flatten_cells(text: str) -> list[str]:
    """按管道符切成单元格并去掉空串（行间分隔会产生空单元格）。"""
    return [c.strip() for c in (text or "").split("|") if c.strip()]


def _looks_like_row(cells: list[str]) -> bool:
    """判断一组单元格是不是一条数据行（而非粘上来的正文）。"""
    if not cells:
        return False
    lead = cells[0] or ""
    if not _ROW_LEAD_RE.match(lead):
        return False
    return not any("#" in c for c in cells)


def _clean_context(ctx: str) -> str:
    """把表标题收敛到 Markdown 标题之后的那一小段。

    压平文本里表格与上一段正文粘在一起，「表头前一格」会拖进整段无关内容
    （如上一张表后面的结论句与危险因素描述）。最后一个 # 之后才是这张表的标题。
    """
    if not ctx:
        return ""
    if "#" in ctx:
        ctx = ctx.rsplit("#", 1)[-1]
    return ctx.strip().strip("：: ").strip()


def parse_tables(text: str) -> list[dict]:
    """把压平文本还原成表格列表。

    返回 [{"context": 表标题, "header": [表头], "rows": [[数据行]]}]，按出现顺序。
    每张表的数据行扫描到第一处「不像表格行」的内容为止，因此多张表都能正确切分。
    """
    cells = _flatten_cells(text)
    tables: list[dict] = []
    n = len(cells)
    i = 0
    while i < n:
        if not _TABLE_SEP_RE.match(cells[i]):
            i += 1
            continue
        # 连续分隔单元格的个数 = 列数
        j = i
        while j < n and _TABLE_SEP_RE.match(cells[j]):
            j += 1
        ncols = j - i
        hdr_start = i - ncols
        if ncols < 2 or hdr_start < 0:
            i = j
            continue
        header = cells[hdr_start:i]
        if not all(header):
            i = j
            continue
        rows: list[list[str]] = []
        k = j
        while k + ncols <= n and _looks_like_row(cells[k:k + ncols]):
            rows.append(cells[k:k + ncols])
            k += ncols
        if rows:
            tables.append({
                # 表标题：表头前一个单元格，压平文本里通常是「### 各民族糖尿病患病率：」
                "context": _clean_context(cells[hdr_start - 1] if hdr_start - 1 >= 0 else ""),
                "header": header,
                "rows": rows,
            })
            i = k
        else:
            i = j
    return tables


def markdown_table(header: list[str], rows: list[list[str]]) -> str:
    """按给定表头与数据行拼回 Markdown 表格字符串。"""
    lines = ["| " + " | ".join(header) + " |",
             "| " + " | ".join(["---"] * len(header)) + " |"]
    lines += ["| " + " | ".join(r) + " |" for r in rows]
    return "\n".join(lines)


def reconstruct_md_tables(text: str) -> list[str]:
    """还原文本中的全部表格，返回 Markdown 表格字符串列表。"""
    return [markdown_table(t["header"], t["rows"]) for t in parse_tables(text)]


def extract_kv_row(text: str, ethnicity: str, disease: str) -> dict | None:
    """在文本中定位「民族 × 疾病 × 百分比」的表格行。

    只有当表标题或表头点明所问疾病时才认账（避免把同一切片里的 CKM 表、
    高血压表当成糖尿病表）。命中行必须带百分比数值，否则返回 None——没有具体
    数字的表不构成「数值证据」，交给常规检索处理即可。

    返回 {"context", "header", "row", "rows"}；未命中返回 None。
    """
    if not (text and ethnicity and disease):
        return None
    for t in parse_tables(text):
        header = t["header"]
        context = t["context"] or ""
        if disease not in context and disease not in " ".join(header):
            continue
        for row in t["rows"]:
            if (row[0] or "").strip() != ethnicity:
                continue
            if any(PCT_RE.search(c or "") for c in row[1:]):
                return {"context": context, "header": header,
                        "row": row, "rows": t["rows"]}
    return None


def kv_snippet(hit: dict, source_title: str = "") -> str:
    """把命中的表格写成一段聚焦证据。

    带完整表格（而非只留命中行）是为了保留民族间对比——用户问白族，同时也需要
    「傣族 7.6%、哈尼族 5.0%」这些参照值才能理解「16.0% 算不算高」。
    """
    header, rows, row = hit["header"], hit["rows"], hit["row"]
    title = (hit["context"] or "").strip().lstrip("#").strip()
    if source_title:
        title = f"{title}（来源：{source_title}）" if title else f"（来源：{source_title}）"
    values = "；".join(
        f"{row[0]} {c}".strip() for c in row[1:] if PCT_RE.search(c or "")
    )
    parts = [p for p in (title, f"命中数值：{values}" if values else "") if p]
    parts.append(markdown_table(header, rows))
    return "\n".join(parts)
