"""FastAPI AI 服务：问题理解 / 知识库检索 / Qwen 回答生成 / 摄取同步"""
import logging
import re
from contextlib import asynccontextmanager
from typing import Optional, List, Dict, Any, NamedTuple

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from . import config
from .services import llm
from .services.answer import generate_answer, generate_fallback_answer, _refine_eth_text
from .services.embeddings import embedding_engine
from .services.evidence_extract import extract_evidences
from .services.field_extract import extract_rows
from .services.expansion import expand_query
from .services.kb import (Entry, citation_str, kb,
                          PARTITION_INTEGRATED, PARTITION_RAW)
from .services.fetch import fetch_article, ArticleFetchError
from .services.summarize import summarize_document
from .services.understanding import (understand, metric_family, dict_provider,
                                     ETHNICITY_GENERAL, eth_label,
                                     MEDICAL_ADVICE_REPLY)

logger = logging.getLogger("ai-service")
logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    papers = kb.load_papers()
    dynamic = kb.load_dynamic_evidences()
    doc_chunks = kb.load_document_chunks()
    await kb.build_index()
    logger.info(
        "知识库装载完成：%d 篇真实文献 / %d 条证据片段（含 %d 条 AI 提取动态证据 / %d 条文档切片）；embedding=%s, llm=%s",
        papers, len(kb.entries), dynamic, doc_chunks, embedding_engine(), llm.llm_engine(),
    )
    yield


app = FastAPI(title="medquery-ai AI Service", version="1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_methods=["*"], allow_headers=["*"],
)


# ---------- 请求/响应模型 ----------
class UnderstandRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    # 最近几轮对话：[{question, ethnicity, disease}]。追问常省略疾病名
    # （「有没有适合白族人的日常血糖监测方法」），要靠它把实体补全。
    history: List[Dict[str, Any]] = []


class AnswerRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    ethnicity: Optional[str] = None
    disease: Optional[str] = None
    intent: Optional[str] = None


class ChunkIn(BaseModel):
    index: int = 0
    pageNo: Optional[int] = None
    section: Optional[str] = None
    content: str = ""


class DocumentSyncRequest(BaseModel):
    documentId: int
    title: str = ""
    fileName: Optional[str] = None
    chunks: list[ChunkIn] = []
    fullText: Optional[str] = Field(default=None, description="文档全文（供左全文·右证据浏览模式）")
    # 来源维度（一级分类）。由 Java 侧裁决后传入——官方身份在后端按账号白名单判定，
    # 前端说了不算，否则「来源等级」只是自选标签。
    sourceLevel: str = Field(default="official", description="official / web_crawl / user_upload")
    sourceOrg: Optional[str] = Field(default=None, description="来源机构，如「国家卫健委」")
    sourceUrl: Optional[str] = Field(default=None, description="原始链接（网页抓取时记录）")
    # 知识库分区：integrated（整合资料库，优先检索）/ raw（原始文献库，降级检索）。
    # 由 Java 侧按 kb_document.partition 下发；老数据没这一项时，kb 侧按文件名后缀现推。
    partition: str = Field(default="integrated", description="integrated / raw")


class FetchUrlRequest(BaseModel):
    """「从链接导入」：抓一个网页并提取正文。

    **白名单校验不在这里**——由 Java 侧在调用本端点之前完成（见 KbService.importFromUrl）。
    这里只负责抓取与清洗，返回最终 URL 供 Java 二次校验（防重定向绕过）。
    """
    url: str = Field(min_length=1, max_length=2000)
    timeout: Optional[float] = Field(default=20.0, ge=1, le=60)


class EvidenceExtractRequest(BaseModel):
    documentId: int
    title: str = ""
    fileName: Optional[str] = None
    ethnicity: Optional[str] = None
    disease: Optional[str] = None
    chunks: list[ChunkIn] = []


class SummarizeRequest(BaseModel):
    title: str = ""
    text: str = Field(default="", description="全文内容")


class ExtractFieldsRequest(BaseModel):
    """文献补录工作台：把一段正文/摘要抽成「民族 × 指标 = 数值」的数据行"""
    text: str = Field(default="", description="文献正文或摘要")
    ethnicity: Optional[str] = Field(default=None, description="当前缺口已知的民族，作为判断研究对象的参照")
    disease: Optional[str] = Field(default=None, description="当前缺口已知的疾病，同上")


class EvidenceItem(BaseModel):
    topic: str
    content: str
    pageNo: Optional[int] = None
    section: Optional[str] = None
    ethnicity: Optional[str] = None
    disease: Optional[str] = None


class EvidencePushRequest(BaseModel):
    paper: dict = {}
    evidences: list[EvidenceItem] = []


# ---------- 前端问答链路请求模型 ----------
class FrontendPoolRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    understand: Dict[str, Any] = {}
    knowledge: List[Dict[str, Any]] = []
    # 见 UnderstandRequest.history：槽位兜底重新理解时同样需要上下文
    history: List[Dict[str, Any]] = []


class FrontendGenerateRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    understand: Dict[str, Any] = {}
    retrieve_result: Dict[str, Any] = {}
    # 见 UnderstandRequest.history：槽位兜底重新理解时同样需要上下文
    history: List[Dict[str, Any]] = []


# ---------- 健康与状态 ----------
@app.get("/api/ai/health")
async def health():
    return {
        "status": "UP",
        "llm": llm.llm_engine(),
        "embedding": embedding_engine(),
        "dataset": f"{len(kb.papers_meta)} papers / {len(kb.entries)} entries",
    }


@app.get("/api/ai/kb/status")
async def kb_status():
    return {**kb.status(), "llm": llm.llm_engine(), "embedding": embedding_engine()}


# ---------- 问题理解 ----------
@app.post("/api/ai/understand")
async def understand_endpoint(req: UnderstandRequest):
    return await understand(req.question)


# ---------- 问答 ----------
def _to_citation(i: int, e: Entry) -> dict:
    paper = None
    if e.source_type == "KB_EVIDENCE" and e.paper:
        p = e.paper
        paper = {
            "id": e.doc_id,
            "title": p.get("title"),
            "ethnicity": p.get("ethnicity"),
            "disease": p.get("disease"),
            "population": p.get("population"),
            "studyYear": p.get("studyYear"),
            "findings": p.get("findings"),
            "limitation": p.get("limitation"),
            "source": citation_str(p),
        }
    elif e.source_type == "RAG_CHUNK":
        paper = {
            "id": e.doc_id,
            "title": e.doc_title,
            "ethnicity": None, "disease": None, "population": None,
            "studyYear": None, "findings": None, "limitation": None,
            "source": f"知识库上传文档《{e.doc_title}》",
        }
    return {
        "index": i,
        "evidenceId": e.entry_id,
        "quote": e.text,
        "pageNo": e.page_no,
        "section": e.section,
        "topic": e.topic or e.section,
        "docTitle": e.doc_title,
        "fileName": e.file_name,
        "sourceType": e.source_type,
        "paper": paper,
    }


@app.post("/api/ai/answer")
async def answer_endpoint(req: AnswerRequest):
    understanding = await understand(req.question)
    ethnicity = req.ethnicity or understanding.get("ethnicity")
    disease = req.disease or understanding.get("disease")
    understanding["ethnicity"], understanding["disease"] = ethnicity, disease

    entries = await _retrieve(req.question, ethnicity, disease,
                              req.intent or understanding.get("intent"))
    if not entries:
        return {"answer": None, "citations": [],
                "engine": f"{llm.llm_engine()} · kb:{embedding_engine()}",
                "understanding": understanding}
    # 意图明确时：送生成的证据必须与意图直接相关（防无据硬编）
    u_intent = req.intent or understanding.get("intent")
    if u_intent and u_intent != "overview":
        entries = [e for e in entries if _intent_relevant(e, understanding)]
    if not entries:
        return {"answer": _no_evidence_answer(understanding), "citations": [],
                "engine": "no-evidence", "understanding": understanding}

    answer = await generate_answer(req.question, understanding, entries)
    citations = [_to_citation(i, e) for i, e in enumerate(entries, 1)]
    return {
        "answer": answer,
        "citations": citations,
        "engine": f"{answer.get('engine', 'template')} · kb:{embedding_engine()}",
        "understanding": understanding,
    }


# ---------- 前端问答链路（多步：understand → evidence-pool → generate） ----------
# ---------- 证据片段清洗：把切片里的 markdown 表格转成可读纯文本 ----------
def _strip_md_headers(text: str) -> str:
    """去掉 markdown 标题 # 号并折叠多余空白。"""
    text = re.sub(r"^#{1,6}\s*", "", text, flags=re.M)
    # 整块切片常压成单行，行首锚点删不掉行中的 ###/##；
    # 把行中残留的标题符号替换为空格，避免证据展示出现「### 噪音」
    text = re.sub(r"\s*#{1,6}\s*", " ", text)
    text = re.sub(r"[ \t]{2,}", " ", text)
    return text.strip()


def _md_table_to_text(text: str) -> str | None:
    """把一段可能含 markdown 表格（常在同一行内、用 | 分隔）的文本转成可读纯文本。

    返回 None 表示不是可解析的表格（调用方走兜底）。表格数值逐字保留，不做任何推算。
    """
    if "|" not in text:
        return None
    parts = text.split("|", 1)
    preamble = parts[0]
    rest = parts[1] if len(parts) > 1 else ""
    cells = [c.strip() for c in rest.split("|")]
    cells = [c for c in cells if c != ""]
    if len(cells) < 4:
        return None
    # 定位分隔行（连续全为 -/—/: 的单元格）
    sep = next((i for i, c in enumerate(cells) if re.fullmatch(r"[:\-—]+", c)), None)
    if sep is None:
        return None
    ncol = 1
    while sep + ncol < len(cells) and re.fullmatch(r"[:\-—]+", cells[sep + ncol]):
        ncol += 1
    if sep < ncol:
        return None
    header = cells[sep - ncol:sep]
    body = cells[sep + ncol:]
    if len(header) != ncol or not body:
        return None
    # 表格之后常粘连不含 | 的正文，按 ncol 取整丢弃残行，避免整除校验失败
    body = body[: len(body) - (len(body) % ncol)]
    if not body:
        return None
    rows = [body[k:k + ncol] for k in range(0, len(body), ncol)]
    parts_txt = []
    for r in rows:
        label = r[0]
        vals = [v for v in r[1:] if v not in ("", "—", "-")]
        parts_txt.append(f"{label}：{' / '.join(vals)}" if vals else label)
    table_txt = f"{' / '.join(header)}：{'；'.join(parts_txt)}"
    pre = _strip_md_headers(preamble.strip())
    return (pre + " " + table_txt).strip() if pre else table_txt


