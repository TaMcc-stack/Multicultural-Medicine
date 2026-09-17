"""知识库：真实文献证据片段（papers.json）+ 文档切片（Java 摄取同步）
+ 动态证据（AI 提取，dynamic_evidences.json 持久化）。
BM25 + 向量混合检索（含民族/疾病加权）。"""
import json
import logging
import math
import re
from dataclasses import dataclass, field

from .. import config
from .embeddings import embed_texts, hashing_embed, tokenize
from .tables import extract_kv_row, kv_snippet

logger = logging.getLogger("ai-service.kb")

# 动态证据持久化文件：AI 从上传文档提取的证据片段（随文档增删）
DYNAMIC_EVIDENCE_PATH = config.BASE_DIR / "data" / "dynamic_evidences.json"
# 文档原始切片持久化文件：Java 摄取同步的 RAG_CHUNK（随文档增删；落盘避免 ai-service 重启后切片丢失）
DOCUMENT_CHUNKS_PATH = config.BASE_DIR / "data" / "document_chunks.json"
# 文档全文持久化文件：供「左全文 · 右证据」浏览模式使用
KB_DOCUMENTS_PATH = config.BASE_DIR / "data" / "kb_documents.json"

# 中文（CJK 统一表意文字）
_CJK_RE = re.compile(r"[\u4e00-\u9fff]")


def is_english_text(text: str | None) -> bool:
    """判断文本是否为英文主导（无中文且英文字母占相当比例）。

    知识库证据片段不使用英文文献：论文标题或证据内容为英文主导时，
    装载阶段即跳过，检索层再做兜底过滤。
    """
    if not text:
        return False
    if _CJK_RE.search(text):
        return False
    letters = sum(1 for c in text if "a" <= c <= "z" or "A" <= c <= "Z")
    return letters >= 8 and letters >= len(text) * 0.3


def is_reference_list(text: str | None) -> bool:
    """判断文本是否为文末参考文献著录列表（如共识 PDF 的参考文献章节切片）。

    特征：中文文献著录格式「[J].」出现多次（一条著录一个 [J]）。
    参考文献列表不是文档实质内容，不应作为证据被检索召回。
    """
    if not text:
        return False
    return text.count("[J]") >= 2 or text.count("[J") >= 3


# ── 知识库分区 ────────────────────────────────────────────────────
# integrated 整合资料库（多篇文献提炼的汇编，《数据资料》那种）—— 检索时优先；
# raw        原始文献库（论文原文 PDF、官方完整报告）—— 整合资料命中不了才降级用它。
# 必须在 Entry 之前定义：dataclass 的字段默认值在类体求值时就要求名已存在。
PARTITION_INTEGRATED = "integrated"
PARTITION_RAW = "raw"


@dataclass
class Entry:
    """一条可检索的证据条目（文献证据片段或文档切片）"""
    entry_id: int
    text: str
    source_type: str            # KB_EVIDENCE（真实文献证据） / RAG_CHUNK（上传文档切片）
    ethnicity: str | None = None
    disease: str | None = None
    topic: str | None = None
    section: str | None = None
    page_no: int | None = None
    doc_id: int | None = None
    paper_id: int | None = None     # 手动录入证据（无 doc_id）所属论文 ID，用于分组持久化
    doc_title: str = ""
    file_name: str | None = None
    paper: dict | None = None   # KB_EVIDENCE 携带完整论文元数据
    dynamic: bool = False       # True = 动态证据（AI 提取 / 手动录入，可持久化、可按来源删除）
    fallback: bool = False      # True = 文档级保底召回切片（无直接证据文档的代表，不进回答证据）
    kv: bool = False            # True = 表格键值检索合成的证据（民族×疾病×数值），永远置顶保留
    source_text: str = ""       # 用于在文档全文中定位的原文。KV 证据的 text 是合成的
                                # （带「（来源：）」「命中数值：」等包装），原文里搜不到，
                                # 必须改用它所来源的那段原始切片来算偏移
    # ── 来源维度（一级分类）────────────────────────────────────────────
    # 随证据一起送进大模型，用于按可信度调整措辞：
    # official 直接引述；web_crawl 要注明「据公开报道」；user_upload 要注明「据用户上传资料」。
    source_level: str = "official"   # official / web_crawl / user_upload
    source_org: str | None = None    # 来源机构：国家卫健委 / 人民日报 / 用户上传 …
    source_url: str | None = None    # 原始链接（网页抓取时记录，供溯源）
    # ── 知识库分区 ────────────────────────────────────────────────
    # integrated 整合资料库（多篇文献提炼的汇编，《数据资料》那种）—— 检索时优先；
    # raw        原始文献库（论文原文 PDF、官方完整报告）—— 整合资料命中不了才降级用它。
    # 由 Java 侧随同步下发（见 KbService.partitionOf）；索引文件里没有这一项的老数据，
    # 用 _partition_of(file_name) 现推。详见该函数上的说明。
    partition: str = PARTITION_INTEGRATED
    embedding: list[float] | None = None
    tokens: list[str] = field(default_factory=list, repr=False)


