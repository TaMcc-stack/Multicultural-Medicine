"""证据的结构化定位：章节路径、表格行/单元格、强调字段与原文锚点。

检索结果仍保留字符 offset 作为最终兜底；本模块只补充确定性、可解释的结构信息，
供前端按标题路径和表格字段定位。表格解析复用 `tables.parse_tables`，不另写一套。
"""
from __future__ import annotations

import re
from typing import Any

from .tables import parse_tables


_HEADING_LINE_RE = re.compile(r"(?m)^[ \t]{0,3}(#{1,6})[ \t]*([^\n#]+?)[ \t]*$")
_HEADING_INLINE_RE = re.compile(r"(#{1,6})[ \t]*([^#\n|]{1,80})")
_STRUCTURAL_HEADING_RE = re.compile(
    r"^\s*(?:[一二三四五六七八九十百千]+[、.．]|\d+\s*[、.．]|\(\d+\)|（\d+）)")
_HAS_CONTENT_RE = re.compile(r"[^\s#|:：;；,，。!！?？\-—]")
_RISK_INTENT_RE = re.compile(r"危险因素|风险|易感|哪些人")
_RISK_HEADING_RE = re.compile(r"危险因素|风险|易感")


def _clean_heading_title(raw: str) -> str:
    # 保留标题原文（只去掉尾部 # 和首尾空白）。前端目录用的是文档里的原始标题，
    # 这里一旦改写（例如把「1、、标题」归一成「1、标题」）两边就对不上，定位会失败。
    return (raw or "").strip().rstrip("#").strip()


def _is_structural_heading(level: int, title: str) -> bool:
    """与前端 outline 的判据一致：顶级恒为章节，2/3 级只认编号标题。"""
    if level == 1:
        return True
    if level not in (2, 3):
        return False
    return bool(_STRUCTURAL_HEADING_RE.match(title))


def _headings(text: str) -> list[tuple[int, str, int]]:
    """返回 [(level, title, start), ...]。

    先按正常 Markdown 行解析；文档切片被压成单行时，再用行内标题兜底。
    """
    if not text:
        return []
    found = [
        (len(m.group(1)), _clean_heading_title(m.group(2)), m.start())
        for m in _HEADING_LINE_RE.finditer(text)
        if _clean_heading_title(m.group(2))
    ]
    if found:
        return found
    return [
        (len(m.group(1)), _clean_heading_title(m.group(2)), m.start())
        for m in _HEADING_INLINE_RE.finditer(text)
        if _clean_heading_title(m.group(2))
    ]


def _find_position(text: str, fragment: str, start_offset: int | None) -> int:
    if start_offset is not None and start_offset >= 0:
        return min(start_offset, len(text))
    if not fragment:
        return -1
    probe = fragment.strip()
    for n in (min(len(probe), 80), min(len(probe), 40), 20):
        if n <= 0:
            continue
        pos = text.find(probe[:n])
        if pos >= 0:
            return pos
    return -1


def _section_path(
    source_text: str,
    fragment: str,
    full_text: str | None,
    start_offset: int | None,
    section_hint: str | None,
) -> list[str]:
    """取命中位置之前的 Markdown 标题层级，返回从顶级到当前的标题数组。"""
    candidates = []
    if full_text:
        candidates.append((full_text, _find_position(full_text, fragment, start_offset)))
    if source_text:
        candidates.append((source_text, _find_position(source_text, fragment, None)))

    for text, position in candidates:
        headings = _headings(text)
        if not headings:
            continue
        headings = [h for h in headings if _is_structural_heading(h[0], h[1])]
        if position >= 0:
            headings = [h for h in headings if h[2] <= position]
        if not headings:
            continue
        stack: list[tuple[int, str]] = []
        for level, title, _ in headings:
            while stack and stack[-1][0] >= level:
                stack.pop()
            stack.append((level, title))
        return [title for _, title in stack]

    if section_hint and len(section_hint.strip()) <= 120:
        return [section_hint.strip()]
    return []