def _clean_fragment(text: str) -> str:
    """证据展示用：去掉 markdown 标题噪音；若含表格则转成可读纯文本（不引入 HTML）。"""
    text = (text or "").strip()
    if not text:
        return text
    # 去掉溢出到下一节的标题：仅当 "## " 之前是句末标点（说明是新的章节）时截断，
    # 这样同一条目里紧跟疾病词的 "### 各民族糖尿病患病率" 这类小标题得以保留
    idx = text.find("## ")
    while idx != -1:
        before = text[:idx].rstrip()
        if before and before[-1] in "。；;":
            text = before
            break
        idx = text.find("## ", idx + 2)
    if "|" in text:
        t = _md_table_to_text(text)
        if t:
            return t
        return _strip_md_headers(text.replace("|", " / "))  # 兜底：管道符换可读分隔
    return _strip_md_headers(text)


def _dedupe_titles(evidence: list[dict]) -> None:
    """同名来源自动编号，避免「证据材料」里出现多条都叫《数据资料》。"""
    seen: dict[str, int] = {}
    for e in evidence:
        t = e.get("title") or "来源资料"
        seen[t] = seen.get(t, 0) + 1
        if seen[t] > 1:
            e["title"] = f"{t}（片段{seen[t]}）"


def _dedupe_entries(entries: list, limit: int) -> list:
    """挑出送进回答生成的证据：**先保证来源多样，还有额度就继续往同一份来源里取**。

    分三层：
      1. 内容去重——正文（去空白后）完全相同的条目只保留一条，防止同一份资料被重复摄取
         （历史 bug 导致「数据资料」在索引里存在 3 个 doc_id）后证据被重复展示；
      2. 表格键值证据（kv）置顶且永远保留——它是数值的聚焦版，正是「库里有 16.0%、
         回答却只给结论句」的解药；
      3. **按来源轮转取**（round-robin），取代原先的「每个来源只留一条」。

    为什么把「按来源去重」从硬砍改成轮转：原先是一份文档只出一条，**知识库里只有 1 份
    资料时会把所有切片折叠成 1 条**。实测问「我是来自云南的，我想知道我们当地糖尿病患病的
    一个大致情况」，命中了 4 条候选（城乡差异 / 各民族患病率汇总 / 海拔差异 / ≥35岁特征），
    送进生成的却只剩「城乡差异」1 条，回答因此完全没有具体患病率数字，也写不出适用范围。

    轮转的语义：先让每份来源各出一条（多文档时仍然是「来源不重复」，与原意图一致），
    走完一轮还没到 limit 就回头取第二轮、第三轮（单文档时能把额度用满）。

    注意这里**不再**因为某文档有 kv 证据就丢掉它的其他切片：那是硬砍的另一处，
    同样会让单文档知识库只剩 1 条。kv 已置顶保住数值，其余切片补充它没有的分民族/分地区数据。
    """
    if limit <= 0:
        return []

    # ① 内容去重（保持原顺序），kv 证据单独拎出置顶
    kv: list = []
    rest: list = []
    seen_text: set = set()
    for e in entries:
        tkey = re.sub(r"\s+", "", e.text or "")
        if tkey in seen_text:
            continue
        seen_text.add(tkey)
        if getattr(e, "kv", False):
            kv.append(e)
        else:
            rest.append(e)

    out: list = kv[:limit]
    if len(out) >= limit:
        return out

    # ② 按来源分组，组内保持原顺序（entries 本身已按相关性降序）
    groups: dict = {}
    order: list = []
    for e in rest:
        if e.source_type == "KB_EVIDENCE":
            skey = ("paper", (e.paper or {}).get("title") or e.doc_title or id(e))
        else:
            skey = ("doc", e.doc_id)
        if skey not in groups:
            groups[skey] = []
            order.append(skey)
        groups[skey].append(e)

    # ③ 轮转：第一轮每份来源各取一条，之后回头取第二轮、第三轮……
    round_i = 0
    while len(out) < limit:
        took = False
        for skey in order:
            if round_i < len(groups[skey]):
                out.append(groups[skey][round_i])
                took = True
                if len(out) >= limit:
                    break
        if not took:
            break
        round_i += 1
    return out


def _sentence_spans(text: str) -> list[tuple[int, int]]:
    """把文本切成「以 。！？ 结尾的完整句子」的 (start, end) 区间（end 含结尾标点）。

    无句末标点的整段（如一个表格块、或切片在句中被截断的开头）视为一个 span。
    用于证据片段截取时只以句子为边界，绝不在句中硬切（断章取义）。
    """
    spans: list[tuple[int, int]] = []
    start = 0
    for m in re.finditer(r"[。！？]", text):
        end = m.end()
        spans.append((start, end))
        start = end
    if start < len(text):
        spans.append((start, len(text)))
    return spans


def _expand_sentences(text: str, anchor: int,
                      expand_terms: list[str] | None, cap: int = 600) -> str:
    """以 anchor 所在句子为中心，返回完整句子片段（不在句中硬切）。

    - 锚点句本身已足够长（>= MIN_ANCHOR）→ 直接返回该完整句（聚焦，不牵扯无关句）。
    - 锚点句偏短 → 仅向两侧补充「含 expand_terms 关键词」的相邻完整句，直到达到 cap；
      无相关相邻句则止（避免把同一切片里无关的下一节整段一并拉进来）。
    """
    spans = _sentence_spans(text)
    if not spans:
        return text[:cap].rstrip()
    # 定位 anchor 所在句子
    cur = None
    for i, (s, e) in enumerate(spans):
        if s <= anchor < e:
            cur = i
            break
    if cur is None:
        for i, (s, e) in enumerate(spans):
            if s >= anchor:
                cur = i
                break
        if cur is None:
            cur = len(spans) - 1
    MIN_ANCHOR = 50
    chosen = [spans[cur]]
    total = spans[cur][1] - spans[cur][0]
    terms = [t for t in (expand_terms or []) if t]
    if total >= MIN_ANCHOR or not terms:
        return text[spans[cur][0]:spans[cur][1]].strip()
    # 锚点句偏短：补充含关键词的相邻完整句
    left, right = cur - 1, cur + 1
    while total < cap and (left >= 0 or right < len(spans)):
        cand: list[tuple[str, tuple[int, int]]] = []
        if right < len(spans) and any(t in text[spans[right][0]:spans[right][1]] for t in terms):
            cand.append(("r", spans[right]))
        if left >= 0 and any(t in text[spans[left][0]:spans[left][1]] for t in terms):
            cand.append(("l", spans[left]))
        if not cand:
            break
        cand.sort(key=lambda x: (x[1][1] - x[1][0]))  # 较短一侧先补
        side, sp = cand[0]
        if total + (sp[1] - sp[0]) > cap:
            break
        total += sp[1] - sp[0]
        chosen.append(sp)
        if side == "r":
            right += 1
        else:
            left -= 1
    chosen.sort()
    return text[chosen[0][0]:chosen[-1][1]].strip()


def _focused_snippet(text, groups, disease: str | None = None,
                     ethnicity: str | None = None, cap: int = 600,
                     min_len: int = 240):
    """从切片中取出「与问题最相关」的聚焦片段，并保证每一句都完整（不断章取义）。

    先按滑窗评分选出锚点（回答真正引用的内容位置），再以锚点句为中心向左右扩展为
    完整句子（以 。！？ 为边界截断）；锚点句偏短则只补充含疾病词的相邻完整句。
    评分对「含所问民族」的窗口给予强加权，使问「白族」时锚定到白族句而非同一切片里的
    傣族/哈尼族句（避免证据材料展示出无关民族的片段）。
    例：问「白族糖尿病患病率」锚定到「三个民族间的糖尿病患病差异……白族最高，哈尼族
    最低。」这一完整句子，而不是在「白族最高」处硬切成半句。

    仅当整段切片本身就很短（<= min_len）时才整段返回；更长的切片一律聚焦到相关完整句，
    扩展总长度不超过 cap（绝不截断句子）。
    """
    text = (text or "").strip()
    if not text:
        return text
    if len(text) <= min_len:
        return text
    groups = [g for g in (groups or []) if g]
    if not groups:
        # 无锚定词：返回开头的完整句子（不硬切）
        return _expand_sentences(text, 0, None, cap)
    n = len(groups)
    eth_words = [ethnicity] if ethnicity else []

    def _window_start(idx: int) -> tuple[int, int]:
        """窗口起点回溯到邻近的小节标题（##/###），返回 (起点, 是否锚定在标题处)。"""
        h = text.rfind("##", max(0, idx - 20), idx)
        return (h, 1) if h != -1 else (idx, 0)

    # 评分窗口只用于定位锚点句（取较小窗口即可）
    score_win = 200

    def _nearest_hit(kw: list[str], lo: int, hi: int, anchor_pos: int) -> int | None:
        """窗口 [lo,hi) 内、离锚点 anchor_pos 最近的命中距离；无命中返回 None。"""
        best = None
        for k in kw:
            p = text.find(k, lo, hi)
            while p != -1:
                d = abs(p - anchor_pos)
                if best is None or d < best:
                    best = d
                p = text.find(k, p + 1, hi)
        return best

    def _decay(d: int) -> float:
        """距离衰减：紧贴锚点的命中远比窗口另一端的可靠。

        窗口宽 200 字，若不衰减，出现在窗口末端的「傣族」和就在锚点旁的「傣族」
        等价——同一份资料里有两张「高血压患病率」表时，问傣族会因「更早优先」
        锚到汉族/哈尼族那张表上，取出的片段里根本没有傣族的数值。
        """
        if d <= 60:
            return 1.0
        return 0.6 if d <= 140 else 0.3

    best_idx, best_key = 0, (-1, -1.0)
    for g in groups:
        for t in g:
            start = 0
            while True:
                idx = text.find(t, start)
                if idx == -1:
                    break
                win_start, at_heading = _window_start(idx)
                lo, hi = win_start, win_start + score_win
                hits, score = 0, 0.0
                for gi, kw in enumerate(groups):
                    d = _nearest_hit(kw, lo, hi, idx)
                    if d is None:
                        continue
                    hits += 1
                    score += (n - gi) * _decay(d)  # 越靠前的组（意图/指标）权重越高
                # 含所问民族 → 强加权（优先锚定到白族句，而非同一切片的傣族/哈尼族句）
                eth_d = _nearest_hit(eth_words, lo, hi, idx) if eth_words else None
                if eth_d is not None:
                    score += 5.0 * _decay(eth_d)
                # 锚点词自身所属组的权重加成。评分窗口有 200 字宽，相邻窗口容易"顺带"
                # 命中同一批组而完全平手，平手后又按「更早优先」落到错误的表上
                # （问「肥胖率」却锚到前面的心脏瓣膜病）。给锚点词本身加权，
                # 让「锚在用户明确问的指标词上」胜过「锚在别处但窗口够宽」。
                own = 0.0
                for gi, kw in enumerate(groups):
                    if t in kw:
                        own = (n - gi) * 1.5
                        break
                key = (hits, score + own, at_heading, -win_start)  # 平手时：标题锚定优先、更早优先
                if key > best_key:
                    best_key, best_idx = key, idx
                start = idx + 1
    anchor = best_idx if best_key[0] > 0 else 0
    return _expand_sentences(text, anchor, [disease] if disease else None, cap)


