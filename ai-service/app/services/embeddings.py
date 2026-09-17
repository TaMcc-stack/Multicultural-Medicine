"""向量服务：多供应商 embedding（硅基流动 bge-m3 / DashScope text-embedding-v3），
未配置时降级本地哈希向量（确定性字符 n-gram，256 维）。"""
import hashlib
import math
import re

import httpx

from .. import config

_HASH_DIM = 256


def tokenize(text: str) -> list[str]:
    """中英文混合分词：英文/数字词 + 汉字单字 + 汉字二元组"""
    text = (text or "").lower()
    words = re.findall(r"[a-z0-9]+", text)
    cn_chars = re.findall(r"[\u4e00-\u9fff]", text)
    bigrams = [cn_chars[i] + cn_chars[i + 1] for i in range(len(cn_chars) - 1)]
    return words + cn_chars + bigrams


def hashing_embed(text: str, dim: int = _HASH_DIM) -> list[float]:
    """确定性本地哈希向量（降级方案）：字符 n-gram 计数 + L2 归一化"""
    vec = [0.0] * dim
    for token in tokenize(text):
        digest = int(hashlib.md5(token.encode("utf-8")).hexdigest(), 16)
        vec[digest % dim] += 1.0
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


async def embed_texts(texts: list[str]) -> list[list[float]]:
    """批量向量化：远程 embedding（硅基流动 / DashScope）优先，失败/未配置降级本地哈希"""
    if not texts:
        return []
    if not config.embedding_enabled():
        return [hashing_embed(t) for t in texts]
    try:
        body: dict = {"model": config.EMBED_MODEL, "input": texts}
        # DashScope text-embedding-v3 支持 dimensions 参数；硅基流动 bge-m3 固定 1024 维不传
        if config.EMBED_PROVIDER == "dashscope":
            body["dimensions"] = 1024
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{config.EMBED_BASE_URL}/embeddings",
                headers={"Authorization": f"Bearer {config.EMBED_API_KEY}"},
                json=body,
            )
            resp.raise_for_status()
            data = sorted(resp.json()["data"], key=lambda d: d["index"])
            return [item["embedding"] for item in data]
    except Exception:
        return [hashing_embed(t) for t in texts]


def embedding_engine() -> str:
    return config.embedding_provider_label()