def _term_score(text: str, terms: list[str]) -> int:
    """按命中词长度加权，避免短词把长而具体的字段顶掉。"""
    if not text:
        return 0
    return sum(len(term) for term in terms if term and term in text)


def _row_in_fragment(row: list[str], fragment: str) -> bool:
    """片段必须真的包含该行（或至少包含该行的数值格），不能只看同表头。"""
    if not fragment:
        return False
    compact_fragment = re.sub(r"\s+", "", fragment)
    compact_row = re.sub(r"\s+", "", " | ".join(row))
    if compact_row and compact_row in compact_fragment:
        return True
    lead = row[0] if row else ""
    if lead and lead not in fragment:
        return False
    return any(
        cell and cell not in ("—", "-", "－") and cell in fragment
        for cell in row[1:]
    )


def _table_hit(
    source_text: str,
    fragment: str,
    terms: list[str],
    understand: dict[str, Any],
) -> dict | None:
    """在 Markdown 表格里挑出与问题/片段最匹配的行。"""
    if not source_text:
        return None
    ethnicity = (understand or {}).get("ethnicity") or ""
    disease = (understand or {}).get("disease") or ""
    metric = (understand or {}).get("metric") or ""
    query_terms = [t for t in dict.fromkeys(terms or []) if t]

    candidates: list[dict] = []
    for table in parse_tables(source_text):
        context = table.get("context") or ""
        header = [h.strip() for h in (table.get("header") or []) if h.strip()]
        rows = table.get("rows") or []
        if not rows:
            continue
        table_score = _term_score(context, query_terms)
        table_score += _term_score(" ".join(header), query_terms)
        if disease and disease in " ".join(header + [context]):
            table_score += 4
        if metric and metric in " ".join(header):
            table_score += 2

        row_hit = None
        row_score = 0
        for row in rows:
            row = [str(c).strip() for c in row]
            if not row:
                continue
            text = " ".join(row)
            score = _term_score(text, query_terms)
            if ethnicity and row[0] == ethnicity:
                score += 100
            if disease and disease in text:
                score += 5
            if metric and metric in text:
                score += 2
            if any(re.search(r"\d", cell) for cell in row[1:]):
                score += 1
            if score > row_score:
                row_hit, row_score = row, score

        total = table_score * 4 + row_score
        if row_hit is not None:
            candidates.append({
                "context": context,
                "header": header,
                "row": row_hit,
                "table_score": table_score,
                "row_score": row_score,
                "score": total,
            })

    for best in sorted(candidates, key=lambda item: item["score"], reverse=True):
        if fragment and not _row_in_fragment(best["row"], fragment):
            cell_text = fragment.strip().strip("|").strip()
            if not any(str(cell).strip() == cell_text for cell in best["row"][1:]):
                continue
        return best
    return None


def _highlight_fields(header: list[str], terms: list[str]) -> list[str]:
    return [
        field for field in header
        if field and _term_score(field, terms) > 0
    ]


def _risk_anchor(
    text: str,
    section_path: list[str],
    ethnicity: str,
    terms: list[str],
) -> str | None:
    """风险类提问时，定位到同一节里的「危险因素/风险」小节正文。

    检索命中的往往是该节的患病率表格（整节是一个切片），但用户问的是「哪些人容易得」，
    该看的是危险因素那段。这里不再把目标落在表格行上，而是从定位到的标题往后找风险小节，
    取其中提到所问民族的那一段。
    """
    if not text:
        return None
    anchor_title = section_path[-1] if section_path else ""
    start = 0
    if anchor_title:
        pos = text.find(anchor_title)
        if pos >= 0:
            start = pos + len(anchor_title)
    rest = text[start:]
    heading_iter = list(re.finditer(r"(?m)^[ \t]{0,3}#{1,6}[ \t]*(.+?)[ \t]*$", rest))
    risk_match = next(
        (m for m in heading_iter if _RISK_HEADING_RE.search(m.group(1))),
        None,
    )
    if risk_match is None:
        return None
    body = rest[risk_match.end():]
    next_heading = re.search(r"(?m)^[ \t]{0,3}#{1,6}[ \t]*", body)
    if next_heading:
        body = body[: next_heading.start()]
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", body) if p.strip()]
    if not paragraphs:
        return None
    if ethnicity:
        for paragraph in paragraphs:
            if ethnicity in paragraph:
                return paragraph
    best = max(paragraphs, key=lambda p: _term_score(p, terms))
    return best if _term_score(best, terms) > 0 else None