def _kv_locate_span(full_text: str, source_text: str, ethnicity: str,
                    disease: str) -> tuple[int, int]:
    """KV 表格证据在原文中的精确区间：从表标题起，到命中行结束为止。

    直接拿来源切片定位会圈进同一切片里的其他表（问糖尿病却把前面的 CKM 表一起高亮），
    溯源就不精确了。这里按「表标题 → 命中行末格」重新夹取一段。
    """
    from .services.tables import extract_kv_row

    hit = extract_kv_row(source_text, ethnicity, disease)
    if not hit:
        return -1, -1
    # parse_tables 已把 context 清洗成「各民族糖尿病患病率」这样的纯标题
    ctx = (hit.get("context") or "").strip()
    if not ctx:
        return -1, -1
    start = full_text.find(ctx)
    if start < 0:
        return -1, -1
    end = start + len(ctx)
    hit_row = hit.get("row") or []
    if hit_row:
        # 命中民族那一行：首格定位，再把该行其余单元格依次纳入范围
        lead = (hit_row[0] or "").strip()
        k = full_text.find(lead, start) if lead else -1
        if k >= 0:
            end = k + len(lead)
            for cell in hit_row[1:]:
                c = (cell or "").strip()
                if not c:
                    continue
                ck = full_text.find(c, end)
                if ck >= 0 and ck - end < 40:   # 相邻才算同一行，避免匹配到别处
                    end = ck + len(c)
    return start, min(end, len(full_text))


def _entry_to_evidence(e: Entry, understand: dict | None = None,
                       question: str = "") -> dict:
    paper = e.paper if e.source_type == "KB_EVIDENCE" else None
    if paper:
        source = citation_str(paper)
        doi = paper.get("doi")
        url = paper.get("url")
    else:
        source = f"知识库上传文档《{e.doc_title}》"
        doi = None
        url = None
    # 证据片段：聚焦到「疾病」关键词附近（避免锚定到同文档里的 CKM 等其他病数据），
    # 并以完整句子输出（不断章取义）；最后把切片里的 markdown 表格转成可读纯文本
    terms = _anchor_groups(understand, question) if understand else None
    disease = (understand or {}).get("disease")
    frag = (e.text or "").strip()
    if len(frag) > 220:
        frag = _focused_snippet(frag, terms, disease, (understand or {}).get("ethnicity"))
    frag = _refine_eth_text(_clean_fragment(frag), (understand or {}).get("ethnicity"))
    # 原文定位：算出片段在文档全文里的字符区间，前端据此「点片段 → 弹原文并高亮」。
    # 只对知识库文档切片有意义（论文证据没有本地全文），拿不到全文时返回 -1。
    # KV 合成证据的 frag 是包装过的文本，原文里搜不到，改用它的来源切片定位。
    start, end = -1, -1
    if e.doc_id is not None:
        full_text = kb.get_document_full_text(e.doc_id)
        if not full_text:
            full_text = "\n".join(x.text for x in kb.entries
                                  if x.source_type == "RAG_CHUNK" and x.doc_id == e.doc_id)
        eth = (understand or {}).get("ethnicity") or ""
        dis = (understand or {}).get("disease") or ""
        if e.kv and e.source_text and eth and dis:
            # KV 证据：收紧到它对应的那张表，避免把同切片里的其他表一起高亮
            start, end = _kv_locate_span(full_text, e.source_text, eth, dis)
        if start < 0:
            target = (e.source_text or "").strip() or frag
            start, end = _compute_snippet_offsets(full_text, target)
    return {
        "id": str(e.doc_id) if e.doc_id is not None else "",
        "title": e.doc_title or "来源资料",
        "fragment": frag,
        "startOffset": start,
        "endOffset": end,
        # 命中的关键词：前端在片段里标出来，说明「为什么这条被检索到」
        "matchedTerms": _matched_terms(terms, frag),
        "page": e.page_no,
        "doi": doi,
        "url": url,
        "source": source,
        # 来源维度（一级分类）：前端据此显示来源标签。
        # 与上面的 `source`（文献出处：期刊/出版社）是两条不同的轴，故字段名刻意错开。
        "sourceLevel": e.source_level,
        "sourceOrg": e.source_org,
        "sourceUrl": e.source_url,
        # 知识库分区：raw 的条目在溯源时该展示 PDF 原文，integrated 的展示文档片段
        "partition": e.partition,
    }


def _entry_to_candidate(e: Entry, understand: dict | None = None,
                        question: str = "") -> dict:
    source = "papers" if e.source_type == "KB_EVIDENCE" else "kb"
    terms = _anchor_groups(understand, question) if understand else None
    disease = (understand or {}).get("disease") or e.disease
    frag = (e.text or "").strip()
    if len(frag) > 220:
        frag = _focused_snippet(frag, terms, disease, (understand or {}).get("ethnicity"))
    frag = _refine_eth_text(_clean_fragment(frag), (understand or {}).get("ethnicity"))
    return {
        "source": source,
        "id": str(e.doc_id) if e.doc_id is not None else "",
        "title": e.doc_title or "",
        "content": e.text,          # 完整资料（供左栏展开查看）
        "ethnicity": e.ethnicity,
        "disease": e.disease,
        "topic": e.topic,
        "status": "sufficient",
        # 知识库分区：前端据此区分「整合资料」与「原始文献」，并决定溯源时展示什么
        "partition": e.partition,
        "evidence": [{"topic": e.topic, "fragment": frag}],
    }


# 注意：prevalence 的统一中文是「患病情况」（与前端 INTENT_LABELS 一致）。
# 「患病情况」是上位概念（患病率/知晓率/控制率等都属于它），不能窄化成「患病率」。
_INTENT_QTYPE = {
    "prevalence": "患病率/发病率查询", "risk": "危险因素查询", "diet": "饮食与生活方式查询",
    "genetics": "遗传相关研究", "overview": "研究概况",
    "prevention": "预防与筛查查询", "symptoms": "症状与早期信号查询",
    "medication": "用药注意事项查询", "treatment": "治疗与干预方向查询",
    "burden": "疾病负担与严重程度查询",
    # 兜底档：不指向某个方面，走全量检索 + 综合回答
    "all": "综合情况查询",
}
# 标准问句后缀。**故意不逐字等于界面标签**：这一串要进 BM25，措辞得贴着文献里的用词
# （如界面上写「用药注意事项」，检索用「用药情况」——「注意事项」是疑问词，对打分只起噪声）。
# 先例就是 diet：界面「饮食与生活方式」，这里「饮食情况」。
# 已知的 5 档只有 diet 不同，新增的 5 档按检索效果各写各的，两者不再是同一份清单。
_INTENT_PHRASE = {
    "prevalence": "患病率", "risk": "危险因素", "diet": "饮食情况",
    "genetics": "遗传相关研究", "overview": "研究概况",
    "prevention": "预防与筛查", "symptoms": "症状表现",
    "medication": "用药情况", "treatment": "治疗情况", "burden": "疾病负担",
    "all": "综合情况",
}
# 命中具体指标（metric）时的展示细化：查询意图/问题类型/标准化问题都落到指标本身。
# 如「白族糖尿病患病率为多少」→ 查询意图=患病率、问题类型=患病率查询、
# 标准化问题=白族糖尿病的患病率是多少，而不是泛化的「患病情况」。
_METRIC_TAIL = {
    "prevalence": "是多少",
    "risk": "有哪些",
    "genetics": "相关研究有哪些",
    "diet": "情况如何",
    "overview": "如何",
}


# 泛民族哨兵值在展示层的说法（后台/标准化问句里不能直接显示 "GENERAL"）
_eth_label = eth_label


def _slots_from_understanding(understand: Dict[str, Any]) -> tuple:
    u = understand or {}
    return (
        u.get("ethnicity") or None,
        u.get("disease") or None,
        u.get("intent") or u.get("query_intent") or None,
    )


SAFETY_FOLLOW_UPS = [
    "不同民族在用药上存在哪些差异？",
    "糖尿病患者日常饮食要注意什么？",
    "某个民族的糖尿病患病情况如何？",
]


def _safety_answer() -> dict:
    """医疗安全边界的固定回答：只有拒绝与就医引导，不带任何文献证据。

    刻意不带证据：这类问题的正确回答就是「我不能答」，
    把库里不相干的片段（吃鸡蛋、傣族吸烟、低教育水平）拼进来当「依据」，
    既生硬又容易被误读成建议。
    """
    return {
        "conclusion": MEDICAL_ADVICE_REPLY,
        "detailed": "",
        "actions": "",
        "applicable": "—",
        "timeRegion": "—",
        "cautions": "—",
        "followUps": list(SAFETY_FOLLOW_UPS),
        "format": "text",
        "table": None,
        "chart": None,
        "engine": "safety-boundary",
    }


