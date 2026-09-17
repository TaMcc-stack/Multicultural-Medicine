"""大模型生成调用（多供应商 OpenAI 兼容接口：硅基流动 / DeepSeek / DashScope）。

未配置 Key 或调用失败抛 LlmUnavailable，上层降级到规则理解 + 模板生成。
注意：DeepSeek deepseek-reasoner 不支持 response_format(json_object)，此处自动降级。
"""
import asyncio
import logging

import httpx

from .. import config

logger = logging.getLogger("ai-service.llm")

# 单次请求超时（秒）
_LLM_TIMEOUT = 120
# 最大尝试次数（首次 + 重试）
_LLM_MAX_RETRIES = 3
# 退避基数：第 n 次失败后等待 base**(n-1) 秒（1s、2s）
_LLM_RETRY_BACKOFF = 2

# 瞬时网络/连接故障：连接被中断、读超时等。这类错误重试通常可直接恢复，
# 不重试会导致回答频繁静默降级成模板兜底（用户看到的就是那段「综合检索到的 N 条研究证据…」）。
_RETRYABLE = (
    httpx.ReadError,
    httpx.WriteError,
    httpx.ConnectError,
    httpx.RemoteProtocolError,
    httpx.ReadTimeout,
    httpx.WriteTimeout,
    httpx.PoolTimeout,
)


class LlmUnavailable(Exception):
    pass


def available() -> bool:
    return config.llm_enabled()


async def chat(
    messages: list[dict],
    temperature: float = 0.3,
    json_mode: bool = False,
    max_tokens: int = 2000,
    model: str | None = None,
) -> str:
    """强模型对话（QA 问答用）。`model` 显式指定时覆盖默认强模型。

    对瞬时网络故障（连接中断 / 读超时等）与服务端 5xx 自动重试；
    4xx 属请求本身问题（Key 无效、参数错误），不重试直接抛出。
    """
    if not config.llm_enabled():
        raise LlmUnavailable(
            f"LLM 未配置（LLM_PROVIDER={config.LLM_PROVIDER or '空'}，"
            f"LLM_API_KEY={'已填' if config.LLM_API_KEY else '空'}）"
        )
    body: dict = {
        "model": model or config.LLM_MODEL,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    # DeepSeek deepseek-reasoner 不支持 response_format；json_mode 仅对支持者开启
    if json_mode and not (config.LLM_PROVIDER == "deepseek" and body["model"] == "deepseek-reasoner"):
        body["response_format"] = {"type": "json_object"}
    last_exc: Exception | None = None
    for attempt in range(1, _LLM_MAX_RETRIES + 1):
        try:
            async with httpx.AsyncClient(timeout=_LLM_TIMEOUT) as client:
                resp = await client.post(
                    f"{config.LLM_BASE_URL}/chat/completions",
                    headers={"Authorization": f"Bearer {config.LLM_API_KEY}"},
                    json=body,
                )
                resp.raise_for_status()
                return resp.json()["choices"][0]["message"]["content"]
        except httpx.HTTPStatusError as exc:
            last_exc = exc
            code = exc.response.status_code
            if code == 429:
                # 限流（Too Many Requests）：短时会恢复，是「偶发 429 限流」导致回答
                # 静默降级成模板兜底的根因。按 Retry-After 头退避后重试，直到重试上限。
                if attempt >= _LLM_MAX_RETRIES:
                    logger.error("LLM 连续被限流 %d 次，放弃：429", _LLM_MAX_RETRIES)
                    raise
                ra = exc.response.headers.get("Retry-After")
                try:
                    delay = float(ra) if ra is not None else _LLM_RETRY_BACKOFF ** attempt
                except (ValueError, TypeError):
                    delay = _LLM_RETRY_BACKOFF ** attempt
                logger.warning(
                    "LLM 返回 429（限流），%.1fs 后第 %d/%d 次重试",
                    delay, attempt, _LLM_MAX_RETRIES,
                )
                await asyncio.sleep(delay)
                continue
            # 其余 4xx（Key 无效 / 参数错误）重试无意义，直接抛出；5xx 才重试
            if code < 500 or attempt >= _LLM_MAX_RETRIES:
                raise
            logger.warning("LLM 返回 %d，准备第 %d/%d 次重试", code, attempt, _LLM_MAX_RETRIES)
        except _RETRYABLE as exc:
            last_exc = exc
            if attempt >= _LLM_MAX_RETRIES:
                logger.error("LLM 调用连续 %d 次失败，放弃：%r", _LLM_MAX_RETRIES, exc)
                break
            logger.warning(
                "LLM 调用第 %d/%d 次失败（%s），准备重试：%r",
                attempt, _LLM_MAX_RETRIES, type(exc).__name__, exc,
            )
        await asyncio.sleep(_LLM_RETRY_BACKOFF ** (attempt - 1))
    raise last_exc if last_exc is not None else LlmUnavailable("LLM 调用失败（未知原因）")


async def chat_struct(
    messages: list[dict],
    temperature: float = 0.2,
    json_mode: bool = False,
    max_tokens: int = 2000,
) -> str:
    """结构化/摘要/证据用快模型（STRUCT_MODEL），加速解析，不占用 QA 强模型。"""
    return await chat(
        messages, temperature=temperature, json_mode=json_mode,
        max_tokens=max_tokens, model=config.STRUCT_MODEL or config.LLM_MODEL,
    )


def llm_engine() -> str:
    return config.llm_provider_label()
