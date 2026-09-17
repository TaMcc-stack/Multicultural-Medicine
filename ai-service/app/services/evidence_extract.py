"""AI 证据提取：从上传文档内容中提取「可溯源的证据片段」。

文档摄取（分片入库）后，Java 后端调用本服务把切片分批喂给 LLM，
提取出结构化的证据条目（意图主题 + 完整可溯源的结论句 + 页码/章节），
再回传 Java 保存到 evidence 表并作为 KB_EVIDENCE 进入检索。

设计约束（与用户对回答格式的要求一致）：
- 证据必须是语义完整的句子/段落，以句号或分号结尾，禁止截断、禁止省略号；
- topic 只允许合法意图值（prevalence/risk/diet/genetics/overview），供检索意图过滤使用；
- LLM 不可用或提取失败时返回空列表（best-effort，不影响文档摄取主链路）。
"""
import json
import re

from . import llm

VALID_TOPICS = {"prevalence", "risk", "diet", "genetics", "overview"}

_SYSTEM_PROMPT = (
    "你是医学研究文献整理助手，任务是从研究资料文本中提取「可溯源的证据片段」。请严格遵守：\n"
    "1. 证据片段 = 一句话或一小段完整的科研结论/数据发现，必须包含：研究对象（人群/民族/年龄范围）、"
    "样本量（如有）、关键数值（患病率/OR值/比例/风险倍数等）、结论性描述。\n"
    "2. 每条证据的 topic 必须且只能属于以下之一：\n"
    "   prevalence=患病情况；risk=危险因素；diet=饮食与生活方式；genetics=遗传相关研究；overview=研究总体情况。\n"
    "   只提取与上述主题直接相关的内容；研究方法学描述、统计口径说明、致谢、参考文献等不提取。\n"
    "3. 证据内容尽量保留原文表述，不得改写、不得补充原文没有的信息、不得做任何数值推算。\n"
    "4. 每条证据必须以句号「。」或分号「；」结尾，语义完整，禁止截断、禁止使用省略号（…）。\n"
    "5. 对每条证据，尽量从原文识别并标注两个可选字段（识别不到填 null）：\n"
    "   ethnicity=该证据涉及的主要民族（如 傣族/白族/哈尼族/汉族/多民族 等；多民族综合研究填 多民族）；\n"
    "   disease=该证据涉及的主要疾病或研究主题（如 糖尿病/高血压/CKM/遗传/药物基因组 等；综合填 多疾病）。\n"
    "6. 只输出一个 JSON 对象，不要输出其他文字，格式：\n"
    '{"evidences":[{"topic":"prevalence","content":"证据内容","pageNo":页码或null,"section":"章节名或null","ethnicity":"民族或null","disease":"疾病或null"},...]}\n'
    "   没有可提取内容时输出 {\"evidences\":[]}。"
)


def _batch_texts(chunks: list[dict], limit: int = 2500) -> list[str]:
    """把切片按约 limit 字数分批拼接（避免单次 LLM 输入超长）"""
    batches: list[str] = []
    current: list[str] = []
    current_len = 0
    for c in chunks:
        text = (c.get("content") or "").strip()
        if not text:
            continue
        if current and current_len + len(text) > limit:
            batches.append("\n".join(current))
            current, current_len = [], 0
        current.append(text)
        current_len += len(text)
    if current:
        batches.append("\n".join(current))
    return batches


def _parse_evidences(content: str) -> list[dict]:
    """解析 LLM 返回的 JSON，清洗非法条目（topic 非法 / 内容空 直接丢弃）"""
    text = (content or "").strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    try:
        obj = json.loads(text)
    except Exception:
        match = re.search(r"\{[\s\S]*\}", text)
        if not match:
            return []
        try:
            obj = json.loads(match.group(0))
        except Exception:
            return []
    evs = obj.get("evidences") if isinstance(obj, dict) else None
    if not isinstance(evs, list):
        return []
    out: list[dict] = []
    for e in evs:
        if not isinstance(e, dict):
            continue
        content_s = (e.get("content") or "").strip()
        topic = (e.get("topic") or "").strip().lower()
        if not content_s or topic not in VALID_TOPICS:
            continue
        page = e.get("pageNo")
        eth = e.get("ethnicity")
        dis = e.get("disease")
        out.append({
            "topic": topic,
            "content": content_s[:1000],
            "pageNo": page if isinstance(page, int) and page > 0 else None,
            "section": (e.get("section") or "").strip() or None,
            "ethnicity": (eth.strip() if isinstance(eth, str) and eth.strip() else None),
            "disease": (dis.strip() if isinstance(dis, str) and dis.strip() else None),
        })
    return out


async def extract_evidences(title: str, ethnicity: str | None,
                            disease: str | None, chunks: list[dict]) -> list[dict]:
    """从文档切片提取证据片段（分批调用 LLM，best-effort）。

    返回 [{topic, content, pageNo, section}]，LLM 不可用或全部失败时返回空列表。
    """
    if not llm.available():
        return []
    batches = _batch_texts(chunks)
    if not batches:
        return []
    results: list[dict] = []
    for i, batch in enumerate(batches, 1):
        user = (
            f"资料标题：{title or '未命名资料'}\n"
            f"（已识别：民族={ethnicity or '未知'}，疾病={disease or '未知'}）\n\n"
            f"资料正文（第 {i}/{len(batches)} 批）：\n{batch}\n\n"
            "请提取本批文本中的可溯源证据片段，输出 JSON。"
        )
        try:
            content = await llm.chat_struct(
                [{"role": "system", "content": _SYSTEM_PROMPT},
                 {"role": "user", "content": user}],
                temperature=0.1, json_mode=True, max_tokens=2000)
            results.extend(_parse_evidences(content))
        except Exception:
            # 单批失败不中断：其余批次继续尝试
            continue
    return results