async def _retrieve(question: str, ethnicity, disease, intent,
                    ensure_doc_coverage: bool = False,
                    partition: str | None = None) -> list:
    """统一检索入口：按需做查询扩展，再走混合检索。

    口语问法与文献书面表述常有落差，扩展出等价问句能补回单一措辞漏掉的证据。
    扩展失败（LLM 不可用 / 解析不出）时静默退回单问句检索，不影响主链路。

    partition 限定只在这一个知识库分区内检索；分档检索请用 `_retrieve_tiered`。
    """
    extra: list[str] = []
    if config.QUERY_EXPANSION_ENABLED:
        extra = await expand_query(question, ethnicity, disease, intent,
                                   n=config.QUERY_EXPANSION_VARIANTS)
    return await kb.retrieve(question, ethnicity, disease, intent=intent,
                             ensure_doc_coverage=ensure_doc_coverage,
                             extra_queries=extra, partition=partition)


class TieredRetrieval(NamedTuple):
    """两趟检索的结果。字段名写得长一点，因为调用方用它们的地方离得不近。"""
    candidates: list      # 选定档次的完整检索结果（含保底切片 / kv 合成证据），驱动「检索结果」面板
    relevant: list        # 过了意图硬门槛的真证据（未去重）—— 进回答的那批
    pre_intent: list      # 意图过滤之前的条目，用于说明「仅找到关于【X】的背景资料」
    tier: str             # 实际用的是哪一档：integrated（整合资料）/ raw（原始文献）


async def _retrieve_tiered(question: str, understand: Dict[str, Any],
                           ensure_doc_coverage: bool = False) -> TieredRetrieval:
    """两趟检索：先查【整合资料库】，命中不了才降级翻【原始文献库】。

    「命中」的判据是**过了意图硬门槛之后还有剩**，而不是「检索器返回了候选」：
    整合资料里没谈这个话题时，检索器照样会按字面相似度返回几条不相干的切片，
    只有过了 `_intent_relevant` 才算真的答得上。判据必须放在过滤之后。

    两趟都收在这里、并由**证据池与生成共用**：它们必须拿到同一批证据，否则会出现
    「界面显示的是整合资料、回答却拿原文生成」，用户一眼就看得出依据对不上。
    """
    u = understand or {}
    ethnicity, disease, intent = _slots_from_understanding(u)
    integrated_pack: TieredRetrieval | None = None
    for tier in (PARTITION_INTEGRATED, PARTITION_RAW):
        entries = await _retrieve(question, ethnicity, disease, intent,
                                  ensure_doc_coverage=ensure_doc_coverage,
                                  partition=tier)
        pre_intent = [e for e in entries if not e.fallback]
        relevant = pre_intent
        if intent and intent != "overview":
            relevant = [e for e in relevant if _intent_relevant(e, u)]
        elif tier == PARTITION_INTEGRATED:
            # overview 在 _intent_relevant 里是「无门槛」的（它同时充当泛化兜底意图），
            # 但**分档判据**不能因此退化成「整合资料里随便一条字面沾边就算命中」——
            # 那样问「研究背景」永远降级不到原文，而"整合资料答不上就翻原文"正是这个功能
            # 存在的理由。所以对整合资料库这一档额外要求命中 overview 的锚点词。
            # 原始文献库那一档不加这条：它是兜底，沾边就该拿得到，否则会出现"两档都空"。
            relevant = [e for e in relevant if _intent_anchored(e, "overview")]
        pack = TieredRetrieval(entries, relevant, pre_intent, tier)
        if relevant:
            return pack
        if tier == PARTITION_INTEGRATED:
            integrated_pack = pack
    # 两档都没命中：带整合资料库那一趟的结果回去，而不是空手而归——
    # 面板要显示「趁这次扫描了哪些资料」，全空会让人误以为检索根本没跑
    return integrated_pack or TieredRetrieval([], [], [], PARTITION_INTEGRATED)


def _intent_anchored(e: Entry, intent: str) -> bool:
    """该条目正文里有没有这个方面的锚点词（与 `_intent_relevant` 用的是同一张表）。

    单独抽出来是因为它只服务**分档判据**：不能直接复用 `_intent_relevant`，
    那个函数对 overview 一律放行。
    """
    kws = set(_INTENT_ANCHOR_KEYWORDS.get(intent, []))
    kws.update(_INTENT_RELEVANT_EXTRA.get(intent, []))
    return any(k in (e.text or "") for k in kws)


# 证据片段锚定用的意图关键词（顺序即优先级：意图词 > 疾病 > 民族）
#
# 新增的 5 档（prevention/symptoms/medication/treatment/burden）刻意**只收精确词**，
# 不为了「让每个选项都能查到」而塞同义词。原因：这张表同时是 `_intent_relevant` 的硬门槛，
# 放宽到「控制/管理/关注」这类泛词，会把不贴题的切片也判成相关证据喂给大模型，
# 答案质量反而下降。代价是这三档（预防/症状/负担）命中率很低，选到就是「未命中」提示
# ——那是如实反映知识库的收录范围，比硬凑证据好。
_INTENT_ANCHOR_KEYWORDS = {
    "risk": ["危险因素", "影响因素", "相关因素", "保护因素", "病因"],
    # 「患病情况」**不**在这一档：它已经拆成两个选项——本档只要「率」类的词，
    # 笼统的「患病情况」归兜底档（all）。不收窄的话两档会抢同一批证据，
    # 用户选了「患病率」却拿到一堆泛泛的患病情况描述。
    "prevalence": ["患病率", "发病率", "检出率"],
    "diet": ["饮食", "膳食", "营养", "食物", "脂肪酸"],
    "genetics": ["基因", "遗传", "多态性", "突变", "位点"],
    "overview": ["研究概况", "样本量", "概况"],
    # ↓ 新增 5 档
    "prevention": ["预防", "防治", "一级预防", "筛查", "筛检"],
    "symptoms": ["症状", "临床表现", "体征", "早期信号"],
    # 「药物代谢 / 药物基因组」是库里《中国多民族人群精准用药…专家共识》的实际用词；
    # 只收「用药」会让「药物基因组学」这类核心段落漏掉
    "medication": ["用药", "药物", "服药", "药物代谢", "药物基因组", "个体化用药", "血药浓度"],
    "treatment": ["治疗", "干预", "治疗率", "管理策略"],
    "burden": ["疾病负担", "经济负担", "并发症", "致残", "致死", "死亡率", "严重程度"],
    # 兜底档：**空表**。它不靠锚点词筛选——`_intent_relevant` 对它直接放行，
    # 走「全量检索 + 综合回答」。往里放词反而会把它偷偷变成又一个窄档。
    "all": [],
}

# 意图相关性判定的补充关键词（用于判断证据是否与问题所问的方面直接相关，
# 避免问饮食却把患病率切片当证据展示/送生成）
_INTENT_RELEVANT_EXTRA = {
    "diet": ["食盐", "腌制", "钠盐", "食用频率", "乳扇", "摄入"],
    "risk": ["RR", "OR", "食盐", "腌制"],
}


def _scoped_text(text: str, q_eth: str) -> str:
    """把多民族同段切片收敛到「所问民族的子段」。

    一份切片常把多个民族的内容连在一起，例如《数据资料》里的：
        傣族：…人均每日食盐摄入超过6g者占41.9%，每周食用腌制食品1次以上者达61.2%
        哈尼族：低教育水平（RR=2.18）和低收入（RR=1.47）
        白族：男性为主要危险因素（RR=0.48）
    若在整块文本上查意图关键词，问「白族饮食」时傣族的「食盐/腌制」会让整块切片
    通过饮食过滤，而白族那一段其实只有危险因素——于是患病率数据被当成饮食证据喂给
    LLM，答非所问。收敛到本民族子段后再判定，才能如实反映「本民族有没有该方面的证据」。
    """
    return _refine_eth_text(text, q_eth) if q_eth else text


def _evidence_intents(e: Entry, q_eth: str = "") -> list:
    """这条证据实际覆盖哪些意图（用于提示「仅找到关于【患病率】的背景资料」）。"""
    text = _scoped_text(e.text or "", q_eth)
    out = []
    for code, kws in _INTENT_ANCHOR_KEYWORDS.items():
        # overview 与 all 不算「某个方面」：它们不是覆盖范围，而是「没指定方面」的两档兜底。
        # 列进提示里会冒出「仅找到关于【研究概况】的背景资料」这种没意义的说法。
        if code in ("overview", "all"):
            continue
        if any(k in text for k in set(kws) | set(_INTENT_RELEVANT_EXTRA.get(code, []))):
            out.append(code)
    return out


def _intent_relevant(e: Entry, understand: Dict[str, Any]) -> bool:
    """证据是否与问题意图直接相关（topic 匹配，或片段含意图/指标关键词）。

    用于把「问饮食注意事项、却展示/采用患病率片段」的错位证据剔除：
    意图明确时，不相关的证据不能作为回答依据（LLM 会基于它硬编建议）。
    overview 与 all（两档兜底意图）不做门槛——它们本来就不指向某个方面。
    """
    u = understand or {}
    intent = u.get("intent") or u.get("query_intent")
    if not intent or intent in ("overview", "all"):
        return True
    # 民族一致性（提前拦截）：问「白族」时，带明确单一民族标签且非所问民族的证据直接排除，
    # 即使其 topic 被误标为相同意图（如「傣族吸烟/食盐」证据被标成 prevalence），也不应作为
    # 白族问题的证据，避免回答/证据材料出现无关民族内容。标为多民族/多疾病或文档切片
    # （ethnicity=None）的证据不受影响。仅当切片文本本身含所问民族时才放行（兼容对比类证据）。
    q_eth = (u.get("ethnicity") or "").strip()
    # 泛民族查询：不指向任何单一民族，民族一致性检查全部跳过——
    # 否则「问各民族遗传研究，切片里提到白族/傣族却没提 GENERAL」会被当成他族内容排除，
    # 泛民族研究段落（云南六个民族全基因组测序、25 个少数民族 Y 染色体）永远进不来。
    if q_eth and q_eth != ETHNICITY_GENERAL:
        e_eth = (e.ethnicity or "").strip()
        # 显式标注了其他单一民族（且非所问民族）的证据 → 排除（除非切片文本本身含所问民族）
        if e_eth and e_eth != q_eth and e_eth not in ("多民族", "多疾病", "各民族"):
            if q_eth not in (e.text or ""):
                return False
        # RAG_CHUNK 通常无民族标注：若切片文本「只提及他族」（傣族/哈尼族/蒙古族…）而不含所问民族，
        # 视为他族内容，排除（避免问白族答傣族/蒙古族、或把 MAOI/藏药等离题章节混入）
        if not e_eth or e_eth in ("多民族", "多疾病", "各民族"):
            _OTHER_ETHNICS = ["傣族", "哈尼族", "汉族", "蒙古族", "维吾尔族", "藏族", "回族",
                              "苗族", "彝族", "景颇族", "傈僳族", "佤族", "普米族", "布朗族",
                              "纳西族", "土家族", "满族", "布依族", "壮族", "畲族", "哈萨克族"]
            txt = e.text or ""
            if any(o in txt for o in _OTHER_ETHNICS if o != q_eth) and q_eth not in txt:
                return False
    if e.topic == intent:
        return True
    kws = set(_INTENT_ANCHOR_KEYWORDS.get(intent, []))
    kws.update(_INTENT_RELEVANT_EXTRA.get(intent, []))
    metric = u.get("metric")
    if metric:
        kws.add(metric)
    # 关键词必须在「所问民族的子段」里命中：他族的内容（傣族的食盐/腌制）不算本民族证据
    return any(k in _scoped_text(e.text or "", q_eth) for k in kws)