# ── 知识库分区 ────────────────────────────────────────────────────
def _partition_of(file_name: str | None, fallback: str = PARTITION_INTEGRATED) -> str:
    """按文件名后缀推分区：pdf 是论文原文，其余（docx/md）在本项目里都是人工整理的汇编。

    <p>为什么这里也要有一份判断：索引是**落盘持久化**的，加字段之前入库的那批文档在
    JSON 里根本没有 partition 这一项，而它们不会因为改代码就自动重新同步一次。
    没有这条兜底，存量 PDF 会被当成整合资料优先检索——正好是这次要修的问题。</p>

    ⚠️ 与 Java 侧 `KbService.partitionOf` 是同一条规则的两份实现，改一处要改两处。
    """
    if not file_name:
        # 没有文件名的（内置文献 KB_EVIDENCE）当原文：它们本来就是论文
        return PARTITION_RAW
    return PARTITION_RAW if file_name.lower().endswith(".pdf") else fallback


def citation_str(paper: dict) -> str:
    """把论文元数据组装为规范引用串（与 Java paper.source 对应）"""
    parts = [paper.get("journal", ""), paper.get("year", "")]
    if paper.get("volume"):
        parts.append(paper["volume"])
    s = ", ".join(x for x in parts if x)
    authors = (paper.get("authors") or "").strip()
    if authors and not (authors.startswith("（") and authors.endswith("）")):
        s += f"（{authors}）"
    if paper.get("doi"):
        s += f" DOI:{paper['doi']}"
    return s[:300]


