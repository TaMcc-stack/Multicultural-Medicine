"""AI 全文摘要：对整份资料做内容总结（LLM best-effort）。

数据资料本身通常没有「摘要」，此模块用大模型通读全文，生成一段真实、客观的
内容摘要，供 Java 知识库的 summary 字段使用；LLM 不可用或失败时返回空串，
由 Java 兜底到启发式摘要。
"""
from . import llm

_SYSTEM_PROMPT = (
    "你是医学科研资料整理助手。请通读下面这份研究资料，用中文输出一段「内容摘要」：\n"
    "1. 概括整份资料的核心内容：研究主题、涉及民族/人群、主要疾病的流行病学数据、关键危险因素、研究结论。\n"
    "2. 摘要必须真实来源于资料本身，不得编造原文没有的数据、数字或结论，不要做任何数值推算。\n"
    "3. 用 3~6 句话，条理清晰，语气客观。直接输出摘要正文，不要加任何标题，不要写「以下是摘要」之类的引导语。"
)


def _strip_codes(s: str) -> str:
    return (s or "").strip().strip("`").strip()


async def summarize_document(title: str, text: str) -> str:
    """对整份资料生成内容摘要；LLM 不可用或失败时返回空串。"""
    if not llm.available():
        return ""
    body = (text or "").strip()
    if not body:
        return ""
    MAX = 12000
    if len(body) > MAX:
        body = body[:MAX] + "\n……（更长内容已略去部分细节）"
    user = f"资料标题：{title or '未命名资料'}\n\n资料正文：\n{body}\n\n请输出内容摘要。"
    try:
        content = await llm.chat_struct(
            [{"role": "system", "content": _SYSTEM_PROMPT},
             {"role": "user", "content": user}],
            temperature=0.3, max_tokens=800)
        return _strip_codes(content).strip()
    except Exception:
        return ""