_CN_CHAR_RE = re.compile(r"[一-鿿]")
# 从用户问题里识别「指标词」的后缀：用户问的通常是 率 / 因素 / 情况 结尾的词
_METRIC_TAIL_CHARS = ("率", "因素", "情况")


def _question_metric_terms(question: str) -> list[str]:
    """从用户问题里提取候选「指标词」，如「肥胖率」「患病率」。

    切片里常常同时存在多处可锚点，例如问「傈僳族的肥胖率」时，同一块切片里既有
    「心脏瓣膜病患病率」又有「标化肥胖率」。泛化的意图词（患病率）会把证据片段锚到
    心脏瓣膜病那张表上，展示出与提问无关的数据。把问题里的指标词提出来置于最高优先级，
    才能锚到用户真正问的那一项。
    """
    q = (question or "").strip()
    if not q:
        return []
    out: list[str] = []
    for i, ch in enumerate(q):
        if ch not in _METRIC_TAIL_CHARS:
            continue
        # 以该后缀结尾，向前取 2~5 个汉字组成候选（长词优先，未命中的候选无害）
        for n in range(5, 1, -1):
            s = q[max(0, i - n + 1): i + 1]
            if len(s) == n and all(_CN_CHAR_RE.match(c) for c in s):
                out.append(s)
    return list(dict.fromkeys(out))


def _anchor_groups(understand: Dict[str, Any], question: str = "") -> list:
    """从理解结果构造证据片段的锚定关键词组。

    顺序即优先级（权重 n-gi），依次为：
      [用户问题里的指标词] → [意图词(具体指标+意图关键词)] → [疾病] → [民族]。
    问题指标词排第一，是为了让「用户明确问的那一项」压过泛化的意图词——
    见 _question_metric_terms 的说明。
    """
    u = understand or {}
    groups: list[list[str]] = []
    # 最高优先级：用户问题里明确问到的指标词（如「肥胖率」）
    q_terms = [t for t in _question_metric_terms(question) if len(t) >= 2]
    if q_terms:
        groups.append(q_terms)
    kw: list[str] = []
    metric = u.get("metric")
    if metric:
        kw.append(metric)
    intent = u.get("intent") or u.get("query_intent")
    if intent:
        kw.extend(_INTENT_ANCHOR_KEYWORDS.get(intent, []))
        # 相关性判定的补充词同样参与锚定：问饮食时含「食盐/腌制」的危险因素段
        # 才是回答依据所在（患病率表只是同一切片里的其他内容）
        kw.extend(_INTENT_RELEVANT_EXTRA.get(intent, []))
    if kw:
        groups.append(list(dict.fromkeys(k for k in kw if k)))
    for t in (u.get("disease"), u.get("ethnicity")):
        # 泛民族是哨兵值、不是文本里会出现的词，放进锚词组只会占一个永不命中的组
        if t and t != ETHNICITY_GENERAL:
            groups.append([t])
    return groups


def _matched_terms(groups: list, text: str) -> list[str]:
    """从锚词组里挑出**真的出现在这段文字里**的词，长词优先。

    前端据此把关键词标出来，让人一眼看出「这条证据为什么被检索到」。
    只返回真正命中的词——把没命中的也传过去，前端会白做一轮替换。
    长词排在前面：前端按顺序替换时，「糖尿病」不会被「糖尿」抢先切开。
    """
    flat = [t for grp in (groups or []) for t in grp if t]
    uniq = sorted(dict.fromkeys(flat), key=len, reverse=True)
    return [t for t in uniq if t in (text or "")]


def _intent_display_label(understand: Dict[str, Any]) -> str:
    """意图的中文展示名（未覆盖提示用）：优先具体指标，其次意图通用名。"""
    u = understand or {}
    if u.get("metric"):
        return u["metric"]
    return _INTENT_PHRASE.get(u.get("intent") or u.get("query_intent")) or "该方面"


def _miss_followups(understand: Dict[str, Any]) -> list[str]:
    """未命中 / 泛化时的「您可能还想问」：只推荐知识库里**确实有数据**的组合。

    两个方向，凑满 3 条：
      ① 同一疾病、其他有数据的民族（病问对了、但问的那个民族没数据）；
      ② 同一民族、其他有数据的疾病（民族问对了、但问的那个病没数据，如「CKM」）。

    方面固定「患病情况」这一最通用的问法——不推荐具体方面（危险因素 / 饮食等），
    因为 available_diseases / available_ethnicities 只保证「该民族有该疾病的资料」，
    不保证某个具体方面有；推荐具体方面可能点进去又是空白，正好违背「只推荐有数据的问题」。
    这些追问是**确定性**生成的，不交给 LLM：LLM 可能漏掉这一节、也可能编出库里没有的组合。
    """
    u = understand or {}
    eth = (u.get("ethnicity") or "").strip()
    dis = (u.get("disease") or "").strip()
    vocab = dict_provider.get()
    ethnicities = vocab.get("ethnicities") or []
    diseases = vocab.get("diseases") or []
    out: list[str] = []
    seen: set[tuple[str, str]] = set()

    def add(e: str, d: str) -> None:
        if not e or not d or e == ETHNICITY_GENERAL:
            return
        key = (e, d)
        if key in seen:
            return
        seen.add(key)
        out.append(f"{e}{d}患病情况如何？")

    if dis:
        for e in kb.available_ethnicities(dis, ethnicities, exclude=eth):
            add(e, dis)
    if eth and eth != ETHNICITY_GENERAL:
        for d in kb.available_diseases(eth, diseases, exclude=dis):
            add(eth, d)
    return out[:3]


def _no_evidence_answer(understand: Dict[str, Any]) -> dict:
    """检索无直接相关证据时的诚实回答（不交给 LLM 硬编）。"""
    label = _intent_display_label(understand)
    u = understand or {}
    topic = f"{_eth_label(u.get('ethnicity'))}{u.get('disease') or ''}".strip() or "该问题"
    return {
        "conclusion": (f"已检索知识库全部资料，未找到与「{topic}的{label}」直接相关的证据，"
                       "现有研究证据未覆盖该问题。"),
        "detailed": (f"知识库现有资料未包含「{label}」方面的直接证据。"
                     "可以尝试更换问题角度（如患病率、危险因素等），"
                     "或补充相关文献后再提问。"),
        "actions": ("建议您换个角度提问该民族在其他方面的研究（如患病情况、危险因素、饮食生活），"
                    "或就个人健康问题到基层医疗机构做一次基础筛查并咨询医生。"),
        "applicable": "—",
        "timeRegion": "未明确",
        "cautions": "本结论基于知识库检索结果生成；未覆盖不代表该问题没有任何研究支持。",
        "followUps": _miss_followups(understand),
        "format": "text",
        "table": None,
        "chart": None,
        "engine": "no-evidence",
    }


async def _fallback_answer(understand: Dict[str, Any],
                           ethnicity: str | None, disease: str | None) -> dict:
    """检索为空时的回答：优先 LLM 兜底（告知数据暂缺 + 用您可能还想问引导询问其他有数据的疾病），
    失败则退回无证据模板。"""
    vocab = dict_provider.get().get("diseases") or []
    available = kb.available_diseases(ethnicity, vocab, exclude=disease)
    try:
        fallback = await generate_fallback_answer(understand, available)
        if fallback:
            # 正文（conclusion/detailed）交给 LLM 组织「数据暂缺」的话术，
            # 但追问按钮统一换成确定性的「基于知识库实际数据」推荐——LLM 那三条可能漏掉，
            # 也可能编出库里没有的组合，点进去又是一次空白。
            followups = _miss_followups(understand)
            if followups:
                fallback["followUps"] = followups
            return fallback
    except Exception:
        logger.exception("generate_fallback_answer raised")
    return _no_evidence_answer(understand)