class KnowledgeBase:
    def __init__(self) -> None:
        self.entries: list[Entry] = []
        self.papers_meta: list[dict] = []
        self._next_id = 1
        # doc_id -> {"title": str, "file_name": str, "full_text": str}
        self.documents: dict[int, dict] = {}

    # ---------- 装载 ----------
    def load_papers(self) -> int:
        """装载真实文献数据集（data/papers.json），返回论文数。

        跳过条件：enabled:false 的论文；标题为英文主导的论文（证据片段不使用英文文献）。
        """
        data = json.loads(config.KB_PATH.read_text(encoding="utf-8"))
        for p in data.get("papers", []):
            if p.get("enabled") is False:
                continue
            if is_english_text(p.get("title", "")):
                logger.warning("跳过英文文献（证据片段不使用英文文献）：%s", p.get("title", ""))
                continue
            self.papers_meta.append(p)
            for ev in p.get("evidences", []):
                if is_english_text(ev.get("content", "")):
                    logger.warning("跳过论文 %s 的英文证据片段", p.get("title", ""))
                    continue
                self.entries.append(Entry(
                    entry_id=self._next_id,
                    text=ev.get("content", ""),
                    source_type="KB_EVIDENCE",
                    # 证据级民族/疾病标注优先，为空则继承论文标注
                    # （如三民族对比研究拆分为多条证据，各自标注所属民族）
                    ethnicity=ev.get("ethnicity") or p.get("ethnicity"),
                    disease=ev.get("disease") or p.get("disease"),
                    topic=ev.get("topic"),
                    section=ev.get("topic"),
                    doc_id=self._next_id,
                    doc_title=p.get("title", ""),
                    paper=p,
                    # 内置文献（papers.json）本身就是论文原文 → 原始文献库
                    partition=PARTITION_RAW,
                ))
                self._next_id += 1
        for e in self.entries:
            e.tokens = tokenize(e.text)
        return len(self.papers_meta)

    # ---------- 动态证据（AI 提取 / 手动录入，Java 推送；持久化到 dynamic_evidences.json） ----------

    @staticmethod
    def _group_key(paper: dict):
        """动态证据分组键：文档证据按 doc_id，手动录入（无 doc_id）按论文 id"""
        if paper.get("doc_id") is not None:
            return ("doc", int(paper["doc_id"]))
        return ("paper", int(paper.get("id") or paper.get("paper_id") or 0))

    def _remove_dynamic_group(self, key) -> int:
        """删除某来源组的全部动态证据（按分组键），并持久化"""
        before = len(self.entries)
        self.entries = [e for e in self.entries
                        if not (e.dynamic and self._entry_group_key(e) == key)]
        removed = before - len(self.entries)
        if removed:
            self._persist_dynamic()
        return removed

    @staticmethod
    def _entry_group_key(e: Entry):
        if e.doc_id is not None:
            return ("doc", e.doc_id)
        return ("paper", e.paper_id or 0)

    def load_dynamic_evidences(self) -> int:
        """启动时加载动态证据（AI 提取 / 手动录入的 KB_EVIDENCE），返回证据条数"""
        try:
            data = json.loads(DYNAMIC_EVIDENCE_PATH.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return 0
        if not isinstance(data, list):
            return 0
        count = 0
        for item in data:
            paper = item.get("paper") or {}
            for ev in item.get("evidences", []):
                text = (ev.get("content") or "").strip()
                if not text:
                    continue
                if is_english_text(text):
                    logger.warning("跳过动态证据中的英文片段（来源：%s）", paper.get("title", ""))
                    continue
                self.entries.append(Entry(
                    entry_id=self._next_id,
                    text=text,
                    source_type="KB_EVIDENCE",
                    ethnicity=ev.get("ethnicity") or paper.get("ethnicity"),
                    disease=ev.get("disease") or paper.get("disease"),
                    topic=ev.get("topic"),
                    section=ev.get("section") or ev.get("topic"),
                    page_no=ev.get("pageNo"),
                    doc_id=paper.get("doc_id"),
                    paper_id=paper.get("id") or paper.get("paper_id"),
                    doc_title=paper.get("title", ""),
                    file_name=paper.get("fileName"),
                    paper=paper,
                    dynamic=True,
                    # 分区跟着它所属的那份文档走：库里没有这一项时按文件名后缀现推
                    partition=_partition_of(paper.get("fileName")),
                ))
                self.entries[-1].tokens = tokenize(text)
                self._next_id += 1
                count += 1
        return count

    def add_dynamic_evidence(self, paper: dict, evidences: list[dict]) -> int:
        """添加动态证据（幂等：先删该来源组旧证据再插入），并持久化。

        paper 需含 doc_id（文档ID）或 id（论文ID）作为分组键；
        evidences 为 [{topic, content, pageNo, section, ethnicity, disease}]。
        """
        self._remove_dynamic_group(self._group_key(paper))
        doc_id = paper.get("doc_id")
        paper_id = paper.get("id") or paper.get("paper_id")
        added = 0
        skipped_english = 0
        for ev in evidences:
            text = (ev.get("content") or "").strip()
            if not text:
                continue
            if is_english_text(text):
                skipped_english += 1
                continue
            self.entries.append(Entry(
                entry_id=self._next_id,
                text=text,
                source_type="KB_EVIDENCE",
                ethnicity=ev.get("ethnicity") or paper.get("ethnicity"),
                disease=ev.get("disease") or paper.get("disease"),
                topic=ev.get("topic"),
                section=ev.get("section") or ev.get("topic"),
                page_no=ev.get("pageNo"),
                doc_id=doc_id,
                paper_id=paper_id,
                doc_title=paper.get("title", ""),
                file_name=paper.get("fileName"),
                paper=paper,
                dynamic=True,
                # 同上：分区跟着所属文档走
                partition=_partition_of(paper.get("fileName")),
            ))
            self.entries[-1].tokens = tokenize(text)
            self._next_id += 1
            added += 1
        if added:
            self._persist_dynamic()
        if skipped_english:
            logger.warning("add_dynamic_evidence 跳过 %d 条英文证据片段（来源：%s）",
                            skipped_english, paper.get("title", ""))
        return added

    def remove_document_evidences(self, document_id: int) -> int:
        """删除某文档的全部动态证据（文档删除/重新摄取时调用），并持久化。

        只删除 dynamic=True 且 doc_id 匹配的证据——避免与论文证据冲突时误删原始文献。
        """
        if document_id is None:
            return 0
        before = len(self.entries)
        self.entries = [e for e in self.entries
                        if not (e.dynamic and e.doc_id == document_id)]
        removed = before - len(self.entries)
        if removed:
            self._persist_dynamic()
        return removed

    def remove_paper_evidences(self, paper_id: int) -> int:
        """删除某论文（手动录入来源，无 doc_id）的全部动态证据，并持久化"""
        if paper_id is None:
            return 0
        return self._remove_dynamic_group(("paper", int(paper_id)))

    def _persist_dynamic(self) -> None:
        """按当前内存中的「动态证据」全量重写持久化文件（数据量小，全量覆盖简单可靠）。

        只持久化 dynamic=True 的条目——原始论文证据（papers.json）不属于动态证据，
        避免把整个 KB 写进文件导致重启后证据翻倍。
        """
        items: dict[tuple, dict] = {}
        for e in self.entries:
            if not e.dynamic:
                continue
            key = self._entry_group_key(e)
            item = items.setdefault(key, {"paper": e.paper or {}, "evidences": []})
            item["evidences"].append({
                "topic": e.topic,
                "content": e.text,
                "pageNo": e.page_no,
                "section": e.section,
                "ethnicity": e.ethnicity,
                "disease": e.disease,
            })
        DYNAMIC_EVIDENCE_PATH.parent.mkdir(parents=True, exist_ok=True)
        DYNAMIC_EVIDENCE_PATH.write_text(
            json.dumps(list(items.values()), ensure_ascii=False, indent=1), encoding="utf-8")

    def set_document_full_text(self, document_id: int, title: str,
                                file_name: str | None, full_text: str,
                                source_level: str = "official",
                                source_org: str | None = None,
                                source_url: str | None = None,
                                partition: str = PARTITION_INTEGRATED) -> None:
        """存储文档全文（供「左全文·右证据」浏览模式），并持久化"""
        self.documents[document_id] = {
            "title": title or "",
            "file_name": file_name or "",
            "full_text": full_text or "",
            "source_level": source_level or "official",
            "source_org": source_org,
            "source_url": source_url,
            "partition": partition or _partition_of(file_name),
        }
        self._persist_documents()

    def get_document_full_text(self, document_id: int) -> str:
        """获取文档全文；未存储时返回空串"""
        d = self.documents.get(document_id)
        return d["full_text"] if d else ""

    def _persist_documents(self) -> None:
        """持久化文档全文映射（含来源维度——不写盘的话重启后来源标签会丢）"""
        KB_DOCUMENTS_PATH.parent.mkdir(parents=True, exist_ok=True)
        KB_DOCUMENTS_PATH.write_text(
            json.dumps([
                {"doc_id": k, "title": v["title"], "file_name": v["file_name"],
                 "full_text": v["full_text"],
                 "source_level": v.get("source_level", "official"),
                 "source_org": v.get("source_org"),
                 "source_url": v.get("source_url"),
                 "partition": v.get("partition", PARTITION_INTEGRATED)}
                for k, v in self.documents.items()
            ], ensure_ascii=False, indent=1), encoding="utf-8")

    def load_documents(self) -> int:
        """启动时加载文档全文，返回加载文档数"""
        try:
            data = json.loads(KB_DOCUMENTS_PATH.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return 0
        if not isinstance(data, list):
            return 0
        for item in data:
            doc_id = item.get("doc_id")
            if doc_id is None:
                continue
            self.documents[doc_id] = {
                "title": item.get("title", ""),
                "file_name": item.get("file_name", ""),
                "full_text": item.get("full_text", ""),
                # 加字段之前落盘的数据没有这几项 → 回退 official，
                # 与 Java 侧 kb_document.source_level 列的 DEFAULT 保持一致
                "source_level": item.get("source_level") or "official",
                "source_org": item.get("source_org"),
                "source_url": item.get("source_url"),
                # 同 load_document_chunks：老数据没有这一项就按文件名现推
                "partition": item.get("partition") or _partition_of(item.get("file_name")),
            }
        return len(self.documents)

    def add_document(self, document_id: int, title: str, file_name: str | None,
                     chunks: list[dict], full_text: str | None = None,
                     source_level: str = "official", source_org: str | None = None,
                     source_url: str | None = None,
                     partition: str = PARTITION_INTEGRATED) -> int:
        """Java 摄取完成后同步文档切片（幂等：先删旧再插新），并落盘持久化"""
        self.remove_document(document_id)
        added = 0
        skipped_english = 0
        for c in chunks:
            text = (c.get("content") or "").strip()
            if not text:
                continue
            if is_english_text(text):
                skipped_english += 1
                continue
            self.entries.append(Entry(
                entry_id=self._next_id,
                text=text,
                source_type="RAG_CHUNK",
                section=c.get("section"),
                page_no=c.get("pageNo"),
                doc_id=document_id,
                doc_title=title or "",
                file_name=file_name,
                source_level=source_level or "official",
                source_org=source_org,
                source_url=source_url,
                # 由 Java 侧下发（它按 kb_document.partition 读），这是权威值；
                # 只有老数据没有这一项时才在加载路径上按文件名现推
                partition=partition or _partition_of(file_name),
            ))
            self.entries[-1].tokens = tokenize(text)
            self._next_id += 1
            added += 1
        if added:
            self._persist_chunks()
        if skipped_english:
            logger.warning("add_document 跳过 %d 条英文切片（文档：%s）",
                            skipped_english, title)
        return added

    def remove_document(self, document_id: int) -> int:
        before = len(self.entries)
        self.entries = [e for e in self.entries
                        if not (e.source_type == "RAG_CHUNK" and e.doc_id == document_id)]
        removed = before - len(self.entries)
        if removed:
            self._persist_chunks()
        if document_id in self.documents:
            del self.documents[document_id]
            self._persist_documents()
        return removed

    def _persist_chunks(self) -> None:
        """按文档分组把当前内存中的 RAG_CHUNK 切片写入文件（供重启后加载）"""
        groups: dict[int, dict] = {}
        for e in self.entries:
            if e.source_type != "RAG_CHUNK" or e.doc_id is None:
                continue
            g = groups.setdefault(e.doc_id, {"doc_id": e.doc_id, "title": e.doc_title,
                                            "file_name": e.file_name,
                                            "source_level": e.source_level,
                                            "source_org": e.source_org,
                                            "source_url": e.source_url,
                                            "partition": e.partition,
                                            "chunks": []})
            g["chunks"].append({"section": e.section, "pageNo": e.page_no, "content": e.text})
        DOCUMENT_CHUNKS_PATH.parent.mkdir(parents=True, exist_ok=True)
        DOCUMENT_CHUNKS_PATH.write_text(
            json.dumps(list(groups.values()), ensure_ascii=False, indent=1), encoding="utf-8")

    def load_document_chunks(self) -> int:
        """启动时加载文档切片（RAG_CHUNK）与全文，返回切片条数"""
        self.load_documents()
        try:
            data = json.loads(DOCUMENT_CHUNKS_PATH.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return 0
        if not isinstance(data, list):
            return 0
        added = 0
        for g in data:
            doc_id = g.get("doc_id")
            if doc_id is None:
                continue
            for c in g.get("chunks", []):
                text = (c.get("content") or "").strip()
                if not text or is_english_text(text):
                    continue
                self.entries.append(Entry(
                    entry_id=self._next_id,
                    text=text,
                    source_type="RAG_CHUNK",
                    section=c.get("section"),
                    page_no=c.get("pageNo"),
                    doc_id=doc_id,
                    doc_title=g.get("title", ""),
                    file_name=g.get("file_name"),
                    # 来源维度存在文档组这一层（同一文档的所有切片同源）；
                    # 加字段之前落盘的组没有该项 → 回退 official
                    source_level=g.get("source_level") or "official",
                    source_org=g.get("source_org"),
                    source_url=g.get("source_url"),
                    # 加字段之前落盘的组没有 partition → 按文件名后缀现推（pdf 算原文）。
                    # 少了这条兜底，存量 PDF 会被当成整合资料优先检索，正好是这次要修的问题。
                    partition=g.get("partition") or _partition_of(g.get("file_name")),
                ))
                self.entries[-1].tokens = tokenize(text)
                self._next_id += 1
                added += 1
        return added

    # ---------- 索引 ----------
    async def build_index(self) -> None:
        if not self.entries:
            return
        vectors = await embed_texts([e.text for e in self.entries])
        for e, v in zip(self.entries, vectors):
            e.embedding = v

    async def rebuild_document_index(self) -> None:
        """增量重建（摄取同步后调用）：只对缺失向量的条目补算"""
        missing = [e for e in self.entries if e.embedding is None]
        if not missing:
            return
        vectors = await embed_texts([e.text for e in missing])
        for e, v in zip(missing, vectors):
            e.embedding = v

    # ---------- 检索 ----------
    def _bm25_scores(self, query_tokens: list[str],
                     candidates: list[Entry] | None = None) -> list[float]:
        entries = candidates if candidates is not None else self.entries
        n = len(entries)
        if n == 0:
            return []
        k1, b = 1.5, 0.75
        avg_len = sum(len(e.tokens) for e in entries) / n or 1.0
        df: dict[str, int] = {}
        for e in entries:
            for t in set(e.tokens):
                df[t] = df.get(t, 0) + 1
        scores = []
        for e in entries:
            tf: dict[str, int] = {}
            for t in e.tokens:
                tf[t] = tf.get(t, 0) + 1
            score = 0.0
            for qt in query_tokens:
                if qt not in tf:
                    continue
                idf = math.log(1 + (n - df.get(qt, 0) + 0.5) / (df.get(qt, 0) + 0.5))
                score += idf * tf[qt] * (k1 + 1) / (tf[qt] + k1 * (1 - b + b * len(e.tokens) / avg_len))
            scores.append(score)
        return scores

    async def retrieve(self, query: str, ethnicity: str | None = None,
                       disease: str | None = None, top_k: int | None = None,
                       intent: str | None = None,
                       ensure_doc_coverage: bool = False,
                       extra_queries: list[str] | None = None,
                       partition: str | None = None) -> list[Entry]:
        """混合检索：BM25 + 向量余弦 加权融合，民族/疾病/意图命中加权

        extra_queries 为查询扩展产生的等价问句：逐条打分后取每条候选的最高分，
        不同措辞召回的证据可以互补。

        ensure_doc_coverage=True 时执行文档级保底：每个知识库文档（RAG_CHUNK）
        至少有 1 条切片进入结果——没被正常检索覆盖的文档，取该文档内相关分数
        最高的切片追加（标记 fallback=True），保证每个问题都扫描每个知识库文件。

        partition 限定只在这一个库内检索（integrated / raw）。调用方**分两趟调用**实现
        「先整合资料、命中不了才降级翻原文」——滤在入口处而不是事后筛，是为了让
        「分数最高的一批」本身就来自对的库，否则降级时会拿着整合资料的分数去比原文。
        """
        top_k = top_k or config.TOP_K
        if not self.entries:
            return []
        # 兜底语言过滤 + 参考文献列表过滤（参考文献著录不是文档实质内容）
        all_pool = [e for e in self.entries
                    if not is_english_text(e.text) and not is_reference_list(e.text)]
        # 仅基于上传文档（RAG_CHUNK）回答：排除内置文献 KB_EVIDENCE（结构化证据层）。
        # 开启后所有问答只依据上传的两份文档，证据材料/引用也只指向这两份文档。
        if config.EXCLUDE_BUILTIN_LITERATURE:
            all_pool = [e for e in all_pool if e.source_type != "KB_EVIDENCE"]
        # 知识库分区过滤（见方法说明）
        if partition:
            all_pool = [e for e in all_pool if e.partition == partition]
        candidates = all_pool
        # 疾病硬过滤：当问题明确指定疾病时，排除带有其他疾病标签的证据（避免把糖尿病证据答成高脂血症）
        # KB_EVIDENCE 必须疾病匹配；RAG_CHUNK（disease=None）需切片文本本身含疾病关键词，
        # 否则会把整篇文档的所有切片（其它疾病的患病率）都当成证据搜回来。
        if disease:
            # 用「完整疾病词 + 二元组（≥2 字）」做包含判断，而非 tokenize 后的单字。
            # tokenize("糖尿病") 会拆成 ["糖","尿","病","糖尿","尿病"]，单字「病」几乎命中
            # 所有医学文本，导致疾病过滤完全失效——共识 PDF 里不含糖尿病的乙肝/药动学切片
            # 也会因单字「病」被误判为相关而混入回答。
            disease_terms = [disease] + [disease[i:i + 2] for i in range(len(disease) - 1)]
            disease_terms = [t for t in disease_terms if len(t) >= 2]
            keep = []
            for e in candidates:
                if e.disease is not None:
                    if e.disease == disease:
                        keep.append(e)
                else:
                    # 文档切片：文本命中完整疾病词或疾病二元组才算相关
                    # （如「糖尿病患病率」「糖尿病」切片），避免单字误伤
                    if any(dt in e.text for dt in disease_terms):
                        keep.append(e)
            candidates = keep
        if not candidates:
            return []

        q_tokens = tokenize(query)
        bm25 = self._bm25_scores(q_tokens, candidates)
        bm_max = max(bm25) or 1.0
        bm_norm = [s / bm_max for s in bm25]

        # 多问句（含查询扩展变体）：逐条算分后取「每条候选的最高分」——
        # 任一问法命中即算命中。只用一个问法时行为与原来完全一致。
        queries = [query] + [x for x in (extra_queries or []) if x and x.strip()]

        bm25 = None
        for qx in queries:
            s = self._bm25_scores(tokenize(qx), candidates)
            bm25 = s if bm25 is None else [max(a, b) for a, b in zip(bm25, s)]
        bm_max = max(bm25) or 1.0
        bm_norm = [s / bm_max for s in bm25]

        if config.embedding_enabled():
            q_vecs = await embed_texts(queries)
        else:
            q_vecs = [hashing_embed(qx) for qx in queries]
        # 原始问题的向量仍单独保留：文档级保底扫描与维度对齐都用它
        q_vec = q_vecs[0]
        # 与条目向量维度对齐（DashScope 1024 维 / 本地哈希 256 维）
        cos = []
        for e in candidates:
            best = 0.0
            for qv in q_vecs:
                if e.embedding is None or len(e.embedding) != len(qv):
                    continue
                dot = sum(a * b for a, b in zip(qv, e.embedding))
                if dot > best:
                    best = dot
            cos.append(best)

        cos_max = max(cos) or 1.0
        cos_norm = [c / cos_max for c in cos]

        scored = []
        for i, e in enumerate(candidates):
            score = 0.55 * bm_norm[i] + 0.45 * cos_norm[i]
            if ethnicity and e.ethnicity == ethnicity:
                score += 0.15
            if disease and e.disease == disease:
                score += 0.15
            if intent and e.topic == intent:
                score += 0.30
            scored.append((score, bm25[i], cos_norm[i], e))

        # 有效性过滤：BM25 无词面命中且向量相似度过低的条目剔除
        scored = [s for s in scored if s[1] > 0 or s[2] >= 0.35]
        scored.sort(key=lambda s: s[0], reverse=True)

        # 意图硬过滤：明确意图（非 overview）时，优先只召回同主题证据；
        # 同主题不足 top_k 时再按分数补充其他主题，避免回答掺入无关主题（与 Java 检索一致）
        if intent and intent != "overview":
            same_topic = [s for s in scored if s[3].topic == intent]
            if len(same_topic) >= top_k:
                result = [s[3] for s in same_topic[:top_k]]
            elif same_topic:
                filled = list(same_topic)
                for s in scored:
                    if s[3].topic != intent and len(filled) < top_k:
                        filled.append(s)
                result = [s[3] for s in filled]
            else:
                result = [s[3] for s in scored[:top_k]]
        else:
            result = [s[3] for s in scored[:top_k]]

        # 文档级保底扫描：每个知识库文档至少进 1 条切片（每个问题都扫描每个文件）。
        # 仅在 ensure_doc_coverage=True（证据池展示）时启用；回答生成走纯检索保持相关性。
        if ensure_doc_coverage:
            covered = {e.doc_id for e in result if e.source_type == "RAG_CHUNK"}
            kb_docs = {e.doc_id for e in all_pool if e.source_type == "RAG_CHUNK"}
            for doc_id in kb_docs - covered:
                doc_entries = [e for e in all_pool
                               if e.source_type == "RAG_CHUNK" and e.doc_id == doc_id]
                if not doc_entries:
                    continue
                # 文档内单独打分（BM25 + 向量余弦），取混合分最高的切片作为该文档的代表
                dbm = self._bm25_scores(q_tokens, doc_entries)
                dmax = max(dbm) or 1.0
                best, best_score = None, -1.0
                for e, b in zip(doc_entries, dbm):
                    c = 0.0
                    if e.embedding is not None and len(e.embedding) == len(q_vec):
                        c = sum(a * b for a, b in zip(q_vec, e.embedding))
                    score = 0.55 * (b / dmax if dmax else 0.0) + 0.45 * c
                    if score > best_score:
                        best_score, best = score, e
                if best is not None:
                    best.fallback = True
                    result.append(best)

        # 表格键值证据置顶：数值表比「白族最高，哈尼族最低」这类综述句更该被 LLM 看到，
        # 靠相似度排序拿不稳（两者常在同一份资料的不同切片里），这里确定性强制召回。
        kv_entries = self._numeric_kv_entries(candidates, ethnicity, disease, intent)
        if kv_entries:
            result = kv_entries + result
        return result

    def _numeric_kv_entries(self, candidates: list[Entry], ethnicity: str | None,
                            disease: str | None, intent: str | None = None,
                            limit: int = 2) -> list[Entry]:
        """表格键值检索：从候选全池捞「民族 × 疾病 × 数值」的表格行，合成聚焦证据。

        向量 / BM25 判断的是整段主题相关性，看不住表格单元格。同一份资料里的综述句
        与数值表常被切进不同切片，按来源去重后数值表会被整条丢掉——用户问
        「白族糖尿病患病率是多少」，只拿到一句没有数字的结论。这里对候选全池做一次
        确定性扫描，命中即作为最高优先级证据返回，不受相似度排序影响。

        **只对患病率类问题生效**：数值表承载的是「多少人有病」，对饮食 / 遗传 /
        危险因素类问题不构成证据。若不分意图一律置顶，问饮食时会把患病率数据塞给
        LLM，导致答非所问、只能靠常识编造——这正是「无论问什么都返回同一段」的成因。
        """
        if not (ethnicity and disease):
            return []
        if intent and intent not in ("prevalence", "overview"):
            return []
        out: list[Entry] = []
        seen: set[str] = set()
        for e in candidates:
            hit = extract_kv_row(e.text or "", ethnicity, disease)
            if not hit:
                continue
            snippet = kv_snippet(hit, e.doc_title or "")
            if snippet in seen:
                continue
            seen.add(snippet)
            out.append(Entry(
                entry_id=-(len(out) + 1),   # 负号表示合成证据，不与真实条目编号冲突
                text=snippet,
                source_type=e.source_type,
                ethnicity=ethnicity,
                disease=disease,
                topic="prevalence",
                page_no=e.page_no,
                doc_id=e.doc_id,
                doc_title=e.doc_title,
                file_name=e.file_name,
                kv=True,
                source_text=e.text or "",
                # 合成证据跟着它的来源切片走：来源在整合资料库里，它就该在整合资料库这一档
                partition=e.partition,
            ))
            if len(out) >= limit:
                break
        return out

    def available_diseases(self, ethnicity: str | None,
                           diseases_vocab: list[str] | None = None,
                           exclude: str | None = None) -> list[str]:
        """检索为空时，找出「该民族在知识库中已有数据」的疾病，供引导用户询问其他疾病。

        仅依据上传文档切片（RAG_CHUNK，即真正参与回答的证据），用完整疾病词匹配
        （避免二元组把「脂肪→脂肪肝」「膜病→视网膜病变」这类无关词误判为有数据）。
        某切片文本同时含该民族名与完整疾病词，才算该民族有该疾病数据。
        diseases_vocab 为空时返回空列表（无可推荐疾病词表）。
        """
        if not ethnicity or not diseases_vocab:
            return []
        found: list[str] = []
        for d in diseases_vocab:
            d = (d or "").strip()
            if not d or (exclude and d == exclude):
                continue
            for e in self.entries:
                if e.source_type != "RAG_CHUNK":
                    continue
                txt = e.text or ""
                if ethnicity in txt and d in txt:
                    found.append(d)
                    break
        return found

    def available_ethnicities(self, disease: str | None,
                              ethnicities_vocab: list[str] | None = None,
                              exclude: str | None = None) -> list[str]:
        """与 {@link available_diseases} 对称：找出「知识库中已有该疾病数据」的民族。

        供未命中时引导用户询问**其他民族**的同一疾病（如问「白族糖尿病」没数据，
        但库里有维吾尔族糖尿病，就推荐后者）。判据同 available_diseases：
        某切片文本同时含该完整疾病词与该民族名，才算该民族有该疾病数据。
        """
        if not disease or not ethnicities_vocab:
            return []
        found: list[str] = []
        for e in ethnicities_vocab:
            e = (e or "").strip()
            if not e or (exclude and e == exclude):
                continue
            for entry in self.entries:
                if entry.source_type != "RAG_CHUNK":
                    continue
                txt = entry.text or ""
                if e in txt and disease in txt:
                    found.append(e)
                    break
        return found

    # ---------- 状态 ----------
    def status(self) -> dict:
        kb_ev = [e for e in self.entries if e.source_type == "KB_EVIDENCE"]
        doc_chunks = [e for e in self.entries if e.source_type == "RAG_CHUNK"]
        return {
            "papers": len(self.papers_meta),
            "kbEvidences": len(kb_ev),
            "docChunks": len(doc_chunks),
            "documents": len({e.doc_id for e in doc_chunks}),
            "coveredEthnicities": sorted({e.ethnicity for e in self.entries if e.ethnicity}),
            "coveredDiseases": sorted({e.disease for e in self.entries if e.disease}),
        }


kb = KnowledgeBase()
