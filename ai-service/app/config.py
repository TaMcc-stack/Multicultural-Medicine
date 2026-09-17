"""全局配置：环境变量 + .env 文件。

支持多供应商 LLM / Embedding（均为 OpenAI 兼容接口）：
  LLM_PROVIDER    siliconflow（硅基流动）/ deepseek / dashscope；留空 = 不用大模型（规则+模板兜底）
  EMBED_PROVIDER  siliconflow / dashscope / local（本地哈希，不花钱）
DeepSeek 不提供 embedding API，向量检索请用 siliconflow 或 local。
"""
import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

# ---------- 供应商默认值 ----------
_PROVIDER_DEFAULTS = {
    "siliconflow": {
        "base_url": "https://api.siliconflow.cn/v1",
        "llm_model": "Qwen/Qwen2.5-7B-Instruct",   # 免费；付费可换 Qwen/Qwen2.5-72B-Instruct
        "struct_model": "Qwen/Qwen2.5-7B-Instruct",  # 结构化/摘要/证据用（快）
        "embed_model": "BAAI/bge-m3",               # 多语言 embedding，1024 维
    },
    "deepseek": {
        "base_url": "https://api.deepseek.com/v1",
        "llm_model": "deepseek-chat",               # V3.2 非思考模式；reasoner 为思考模式（QA 强模型）
        "struct_model": "deepseek-chat",            # 结构化/摘要/证据用
        "embed_model": "",                          # DeepSeek 无 embedding
    },
    "dashscope": {
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "llm_model": "qwen-plus",
        "struct_model": "qwen-turbo",               # 结构化/摘要/证据用（快）
        "embed_model": "text-embedding-v3",
    },
}


def _load_dotenv() -> None:
    env_file = BASE_DIR / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip())


_load_dotenv()

# ---------- LLM ----------
LLM_PROVIDER = os.getenv("LLM_PROVIDER", "").strip().lower()
LLM_API_KEY = os.getenv("LLM_API_KEY", "").strip()
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "").strip().rstrip("/")
LLM_MODEL = os.getenv("LLM_MODEL", "").strip()
STRUCT_MODEL = os.getenv("STRUCT_MODEL", "").strip()  # 结构化/摘要/证据用快模型；留空用供应商默认

# ---------- Embedding ----------
EMBED_PROVIDER = os.getenv("EMBED_PROVIDER", "").strip().lower()
EMBED_API_KEY = os.getenv("EMBED_API_KEY", "").strip()
EMBED_BASE_URL = os.getenv("EMBED_BASE_URL", "").strip().rstrip("/")
EMBED_MODEL = os.getenv("EMBED_MODEL", "").strip()

# ---------- 向后兼容：旧配置 DASHSCOPE_API_KEY ----------
# 未显式设置 LLM_PROVIDER / EMBED_PROVIDER 但填了 DASHSCOPE_API_KEY 时，视为 dashscope
if not LLM_PROVIDER and os.getenv("DASHSCOPE_API_KEY", "").strip():
    LLM_PROVIDER = "dashscope"
    LLM_API_KEY = os.getenv("DASHSCOPE_API_KEY", "").strip()
    LLM_MODEL = LLM_MODEL or "qwen-plus"
if not EMBED_PROVIDER and os.getenv("DASHSCOPE_API_KEY", "").strip():
    EMBED_PROVIDER = "dashscope"
    EMBED_API_KEY = os.getenv("DASHSCOPE_API_KEY", "").strip()
    EMBED_MODEL = EMBED_MODEL or "text-embedding-v3"

# ---------- 填充供应商默认值 ----------
def _fill(provider: str, base_url: str, model: str, embed_model: bool) -> tuple[str, str]:
    defaults = _PROVIDER_DEFAULTS.get(provider, {})
    if not base_url:
        base_url = defaults.get("base_url", "")
    if embed_model:
        if not model:
            model = defaults.get("embed_model", "")
    else:
        if not model:
            model = defaults.get("llm_model", "")
    return base_url, model


LLM_BASE_URL, LLM_MODEL = _fill(LLM_PROVIDER, LLM_BASE_URL, LLM_MODEL, embed_model=False)
EMBED_BASE_URL, EMBED_MODEL = _fill(EMBED_PROVIDER, EMBED_BASE_URL, EMBED_MODEL, embed_model=True)


def _fill_struct(base_url: str, model: str) -> tuple[str, str]:
    """结构化任务用模型：优先 STRUCT_MODEL，否则取供应商的 struct_model 默认值。"""
    defaults = _PROVIDER_DEFAULTS.get(LLM_PROVIDER, {})
    if not base_url:
        base_url = defaults.get("base_url", "")
    if not model:
        model = defaults.get("struct_model") or defaults.get("llm_model", "")
    return base_url, model