@app.post("/api/understand")
async def understand_frontend(req: UnderstandRequest):
    """前端验证步骤：补齐 status / confidence，使前端 verify 走真实理解结果而非降级。

    分级：
      - 民族、疾病都缺失        → fail
      - 缺民族或缺疾病          → clarify
      - 民族疾病齐全但查询意图不明确（未识别 / 命中模糊词且未指明具体指标）→ clarify，
        由前端弹出「你想了解哪方面」让用户选择，而不是替用户猜测后直接放行
      - 民族、疾病、意图三者明确 → pass
    """
    base = await understand(req.question, req.history)
    ethnicity = base.get("ethnicity")
    disease = base.get("disease")
    intent = base.get("intent")
    intent_clear = base.get("intent_clear")
    clarity_reason = base.get("clarity_reason") or ""
    metric = base.get("metric")

    # 只给了疾病、没提民族的科普问题（如「糖尿病患者饮食要注意什么？」）：
    # 不追问民族，按泛民族全库范围检索。知识库里本来就有《中国居民膳食指南》这类
    # 与民族无关的通用资料，硬要用户补民族反而是答得上来却不让答。
    if not ethnicity and disease and intent:
        ethnicity = ETHNICITY_GENERAL

    # 医疗安全拦截：用药/诊疗求助一律「放行」到拒绝分支（由 /api/generate 返回固定话术）。
    # 不能判成 fail——那前端会显示「无法理解，请补充民族+疾病」，用户会以为是系统故障，
    # 而真正该传达的是「这类问题我不能答，请去医院」。
    if base.get("medical_advice"):
        return {
            "ethnicity": ethnicity,
            "disease": disease,
            "intent": intent,
            "query_intent": intent,
            "metric": metric,
            "intent_label": None,
            "question_type": "用药咨询",
            "standardized_question": None,
            "confidence": 0.9,
            "status": "pass",
            "clarity_reason": "",
            "generalized": False,
            "missing": [],
            "ethnicity_from_context": False,
            "disease_from_context": False,
            "medical_advice": True,
            "engine": base.get("engine"),
        }

    # 泛化检索标记：民族明确、只缺疾病时不判失败，放行去用「民族 + 意图」检索，
    # 由前端给出「您是否想了解…」的降级提示。硬拒绝会让追问走进死胡同。
    generalized = False
    missing: list[str] = []
    if not ethnicity and not disease:
        status, confidence = "fail", 0.0
        missing = ["ethnicity", "disease"]
    elif not ethnicity:
        # 有疾病没民族：定位不到具体民族，只能澄清
        status, confidence = "clarify", 0.5
        missing = ["ethnicity"]
    elif not disease:
        if intent:
            # 泛化放行：只有民族 + 意图，按「民族 + 意图」检索。
            # 置信度落在 0.6~0.8：低于三要素齐全的 0.9，但足以放行、不触发澄清。
            status, confidence = "pass", 0.7
            generalized = True
            missing = ["disease"]
        else:
            status, confidence = "clarify", 0.4
            missing = ["disease", "intent"]
    elif not intent_clear:
        # 民族 + 疾病已明确，但意图宽泛或缺失 → 让用户确认具体想了解哪方面
        status, confidence = "clarify", 0.6
    else:
        status, confidence = "pass", 0.9
    if ethnicity and disease:
        if metric:
            # 用户问到了具体指标（如「患病率」）→ 标准化问题落到该指标本身；
            # 措辞后缀按指标所属意图族选取（知晓率等率类指标 → 「是多少」）
            fam = metric_family(metric) or intent
            standardized = f"{_eth_label(ethnicity)}{disease}的{metric}{_METRIC_TAIL.get(fam, '')}"
        else:
            standardized = f"{_eth_label(ethnicity)}{disease}的{_INTENT_PHRASE.get(intent, '相关情况')}"
    elif ethnicity:
        # 泛化检索：没有疾病，用「民族 + 意图」组标准化问句
        standardized = f"{_eth_label(ethnicity)}的{_INTENT_PHRASE.get(intent, '相关情况')}"
    else:
        standardized = None
    return {
        "ethnicity": ethnicity,
        "disease": disease,
        "intent": intent,
        "query_intent": intent,
        "metric": metric,
        # 查询意图展示名：命中具体指标时为指标名（患病率），否则为通用意图名（患病情况）
        "intent_label": (metric or _INTENT_PHRASE.get(intent)) if intent else None,
        "question_type": (f"{metric}查询" if metric else _INTENT_QTYPE.get(intent)) if intent else None,
        "standardized_question": standardized,
        "confidence": confidence,
        "status": status,
        "clarity_reason": clarity_reason,
        # 缺疾病但放行时置 True，前端据此提示「暂未收录…您是否想了解…」
        "generalized": generalized,
        # 本轮仍缺失的槽位（澄清文案与前端提示据此措辞）
        "missing": missing,
        # 实体是从历史对话继承来的（追问场景），供前端提示「已接着上文理解」
        "ethnicity_from_context": bool(base.get("ethnicity_from_context")),
        "disease_from_context": bool(base.get("disease_from_context")),
        # 用药/诊疗求助：前端据此跳过民族校验，后端据此不做检索、直接返回安全话术
        "medical_advice": bool(base.get("medical_advice")),
        "engine": base.get("engine"),
    }


def _compute_snippet_offsets(full_text: str, snippet_text: str) -> tuple[int, int]:
    """在 full_text 中定位 snippet_text 的字符偏移；支持清洗后片段的模糊匹配。"""
    if not full_text or not snippet_text:
        return -1, -1
    # 优先精确匹配
    idx = full_text.find(snippet_text)
    if idx != -1:
        return idx, idx + len(snippet_text)
    # fallback 1：取 snippet 前 20 个字符做前缀匹配（应对片段被 _clean_fragment 改写的情况）
    prefix = snippet_text[:20] if len(snippet_text) >= 20 else snippet_text
    idx = full_text.find(prefix)
    if idx != -1:
        end = idx + len(snippet_text)
        # 确保 end 不越界
        return idx, min(end, len(full_text))
    # fallback 2：归一化匹配——去掉空白与排版符号（| # / ： ； 等）后按内容字符匹配，
    # 并把归一化位置映射回原文位置。应对 markdown 表格被改写成可读文本的场景。
    _DROP = set(" \t\r\n|#/：:；;，,。、*—-")
    norm_full, idx_map = [], []
    for i, ch in enumerate(full_text):
        if ch not in _DROP:
            norm_full.append(ch)
            idx_map.append(i)
    norm_frag = "".join(ch for ch in snippet_text if ch not in _DROP)
    if len(norm_frag) >= 6:
        ntext = "".join(norm_full)
        pos = ntext.find(norm_frag)
        if pos != -1:
            start = idx_map[pos]
            end_pos = min(pos + len(norm_frag) - 1, len(idx_map) - 1)
            # end 取原文中最后一个被映射字符的下一位，再吞掉紧随的排版字符，覆盖整段
            end = idx_map[end_pos] + 1
            while end < len(full_text) and full_text[end] in " \t\r\n|":
                end += 1
            return start, min(end, len(full_text))
    return -1, -1


def _build_candidates(entries: list, understand: dict, question: str = "") -> list[dict]:
    """把检索到的条目组装成候选资料列表。

    规则：
      - 论文证据（KB_EVIDENCE）保持单条候选（每条证据对应一份候选，与现有行为一致）。
      - 知识库文档切片（RAG_CHUNK）按 doc_id 聚合成单份候选，携带全文与所有匹配片段，
        供前端「左全文·右证据」浏览。
    """
    from collections import defaultdict
    terms = _anchor_groups(understand, question)

    # 分离论文证据与文档切片
    paper_entries = [e for e in entries if e.source_type == "KB_EVIDENCE"]
    doc_entries = [e for e in entries if e.source_type == "RAG_CHUNK"]

    candidates = [_entry_to_candidate(e, understand, question) for e in paper_entries]

    # 按 doc_id 聚合文档切片
    doc_groups: defaultdict[int, list] = defaultdict(list)
    for e in doc_entries:
        if e.doc_id is not None:
            doc_groups[e.doc_id].append(e)

    intent = understand.get("intent") if understand else None
    for doc_id, group in doc_groups.items():
        full_text = kb.get_document_full_text(doc_id)
        if not full_text:
            # 兼容旧数据：用该文档全部切片拼接全文（而不只是检索命中的切片）
            all_chunks = [e.text for e in kb.entries
                          if e.source_type == "RAG_CHUNK" and e.doc_id == doc_id]
            full_text = "\n".join(all_chunks)
        title = group[0].doc_title or ""
        evidences: list[dict] = []
        for e in group:
            # 右栏只展示「与问题意图强相关」的片段：避免共识 PDF 在问糖尿病患病率时，
            # 把含「发病率」的乙肝/药动学切片当成证据片段列在右侧误导用户
            if not _intent_relevant(e, understand):
                continue
            frag = (e.text or "").strip()
            if len(frag) > 220:
                frag = _focused_snippet(frag, terms, (understand or {}).get("disease"),
                                        (understand or {}).get("ethnicity"))
            frag = _refine_eth_text(_clean_fragment(frag), (understand or {}).get("ethnicity"))
            start, end = _compute_snippet_offsets(full_text, frag)
            evidences.append({
                "topic": e.topic,
                "fragment": frag,
                "startOffset": start,
                "endOffset": end,
                # 命中的关键词：前端在片段里标出来，说明「为什么这条被检索到」
                "matchedTerms": _matched_terms(terms, frag),
                "page": e.page_no,
            })
        # 状态判定（三级）：
        # - 文档完全不含问题疾病（完整词+二元组）→ none（被扫描过，但无直接证据）
        # - 含疾病但无该意图的强相关片段 → partial（提到疾病，但无此方面证据）
        # - 有强相关片段 → sufficient
        disease = (understand or {}).get("disease")
        disease_terms = ([disease] + [disease[i:i + 2] for i in range(len(disease) - 1)]
                         if disease else [])
        disease_terms = [t for t in disease_terms if len(t) >= 2]
        disease_hit = bool(disease_terms) and any(
            any(dt in (e.text or "") for dt in disease_terms) for e in group
        )
        if evidences:
            status = "sufficient"
        else:
            # 无任何强相关片段：该文档被扫描过，但无此问题的直接证据
            status = "none"
        candidates.append({
            "source": "kb",
            "id": str(doc_id),
            "title": title,
            "content": group[0].text,          # 向后兼容
            "fullText": full_text,
            "ethnicity": group[0].ethnicity,
            "disease": group[0].disease,
            "status": status,
            "evidence": evidences,
        })
    return candidates