def _cell_hit(row: list[str], header: list[str], fragment: str, terms: list[str]) -> dict | None:
    """只在片段本身就是单个单元格时判 table_cell，避免把整行证据降成单格。"""
    text = (fragment or "").strip().strip("|").strip()
    if not text or "|" in text or len(text) > 80:
        return None
    for index, value in enumerate(row):
        if value and value == text and 0 <= index < len(header):
            field = header[index]
            if field and _term_score(field, terms) > 0:
                return {"colKey": field, "anchorText": f"{row[0]} | {value}" if row else value}
    return None


def _contains_body(anchor: str, section_path: list[str]) -> bool:
    """判断片段是否包含标题之外的可读正文。"""
    if not anchor.startswith("#"):
        return bool(_HAS_CONTENT_RE.search(anchor))
    body = re.sub(r"^[ \t]*#{1,6}[ \t]*", "", anchor, count=1).strip()
    current = section_path[-1] if section_path else ""
    if current and body.startswith(current):
        body = body[len(current):].strip(" \t|")
    return bool(_HAS_CONTENT_RE.search(body))


def build_evidence_location(
    source_text: str,
    fragment: str,
    *,
    terms: list[str] | None = None,
    understand: dict[str, Any] | None = None,
    full_text: str | None = None,
    start_offset: int | None = None,
    section_hint: str | None = None,
) -> dict:
    """组装契约中的可选结构化字段；算不出的字段按契约省略。"""
    flat_terms: list[str] = []
    for term in terms or []:
        values = term if isinstance(term, (list, tuple, set)) else [term]
        flat_terms.extend(str(value).strip() for value in values if str(value).strip())
    terms = list(dict.fromkeys(flat_terms))
    understand = understand or {}
    ethnicity = understand.get("ethnicity") or ""
    table = _table_hit(source_text or "", fragment or "", terms, understand)
    if table:
        section_anchor = " | ".join(table["row"])
        section_path = _section_path(
            source_text or "", section_anchor, full_text, None, section_hint)
    else:
        section_path = _section_path(
            source_text or "", fragment or "", full_text, start_offset, section_hint)

    # 风险类提问优先落到「危险因素」段落：检索给的往往是该节的患病率表格，
    # 但用户问「哪些人容易得」时该看的是危险因素那段，这里把目标纠正过来。
    intent_text = " ".join([
        str(understand.get("intent") or ""),
        str(understand.get("question_type") or ""),
        " ".join(terms),
    ])
    if _RISK_INTENT_RE.search(intent_text):
        risk_text = _risk_anchor(
            full_text or source_text or "", section_path, ethnicity, terms)
        if risk_text:
            return {
                "sectionPath": section_path,
                "targetType": "text",
                "highlightFields": [],
                "anchorText": risk_text,
            }

    result: dict[str, Any] = {
        "sectionPath": section_path,
        "targetType": "text",
        "highlightFields": [],
    }

    if table:
        row, header = table["row"], table["header"]
        highlight = _highlight_fields(header, terms)
        result["targetType"] = "table_row"
        result["rowKey"] = row[0]
        result["highlightFields"] = highlight
        result["anchorText"] = " | ".join(row)
        cell = _cell_hit(row, header, fragment, terms)
        if cell:
            result["targetType"] = "table_cell"
            result.update(cell)
        return result

    anchor = (fragment or "").strip()
    if anchor:
        result["anchorText"] = anchor
        if not _contains_body(anchor, section_path):
            result["targetType"] = "section"
    else:
        result["targetType"] = "section"
    return result
