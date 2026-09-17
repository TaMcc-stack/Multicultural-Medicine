# -*- coding: utf-8 -*-
"""查询扩展（Query Expansion）：把用户问题改写成若干等价问句，用于提高召回。

动机：用户的口语问法与文献的书面表述常有落差——问「得糖尿病的人多吗」，
文献写的是「糖尿病标化患病率」；问「怎么预防」，文献写的是「危险因素」。
单个问句检索只看一种措辞，容易漏。

做法：用 LLM 生成 N 个等价问句（不引入原问题没有的限定），逐条检索后
在打分阶段取「各问法得分的最大值」——任一问法命中即算命中。

失败一律静默降级为「不扩展」，绝不影响问答主链路。
"""
import logging
import re

from . import llm

logger = logging.getLogger("ai-service.expansion")

DEFAULT_VARIANTS = 3

_PROMPT = """你是中文医学文献检索助手。请把用户的问题改写成 {n} 个**等价的检索问句**，用于在文献库里检索。

要求：
1. 保持原意不变，**不得引入原问题没有的限定**（不要自行添加民族、疾病、年龄或年份）。
2. 改用文献里更可能出现的**书面表述**。例如：
   - 「得的人多吗」→「患病率」「患病情况」
   - 「怎么预防」→「危险因素」「影响因素」
   - 「是不是也得小心」→「患病风险」「危险因素」
3. 每行一个问句，只输出问句本身，不要编号、不要解释、不要引号。

{slots}用户问题：{question}"""


def _slots_text(ethnicity: str | None, disease: str | None, intent: str | None) -> str:
    parts = []
    if ethnicity:
        parts.append(f"民族={ethnicity}")
    if disease:
        parts.append(f"疾病={disease}")
    if intent:
        parts.append(f"意图={intent}")
    return ("已知槽位（可在问句中原样保留）：" + "，".join(parts) + "\n") if parts else ""


def _parse_variants(content: str, original: str, n: int) -> list[str]:
    """解析 LLM 输出为问句列表：去编号、去引号、去重、剔除与原文重复的。"""
    out: list[str] = []
    for raw in (content or "").splitlines():
        s = raw.strip()
        if not s:
            continue
        s = re.sub(r"^\s*[-*•]\s*", "", s)
        s = re.sub(r"^\s*\d+[.、)）]\s*", "", s)
        s = s.strip().strip("\"'“”‘’").strip()
        # 去掉「问句1：」这类前缀
        s = re.sub(r"^问句\s*\d*\s*[:：]\s*", "", s).strip()
        if len(s) < 4 or s == original or s in out:
            continue
        out.append(s)
        if len(out) >= n:
            break
    return out


async def expand_query(question: str, ethnicity: str | None = None,
                       disease: str | None = None, intent: str | None = None,
                       n: int = DEFAULT_VARIANTS) -> list[str]:
    """生成 n 个等价问句。LLM 不可用或失败时返回空列表（调用方按「不扩展」处理）。"""
    q = (question or "").strip()
    if not q or n <= 0 or not llm.available():
        return []
    prompt = _PROMPT.format(n=n, slots=_slots_text(ethnicity, disease, intent), question=q)
    try:
        content = await llm.chat_struct(
            [{"role": "user", "content": prompt}],
            temperature=0.2, max_tokens=240)
    except Exception as exc:
        logger.warning("查询扩展失败，按不扩展处理：%r", exc)
        return []
    variants = _parse_variants(content, q, n)
    if variants:
        logger.info("查询扩展：%s → %s", q, variants)
    return variants