@app.post("/api/evidence-pool")
async def evidence_pool_endpoint(req: FrontendPoolRequest):
    # 医疗安全拦截：不做任何检索，避免把不相干片段当「依据」展示出来
    if (req.understand or {}).get("medical_advice"):
        return {
            "status": "none",
            "candidates": [],
            "evidence_found": False,
            "evidence": [],
            "blocked": True,
            "reason": "该问题属于用药 / 诊疗咨询，已触发医疗安全边界，不进行知识库检索。",
        }
    ethnicity, disease, intent = _slots_from_understanding(req.understand)
    # 两趟检索：先整合资料库，命中不了才降级翻原始文献库（见 _retrieve_tiered）。
    # ensure_doc_coverage：每个知识库文档都至少扫描一次（未命中的文档以保底切片出现在候选列表，
    # 标注「无直接证据/部分相关」），保证每份上传资料每个问题都被检索到
    tiered = await _retrieve_tiered(req.question, req.understand, ensure_doc_coverage=True)
    entries = tiered.candidates
    if not entries:
        return {
            "status": "none",
            "candidates": [],
            "evidence_found": False,
            "evidence": [],
            "retrievalTier": tiered.tier,
            "reason": "未检索到与问题相关的证据，请尝试更换民族/疾病或调整问题描述。",
        }
    # 送进回答生成的证据只取真正相关的条目（保底切片仅用于展示「该文档被扫描过」）；
    # 意图过滤已经在 _retrieve_tiered 里做过（分档判据依赖它），这里只去重限量
    real_entries = _dedupe_entries(tiered.relevant, config.MAX_EVIDENCE)
    # 记下意图过滤前的候选：全部被过滤掉时，用它说明「仅找到关于哪个方面的背景资料」
    pre_intent_entries = tiered.pre_intent
    evidence = [_entry_to_evidence(e, req.understand, req.question) for e in real_entries]
    _dedupe_titles(evidence)
    candidates = _build_candidates(entries, req.understand or {}, req.question)
    # 文档级候选去重标题
    seen: dict[str, int] = {}
    for c in candidates:
        t = c.get("title") or "来源资料"
        seen[t] = seen.get(t, 0) + 1
        if seen[t] > 1:
            c["title"] = f"{t}（片段{seen[t]}）"
    scanned = len({e.doc_id for e in entries if e.source_type == "RAG_CHUNK"})
    if not real_entries:
        label = _intent_display_label(req.understand)
        # 区分两种「空」：知识库里压根没有相关证据 vs 有背景资料但都不对应该意图。
        # 后者要说清「仅找到关于【患病率】的背景资料」，否则管理员会误以为检索到了证据，
        # 而回答其实是 LLM 凭空组织的。
        # 只报告排名最高那条证据覆盖的方面：它是系统实际会拿来回答的东西。
        # 把所有被丢弃的条目都罗列出来会得到一长串与问题无关的方面（甚至包含所问方面
        # 本身，自相矛盾），反而误导管理员。
        top = pre_intent_entries[0] if pre_intent_entries else None
        got = [c for c in (_evidence_intents(top, ethnicity or "") if top else [])
               if c != intent]
        if intent and intent != "overview" and got:
            got_label = "、".join(_INTENT_PHRASE.get(c, c) for c in got)
            reason = (f"未找到与【{label}】直接相关的证据片段，"
                      f"仅找到关于【{got_label}】的背景资料。")
        else:
            reason = (f"已扫描知识库全部 {scanned} 份资料，"
                      f"但未找到与「{label}」直接相关的证据。")
        return {
            "status": "none",
            "candidates": candidates,
            "evidence_found": False,
            "evidence": [],
            "intent_missing": bool(intent and intent != "overview"),
            # 两档都没命中（_retrieve_tiered 只在都空时才返回 integrated 那一趟）：
            # 明说两个库都翻过了，免得管理员以为只查了整合资料就下结论
            "retrievalTier": tiered.tier,
            "tierNote": "整合资料库与原始文献库均已检索，均无直接相关证据",
            "reason": reason,
        }
    # 泛化检索（没识别出疾病，只按民族 + 意图搜）：证据只能算「部分相关」——
    # 它回答的是民族整体健康资料，不是该民族该疾病的专属数据。即使命中多条也不给 🟢。
    generalized = bool((req.understand or {}).get("generalized"))
    if generalized:
        eth_slot = (req.understand or {}).get("ethnicity") or ""
        if eth_slot == ETHNICITY_GENERAL:
            reason = (f"已识别为泛民族查询，检索全库民族相关研究"
                      f"（{scanned} 份资料，命中 {len(real_entries)} 条）。"
                      f"以下为针对多个民族的整体研究，具体到某个民族的数据需进一步查询。")
        else:
            reason = (f"未识别出具体疾病，已按【民族 + 意图】泛化检索（{scanned} 份资料，"
                      f"命中 {len(real_entries)} 条）。以下为民族整体健康资料，"
                      f"非该民族该疾病的专属数据。")
        return {
            "status": "partial",
            "candidates": candidates,
            "evidence_found": True,
            "evidence": evidence,
            "generalized": True,
            "pan_ethnic": eth_slot == ETHNICITY_GENERAL,
            "retrievalTier": tiered.tier,
            "tierNote": _tier_note(tiered.tier, len(real_entries)),
            "reason": reason,
        }
    return {
        "status": "sufficient" if len(real_entries) >= 2 else "partial",
        "candidates": candidates,
        "evidence_found": True,
        "evidence": evidence,
        "retrievalTier": tiered.tier,
        "tierNote": _tier_note(tiered.tier, len(real_entries)),
        "reason": f"已扫描知识库全部 {scanned} 份资料，检索到 {len(real_entries)} 条相关证据。",
    }


def _tier_note(tier: str, hit: int) -> str:
    """给后台一句话说清「这次走的是哪一档」。需求要求这里必须能一眼看出有没有降级。"""
    if tier == PARTITION_RAW:
        return f"整合资料库未命中，已降级检索原始文献库，命中 {hit} 条"
    return f"命中整合资料库 {hit} 条（未启用原始文献库）"


@app.post("/api/generate")
async def generate_endpoint(req: FrontendGenerateRequest):
    # 医疗安全拦截（红线）：用药/诊疗求助不检索、不喂证据，直接返回固定拒绝话术。
    if (req.understand or {}).get("medical_advice"):
        return {"answer": _safety_answer(), "evidence_found": False, "blocked": True}
    ethnicity, disease, intent = _slots_from_understanding(req.understand)
    # 槽位兜底：若客户端传入的 understand 结构不完整（缺民族/疾病），
    # 用问题重新理解补齐，避免检索失去疾病过滤导致回答跑题
    if (not ethnicity or not disease) and req.question:
        try:
            base = await understand(req.question, req.history)
            ethnicity = ethnicity or base.get("ethnicity")
            disease = disease or base.get("disease")
            intent = intent or base.get("intent")
            if not req.understand:
                req.understand = base
            else:
                req.understand.setdefault("disease", disease)
                req.understand.setdefault("ethnicity", ethnicity)
                req.understand.setdefault("intent", intent)
        except Exception:
            pass
    # 与证据池走同一套两趟检索（先整合资料库、命中不了才降级）：
    # 必须拿到同一批证据，否则界面显示的是整合资料、回答却拿原文生成，依据对不上
    tiered = await _retrieve_tiered(req.question, req.understand)
    # 意图过滤已在 _retrieve_tiered 内完成（分档判据依赖它），这里只去重限量
    entries = _dedupe_entries(tiered.relevant, config.MAX_EVIDENCE)
    if not entries:
        return {"answer": await _fallback_answer(req.understand or {}, ethnicity, disease),
                "evidence_found": False}
    answer = await generate_answer(req.question, req.understand or {}, entries)
    # 泛化检索（疾病没被识别出来，回答的是该民族的通用资料）：LLM / 模板给出的追问
    # 可能仍指向不存在的「该民族 × 该疾病」组合，统一换成基于知识库实际数据的推荐，
    # 避免用户点进去又是一次空白。
    if (req.understand or {}).get("generalized"):
        followups = _miss_followups(req.understand or {})
        if followups:
            answer["followUps"] = followups
    return {"answer": answer, "evidence_found": True}


@app.post("/api/retrieve")
async def retrieve_endpoint(req: FrontendPoolRequest):
    # 医疗安全拦截：用药/诊疗求助不返回任何证据
    if (req.understand or {}).get("medical_advice"):
        return {
            "evidence_found": False,
            "evidence": [],
            "blocked": True,
            "reason": "该问题属于用药 / 诊疗咨询，已触发医疗安全边界，不进行知识库检索。",
        }
    ethnicity, disease, intent = _slots_from_understanding(req.understand)
    # 与证据池同一套两趟检索：先整合资料库、命中不了才降级翻原始文献库
    tiered = await _retrieve_tiered(req.question, req.understand)
    # entries_all 用于「仅找到关于【X】的背景资料」，取意图过滤前的那批；entries 是要展示的
    entries_all = tiered.pre_intent
    entries = tiered.relevant
    if not entries:
        # 区分两种「空」：完全没有证据 vs 有证据但都不对应该意图。
        # 后者必须明说「仅找到关于【患病率】的背景资料」，让管理员一眼看出检索的真实
        # 情况；绝不能把不相干的背景资料硬当作该问题的证据展示出去。
        if entries_all:
            want = _intent_display_label(req.understand)
            got = sorted({c for e in entries_all for c in _evidence_intents(e, ethnicity or "")})
            got_label = "、".join(_INTENT_PHRASE.get(c, c) for c in got) or "其他方面"
            return {
                "evidence_found": False,
                "evidence": [],
                "intent_missing": True,
                "intent": intent,
                "retrievalTier": tiered.tier,
                "reason": f"未找到与【{want}】直接相关的证据片段，仅找到关于【{got_label}】的背景资料。",
            }
        return {
            "evidence_found": False,
            "evidence": [],
            "intent_missing": bool(intent and intent != "overview"),
            "intent": intent,
            "retrievalTier": tiered.tier,
            "reason": "未检索到与问题相关的证据。",
        }
    evidence = [_entry_to_evidence(e, req.understand, req.question) for e in entries]
    _dedupe_titles(evidence)
    return {
        "evidence_found": True,
        "evidence": evidence,
        "retrievalTier": tiered.tier,
        "reason": f"检索到 {len(entries)} 条相关文献证据。",
    }


@app.get("/api/papers")
async def papers_endpoint():
    papers = []
    for idx, p in enumerate(kb.papers_meta, 1):
        papers.append({
            "id": str(p.get("id", idx)),
            "title": p.get("title", ""),
            "authors": p.get("authors"),
            "journal": p.get("journal"),
            "year": p.get("year"),
            "volume": p.get("volume"),
            "doi": p.get("doi"),
            "url": p.get("url"),
            "ethnicity": p.get("ethnicity"),
            "disease": p.get("disease"),
            "population": p.get("population"),
            "studyYear": p.get("studyYear"),
            "studyType": p.get("studyType"),
            "findings": p.get("findings"),
            "limitation": p.get("limitation"),
            "evidences": p.get("evidences", []),
        })
    return papers