STRUCT_BASE_URL, STRUCT_MODEL = _fill_struct(LLM_BASE_URL, STRUCT_MODEL)

# ---------- 其他 ----------
KB_PATH = Path(os.getenv("KB_PATH", str(BASE_DIR / "data" / "papers.json")))
TOP_K = int(os.getenv("TOP_K", "5"))
# 每个问题送进回答生成的证据材料上限，减少 LLM 输入、加速回答。
#
# 2026-09-16 从 2 提到 3。原来的 2 是配合「按来源去重」用的（那时一个来源只出一条，
# 2 条 = 2 份资料）；改成按来源轮转后，同一个来源可以出多条，2 条对单文档知识库来说
# 仍然偏少（「云南糖尿病患病情况」只能拿到 2 段，写不全分民族数据）。3 条是覆盖面与
# 生成速度的折中——每条证据最多 _EVIDENCE_MAX_CHARS=2000 字，上限再往上加会明显拖慢生成。
MAX_EVIDENCE = int(os.getenv("MAX_EVIDENCE", "3"))

# 是否排除「内置文献」（结构化知识库证据 KB_EVIDENCE），只基于上传文档（RAG_CHUNK）回答。
# 开启后，检索层仅返回 RAG_CHUNK（上传的两份文档切片），所有问答只依据这两份文档，
# 不再引用内置的结构化证据层（papers.json 文献 + AI 抽取动态证据）。
EXCLUDE_BUILTIN_LITERATURE = os.getenv("EXCLUDE_BUILTIN_LITERATURE", "").strip().lower() in ("1", "true", "yes", "on")

# ---------- 查询扩展（Query Expansion） ----------
# 开启后，检索前先用 LLM 把用户问题改写成若干等价问句，逐条检索后逐条取最高分。
# 动机：口语问法与文献书面表述有落差（「得的人多吗」vs「患病率」），单问句容易漏。
#
# 实测（2026-09-11，eval/run_eval.py，20 个用例）：**默认关闭**。
#   关闭：检索层 35/39 (89.7%)，4 个用例检索失败
#   开启：检索层 34/39 (87.2%)，同样那 4 个 + 新增 1 个（「白族糖尿病怎么预防」）
#   两次开启的结果完全一致，不是抖动。
# 原因：当前知识库只有 2 份资料、MAX_EVIDENCE=2，召回本来就接近饱和，
# 扩展带来的不是「多召回」而是「打分被扰动、把错的切片顶上来」。
# 知识库扩容到几十份资料后再打开重测（QUERY_EXPANSION_ENABLED=1），届时可能反转。
QUERY_EXPANSION_ENABLED = os.getenv("QUERY_EXPANSION_ENABLED", "0").strip().lower() in ("1", "true", "yes", "on")
QUERY_EXPANSION_VARIANTS = int(os.getenv("QUERY_EXPANSION_VARIANTS", "3"))

# ---------- 理解层动态词典（从 Java 后端拉取，管理端可动态维护） ----------
# 失败时自动回退内置静态词典，不影响问答主链路
JAVA_BASE_URL = os.getenv("JAVA_BASE_URL", "http://localhost:8080").strip().rstrip("/")
NLU_DICT_URL = os.getenv("NLU_DICT_URL", f"{JAVA_BASE_URL}/api/internal/nlu/dict").strip()
# 词典缓存 TTL（秒）：管理端改词典后最多 TTL 内生效
NLU_DICT_TTL = int(os.getenv("NLU_DICT_TTL", "30"))
# 拉取失败后的重试间隔（秒），避免持续打后端
NLU_DICT_FAIL_RETRY = int(os.getenv("NLU_DICT_FAIL_RETRY", "10"))


# ---------- 能力判断 ----------
def llm_enabled() -> bool:
    """是否配置了可用的 LLM（供应商 + Key + base_url + model）"""
    return bool(LLM_PROVIDER and LLM_API_KEY and LLM_BASE_URL and LLM_MODEL)


def embedding_enabled() -> bool:
    """是否配置了远程 embedding（local 或未配置 Key 时返回 False）"""
    return bool(EMBED_PROVIDER and EMBED_PROVIDER != "local"
                and EMBED_API_KEY and EMBED_BASE_URL and EMBED_MODEL)


def llm_provider_label() -> str:
    return f"{LLM_PROVIDER}:{LLM_MODEL}" if llm_enabled() else "rules/template"


def embedding_provider_label() -> str:
    if not embedding_enabled():
        return "local-hashing"
    return f"{EMBED_PROVIDER}:{EMBED_MODEL}"