# ---------- 网页抓取（知识库「从链接导入」）----------
@app.post("/api/ai/kb/fetch-url")
async def fetch_url_endpoint(req: FetchUrlRequest):
    """
    抓取网页正文并清洗，供 Java 侧落库。

    白名单由 Java 侧校验——它才是业务规则的拥有者，而且必须在请求发出**之前**拦，
    否则这个端点就成了 SSRF 跳板。这里返回 finalUrl 让 Java 复检重定向目标。
    """
    try:
        return {"ok": True, **await fetch_article(req.url, timeout=req.timeout or 20.0)}
    except ArticleFetchError as e:
        # 消息面向管理员，直接透出；不记 exception 栈（这是预期内的失败，不是 bug）
        return {"ok": False, "error": str(e)}
    except Exception as e:
        logger.exception("fetch-url 未预期失败: %s", req.url)
        return {"ok": False, "error": f"抓取失败：{e.__class__.__name__}"}


# ---------- 知识库词表（高级检索页下拉的数据源）----------
# 意图的规范顺序。两个作用：
#   1. 下拉的排序（下拉选项本身来自前端 INTENT_LABELS，这里排的是 `pairs[].intents`）
#   2. 下拉默认选中 `pairs[].intents[0]`，所以顺序即「最核心的问法」优先
# 排序依据是使用频率，不是知识库覆盖度：prevalence/risk/prevention 是最常问的三个，
# 哪怕后两个在现有语料里命中很少，用户也仍然最可能先点它们。
_INTENT_ORDER = [
    "prevalence", "risk", "prevention", "diet", "genetics",
    "medication", "symptoms", "treatment", "burden", "overview", "all",
]


def _dedup_keep_order(items: list) -> list:
    """去重但保留首次出现的顺序（下拉顺序要稳定，不能用 set）"""
    out: list = []
    for x in items:
        if x and x not in out:
            out.append(x)
    return out


class KbVocabRequest(BaseModel):
    """候选民族 / 疾病词表。

    候选由**前端**传入（前端 `medicalVocab.ts` 那份覆盖面最广，含心脏瓣膜病、NAFLD
    等后端兜底词表没有的疾病），服务端只回答「这些候选里哪些在知识库里真有资料」。
    这样 Python 不必再维护与前端、Java 各不相同的第四份词表。
    """
    ethnicities: list[str] = []
    diseases: list[str] = []


@app.post("/api/ai/kb/vocab")
async def kb_vocab_endpoint(req: KbVocabRequest):
    """列出「知识库里真有资料」的民族 × 疾病组合，供高级检索页的级联下拉使用。

    <p>判定方式与 `KnowledgeBase.available_diseases` 一致：**扫描 RAG_CHUNK 切片文本**，
    切片里同时出现该民族名与该疾病名，才算有这个组合的数据。不能读
    `Entry.ethnicity` / `Entry.disease` —— RAG_CHUNK 这两个字段恒为 None，
    实体标注只存在于 KB_EVIDENCE 上（见 kb.py 检索段的注释）。</p>

    <p>`intents` 复用 `_evidence_intents`：它本来就在算「这条切片覆盖哪些方面」。
    于是检索未命中时，前端能说清「该组合实际收录的是【患病情况】」，而不是干巴巴一句
    「暂无数据」。</p>
    """
    # 候选 = 前端给的 ∪ 服务端兜底词表。兜底是必要的：前端那份词表可能落后于知识库，
    # 若只信前端，库里有资料、前端却没列出的民族/疾病会被永久隐藏。
    fallback = dict_provider.get()
    eth_cands = _dedup_keep_order(list(req.ethnicities) + list(fallback.get("ethnicities") or []))
    dis_cands = _dedup_keep_order(list(req.diseases) + list(fallback.get("diseases") or []))

    chunks = [e for e in kb.entries if e.source_type == "RAG_CHUNK" and e.text]
    pairs: list[dict] = []
    eth_hits: list[str] = []
    for eth in eth_cands:
        eth_entries = [e for e in chunks if eth in e.text]
        if not eth_entries:
            continue
        eth_hits.append(eth)
        for dis in dis_cands:
            matched = [e for e in eth_entries if dis in e.text]
            if not matched:
                continue
            hit: set[str] = set()
            for e in matched:
                hit.update(_evidence_intents(e, eth))
            intents = [c for c in _INTENT_ORDER if c in hit]
            pairs.append({"ethnicity": eth, "disease": dis, "intents": intents})

    return {
        "ethnicities": eth_hits,
        "diseases": _dedup_keep_order([p["disease"] for p in pairs]),
        "pairs": pairs,
        # 意图 → 标准问句后缀。前端把选中的三个槽位拼成问句送检索，措辞必须与
        # `_INTENT_PHRASE`（标准化问句用的那份）逐字一致，否则 BM25 打分与对话页不同，
        # 同一个组合在两处可能给出不同的证据排序。把它放在响应里，前端就不必再抄一份
        # ——这正是本项目反复吃亏的「同一逻辑多份拷贝」。
        # 注意它**不是**界面标签：界面上 diet 写「饮食与生活方式」，标准问句里是「饮食情况」。
        "intentPhrases": {c: _INTENT_PHRASE.get(c, "相关情况") for c in _INTENT_ORDER},
        # 意图 → **检索硬门槛用的锚点词**。补录文献时最该先知道的就是这个：
        # `_intent_relevant` 判定一条切片属不属于某个方面，有两条路——「切片 topic 标签命中」
        # 或「正文命中锚点词」。而新入库的切片**没有 topic 标签**（kb.py 不给 RAG_CHUNK 写 topic），
        # 所以实际只剩后面那条。正文里一个锚点词都没出现，那份资料就永远检索不到，
        # 而且不报错——用户只看到「未找到相关证据」。
        # 放在响应里而不是让前端抄一份：它就是服务端判定逻辑本身，抄一份必然漂移。
        "intentKeywords": {c: _INTENT_ANCHOR_KEYWORDS.get(c, []) for c in _INTENT_ORDER},
    }


# ---------- 摄取同步（Java 摄取文档后推送切片；删除时通知移除） ----------
@app.post("/api/ai/kb/documents")
async def add_document(req: DocumentSyncRequest):
    added = kb.add_document(
        req.documentId, req.title, req.fileName,
        [c.model_dump() for c in req.chunks],
        full_text=req.fullText,
        source_level=req.sourceLevel,
        source_org=req.sourceOrg,
        source_url=req.sourceUrl,
        partition=req.partition)
    if req.fullText:
        kb.set_document_full_text(req.documentId, req.title, req.fileName, req.fullText,
                                  source_level=req.sourceLevel,
                                  source_org=req.sourceOrg,
                                  source_url=req.sourceUrl,
                                  partition=req.partition)
    await kb.rebuild_document_index()
    return {"ok": True, "documentId": req.documentId, "added": added}


# 两个路径指向同一个实现：
#   /api/kb/documents/{doc_id}    —— Java 内部调用（前端到不了，vite 把 /api/kb 代理到 8080）
#   /api/ai/kb/documents/{doc_id} —— 前端调用（/api 兜底代理到本服务 8000）
@app.get("/api/kb/documents/{doc_id}")
@app.get("/api/ai/kb/documents/{doc_id}")
async def get_document(doc_id: int):
    """获取文档全文与已存储的元数据（供前端「点证据片段 → 打开原文并定位高亮」）"""
    full_text = kb.get_document_full_text(doc_id)
    if not full_text:
        # 兼容旧数据：尝试从切片拼接全文
        chunks = [e.text for e in kb.entries
                  if e.source_type == "RAG_CHUNK" and e.doc_id == doc_id]
        full_text = "\n".join(chunks)
    # 查找该文档的任意一条切片以获取标题
    title = ""
    for e in kb.entries:
        if e.source_type == "RAG_CHUNK" and e.doc_id == doc_id:
            title = e.doc_title
            break
    return {"doc_id": doc_id, "title": title, "full_text": full_text}


@app.delete("/api/ai/kb/documents/{document_id}")
async def remove_document(document_id: int):
    removed = kb.remove_document(document_id)
    return {"ok": True, "documentId": document_id, "removed": removed}


# ---------- AI 证据整理（文档摄取后：LLM 提取证据 → 入库 → 进入检索） ----------

@app.post("/api/ai/kb/evidence/extract")
async def extract_evidence_endpoint(req: EvidenceExtractRequest):
    """Java 摄取文档后调用：LLM 从切片中提取结构化证据（best-effort）"""
    evs = await extract_evidences(
        req.title, req.ethnicity, req.disease,
        [c.model_dump() for c in req.chunks])
    return {"ok": True, "documentId": req.documentId, "evidences": evs}


@app.post("/api/ai/kb/summarize")
async def summarize_endpoint(req: SummarizeRequest):
    """Java 解析/上传文档后调用：LLM 对整份资料生成内容摘要（best-effort，失败返回空串）"""
    s = await summarize_document(req.title, req.text)
    return {"ok": True, "summary": s}


@app.post("/api/ai/kb/extract-fields")
async def extract_fields_endpoint(req: ExtractFieldsRequest):
    """文献补录工作台：把粘贴/导入的正文抽成「民族 × 指标 = 数值」的数据行。

    与 `/api/ai/kb/evidence/extract` 的分工：那个抽的是**可溯源的证据句**，直接进检索索引；
    这里抽的是**表格里的一行**，供工作台拼成汇编文档——最终入索引的是那份拼好的文档。
    抽取失败返回空列表（best-effort），由工作台提示管理员手工填写。
    响应里的 `dropped` 是被丢弃的行数（数值或出处对不上原文），前端应当提示管理员。
    """
    result = await extract_rows(req.text, req.ethnicity, req.disease)
    return {"ok": True, **result}


@app.post("/api/ai/kb/evidence")
async def push_evidence_endpoint(req: EvidencePushRequest):
    """Java 保存证据到 evidence 表后推送：作为 KB_EVIDENCE 进入检索（幂等）"""
    added = kb.add_dynamic_evidence(
        req.paper, [e.model_dump() for e in req.evidences])
    await kb.rebuild_document_index()
    return {"ok": True, "added": added}


@app.delete("/api/ai/kb/evidence/document/{document_id}")
async def remove_document_evidence(document_id: int):
    """文档删除时移除其动态证据（同步 Java 级联删除）"""
    removed = kb.remove_document_evidences(document_id)
    return {"ok": True, "documentId": document_id, "removed": removed}


@app.delete("/api/ai/kb/evidence/paper/{paper_id}")
async def remove_paper_evidence(paper_id: int):
    """手动录入来源论文的全部证据被删除时移除其动态证据（无 doc_id 的 paper 维度）"""
    removed = kb.remove_paper_evidences(paper_id)
    return {"ok": True, "paperId": paper_id, "removed": removed}
