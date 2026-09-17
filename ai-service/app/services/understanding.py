"""问题理解：民族 / 疾病 / 意图识别。Qwen 优先，规则兜底（无 Key 可用）。

词典动态化：
- 规则词典（民族/疾病/意图关键词）优先从 Java 后端 /api/internal/nlu/dict 拉取
  （管理端可动态维护，TTL 缓存，Java 与 Python 规则链同源生效）；
- 拉取失败时回退内置静态词典（离线可用，不阻塞问答主链路）。
"""
import json
import logging
import re
import time

import httpx

from .. import config
from . import llm
from .kb import kb

logger = logging.getLogger("ai-service.understanding")

# ---------- 内置兜底词典（拉不到后端动态词典时使用） ----------
# 民族表 2026-09-16 与 backend 的 `NluService.ETHNICITIES` 对齐：此前这里只有 15 个民族，
# **缺满族、土家族、瑶族、普米族、黎族等 15 个**。后果是「满族糖尿病患病情况怎么样」
# 识别不出民族槽位 → 检索退化成只按「糖尿病」匹配 → 命中一堆泛化片段（evidence_found=true）
# → 回答里写着「未覆盖满族人群」却既没有民族槽位可登记、也没触发「未命中」判据，
# 于是「反馈此问题」入口不出现（用户报的正是这条）。
#
# 为什么会存在这份重复的表：`config.NLU_DICT_URL` 指向后端的 /api/internal/nlu/dict，
# 但后端**根本没有这个路由**——/api/** 全部先过认证拦截器，所以拿到的是 401 而不是 404，
# 报错信息看着像「没权限」，实际是「没这个接口」。于是每次请求都静默回退到这份内置表。
# 要治本得在后端补上那个接口；在那之前，两张表必须手工同步。
#
# 比后端多一个「汉族」：首页热门问题里有「傣族高血压和汉族比，谁更严重？」，
# 汉族必须可识别。后端那份没有汉族——将来真去拉动态词典前，记得先给后端补上，
# 否则这个对比问法会当场退化成「没识别出民族」。
_FALLBACK = {
    "ethnicities": ["维吾尔族", "哈萨克族", "傈僳族", "景颇族", "布朗族", "阿昌族", "德昂族",
                    "独龙族", "基诺族", "白族", "彝族", "哈尼族", "傣族", "纳西族", "壮族",
                    "苗族", "回族", "藏族", "拉祜族", "佤族", "瑶族", "普米族", "怒族", "水族",
                    "蒙古族", "满族", "侗族", "布依族", "土家族", "黎族", "汉族"],
    "diseases": ["糖尿病", "高血压", "代谢综合征", "脂肪肝", "视网膜病变",
                 "高尿酸血症", "痛风"],
    "intentRules": [
        {"intent": "prevalence", "keywords": ["患病率", "患病", "流行", "发病率", "多少人", "比例", "现状", "患病情况", "患病现况",
                                              # 口语化问法（首页预设问题等）：问「多不多 / 严重不严重」即患病情况
                                              "多吗", "人多", "得的人", "常见吗", "普遍", "严重吗", "高吗"]},
        {"intent": "genetics", "keywords": ["遗传", "基因", "易感", "多态性", "突变", "位点"]},
        {"intent": "diet", "keywords": ["膳食", "饮食", "吃什么", "乳扇", "营养", "食物", "脂肪酸",
                                        # 口语化问法：问「吃出病 / 怎么吃」即饮食与生活方式
                                        "吃出", "饮食习惯", "怎么吃", "生活习惯"]},
        {"intent": "risk", "keywords": ["危险因素", "风险", "病因", "为什么会得", "相关因素", "影响因素", "保护因素",
                                        # 口语化问法：长辈有病史时的担忧属于危险因素
                                        "小心", "要不要紧", "会不会得", "担心",
                                        # 「怎么预防」问的是危险因素与干预方向，不归到预防就会退化成
                                        # overview，令意图过滤失效、患病率数据被拿来答预防问题
                                        "预防", "怎么防", "如何防", "防范"]},
        {"intent": "overview", "keywords": ["研究", "概况", "总结", "哪些", "介绍", "自我管理", "知晓率", "控制率"]},
    ],
    "boundary": ["诊断", "确诊", "怎么治", "如何治疗", "怎么治疗", "治疗方案",
                 "能不能治好", "治好", "吃药", "用药", "服药", "吃什么药",
                 "剂量", "吃多少", "处方", "开药", "挂什么科", "注射"],
    "ambiguous": ["研究情况", "哪些发现", "相关研究", "什么情况", "怎么样", "如何", "啥情况"],
}
VALID_INTENTS = {"prevalence", "risk", "diet", "genetics", "overview"}

# ---------- 医疗安全边界：用药 / 诊疗咨询 ----------
# 医疗 AI 的红线：不得给出具体药物名称、用药方案或治疗建议。
# 命中即拦截，**不进检索、不喂证据**——否则系统会为了「有依据」把库里
# 毫不相干的片段（吃鸡蛋、傣族吸烟、低教育水平）拼进来，既生硬又有风险。
#
# 用「强指示词」而不是单个「药」字：要区分
#   「有没有适合白族人的降糖药？」→ 是求助（拦）
#   「白族的药物代谢基因有什么不同？」→ 是科普（不拦）
_MEDICAL_ADVICE_PATTERNS = [
    r"吃什么药", r"吃啥药", r"用什么药", r"用啥药", r"该吃(什么|啥)药",
    r"推荐.{0,6}药", r"有没有.{0,8}药", r"哪些药", r"什么药(能|可|好)",
    r"(能|可以|怎么|如何)(治|治愈|根治)", r"根治", r"偏方", r"秘方", r"特效药",
    r"开(点|些)?药", r"开个方", r"处方", r"买药", r"自己(吃|买|用)",
    r"剂量", r"吃几(片|粒|次|颗)", r"一天(吃|服)几", r"吃多少(药|片|粒)",
    r"挂(什么|哪个)科", r"停药", r"换药", r"加药", r"减药", r"打(针|胰岛素)",
    # 「降糖药 / 降压药 / 降脂药」这类具体药类 + 求助语气
    r"(降糖|降压|降脂|降尿酸|止痛|消炎)药",
]
_MEDICAL_ADVICE_RE = re.compile("|".join(_MEDICAL_ADVICE_PATTERNS))

# 明确的用药/诊疗请求话术（命中即拦截，无需再看别的）
MEDICAL_ADVICE_REPLY = (
    "对不起，作为健康科普智能体，我无法为您提供具体的用药建议或治疗方案。\n\n"
    "糖尿病属于慢性病，用药必须由专业医生根据您的血糖水平、并发症和身体状况来决定。"
    "请务必前往正规医院内分泌科就诊，切勿自行购药服用。\n\n"
    "如果您想了解的是**民族健康研究**方面的内容（例如某个民族的患病情况、"
    "危险因素、饮食与生活方式、遗传研究等），我很乐意继续为您介绍。"
)


def detect_medical_advice(question: str) -> bool:
    """规则层判断是否为「用药 / 诊疗」求助（求助语气 + 具体药物/治疗）。"""
    return bool(_MEDICAL_ADVICE_RE.search(question or ""))

# 泛民族查询的哨兵值。
# 用户问「云南各民族的遗传研究」这类不指向单一民族的问题时，用它代表「全库民族范围」。
# 若沿用 None（未识别），会被验证层判成「缺民族」而整条链路拦掉——但知识库里确实有
# 「云南六个民族全基因组测序」「25个少数民族 Y 染色体分析」这类泛民族研究可答。
ETHNICITY_GENERAL = "GENERAL"
_GENERAL_ETHNIC_KEYWORDS = [
    "各民族", "少数民族", "各族", "多个民族", "不同民族", "云南民族",
    "多民族", "民族整体", "各民族人群", "全省民族", "各个民族",
]
# 展示层写法：后台/提示语/标准化问句里不能直接出现 "GENERAL"
ETHNICITY_GENERAL_LABEL = "各民族"


def eth_label(ethnicity: str | None) -> str:
    """民族在展示文案里的写法：泛民族 → 「各民族」，其余原样。"""
    if not ethnicity:
        return ""
    return ETHNICITY_GENERAL_LABEL if ethnicity == ETHNICITY_GENERAL else ethnicity


class DictProvider:
    """动态词典：从 Java 后端拉取（TTL 缓存），失败回退内置词典。"""

    def __init__(self) -> None:
        self._cache: tuple[float, dict] | None = None  # (fetched_at, dict)

    def get(self) -> dict:
        now = time.time()
        cached = self._cache
        if cached is not None and now - cached[0] < config.NLU_DICT_TTL:
            return cached[1]
        return self._fetch(now)

    def _fetch(self, now: float) -> dict:
        try:
            # 超时放宽到 6s：2s 在首次请求（后端冷启动/连接池未预热）时经常超时，
            # 导致规则层被迫回退内置词典（有 TTL 缓存，仅在过期时才拉取，不影响常规请求耗时）
            resp = httpx.get(config.NLU_DICT_URL, timeout=6.0)
            resp.raise_for_status()
            body = resp.json()
            data = body.get("data") if isinstance(body, dict) else None
            parsed = self._parse(data)
            self._cache = (now, parsed)
            logger.info("理解层词典已从后端加载：version=%s 民族=%d 疾病=%d 意图规则=%d",
                        data.get("version") if isinstance(data, dict) else "?",
                        len(parsed["ethnicities"]), len(parsed["diseases"]),
                        len(parsed["intentRules"]))
            return parsed
        except Exception as exc:  # 网络异常 / JSON 解析失败 → 回退内置词典
            logger.warning("理解层词典拉取失败，回退内置词典：%s", exc)
            # 失败也记录时间点，按较短间隔重试，避免每次请求都打后端
            self._cache = (now - config.NLU_DICT_TTL + config.NLU_DICT_FAIL_RETRY, _FALLBACK)
            return _FALLBACK

    @staticmethod
    def _parse(data) -> dict:
        """后端快照 → 本模块结构（缺字段用内置兜底，逐项容错）"""
        if not isinstance(data, dict):
            return _FALLBACK
        rules = []
        for rule in data.get("intentRules") or []:
            if isinstance(rule, dict) and rule.get("intent") and rule.get("keywords"):
                rules.append({
                    "intent": rule["intent"],
                    "keywords": [k for k in rule["keywords"] if isinstance(k, str)],
                })
        return {
            "ethnicities": [e for e in (data.get("ethnicities") or _FALLBACK["ethnicities"]) if isinstance(e, str)],
            "diseases": [d for d in (data.get("diseases") or _FALLBACK["diseases"]) if isinstance(d, str)],
            "intentRules": rules or _FALLBACK["intentRules"],
            "boundary": [b for b in (data.get("boundary") or _FALLBACK["boundary"]) if isinstance(b, str)],
            "ambiguous": [a for a in (data.get("ambiguous") or _FALLBACK["ambiguous"]) if isinstance(a, str)],
        }


dict_provider = DictProvider()


# ---------- 意图明确性判定 ----------
# 具体指标词：命中说明用户问的是可直接检索回答的明确指标，无需再让用户澄清
_SPECIFIC_MARKERS = [
    "患病率", "发病率", "检出率", "知晓率", "控制率", "治疗率", "死亡率", "标化",
    "危险因素", "影响因素", "保护因素", "病因", "相关因素",
    "基因", "多态性", "位点", "突变", "易感",
    "饮食", "膳食", "营养", "食物", "脂肪酸",
    "样本量", "概况",
]

# 具体指标 → 所属意图族（仅用于展示层细粒度标注，不改变意图码 taxonomy / 检索行为）
_METRIC_FAMILY = {
    "患病率": "prevalence", "发病率": "prevalence", "检出率": "prevalence",
    "知晓率": "prevalence", "控制率": "prevalence", "治疗率": "prevalence", "死亡率": "prevalence",
    "危险因素": "risk", "影响因素": "risk", "相关因素": "risk", "保护因素": "risk", "病因": "risk",
    "基因": "genetics", "多态性": "genetics", "位点": "genetics", "突变": "genetics", "易感性": "genetics",
    "饮食": "diet", "膳食": "diet", "营养": "diet", "食物": "diet",
}


def _detect_metric(question: str) -> str | None:
    """从问题原文中找用户明确问到的具体指标（如「患病率」），长词优先。

    命中说明用户问的就是该指标，展示层据此把「查询意图」从泛化的「患病情况」
    细化成「患病率」，避免「问患病率、答患病情况」的识别落差。
    """
    q = question or ""
    hits = [m for m in _METRIC_FAMILY if m in q]
    return max(hits, key=len) if hits else None


def metric_family(metric: str) -> str | None:
    """具体指标 → 所属意图族（供展示层决定标准化问句的措辞后缀）"""
    return _METRIC_FAMILY.get(metric)


def judge_intent_clarity(question: str, intent, d: dict,
                         inferred: bool = False) -> tuple[bool, str]:
    """判定查询意图是否明确。

    返回 (是否明确, 原因码)：
      - intent 缺失 → 不明确（no_intent）
      - 命中模糊词（如「怎么样/如何/什么情况」）且未指明任何具体指标 → 不明确（ambiguous）
      - 问题里没有任何意图关键词、意图完全由大模型推测而来，且未指明具体指标
        → 不明确（inferred_intent），避免替用户猜测后直接放行
      - 其余 → 明确

    说明：像「患病情况如何」这种，规则层会命中 prevalence 关键词，但「患病情况」是
    上位概念（患病率/知晓率/控制率都算），配合「如何」应视为意图宽泛，交给用户确认
    具体想了解哪方面，而不是替用户窄化成某一项。
    """
    q = question or ""
    if not intent:
        return False, "no_intent"
    specific = [m for m in _SPECIFIC_MARKERS if m in q]
    ambiguous_hit = [a for a in (d.get("ambiguous") or []) if a in q]
    if ambiguous_hit and not specific:
        return False, "ambiguous"
    if inferred and not specific:
        return False, "inferred_intent"
    return True, ""


def rule_understand(question: str) -> dict:
    q = question or ""
    d = dict_provider.get()
    ethnicities = d["ethnicities"]
    diseases = d["diseases"]

    # 最长匹配：优先命中多字民族/疾病
    ethnicity = next((e for e in sorted(ethnicities, key=len, reverse=True) if e in q), None)
    disease = next((x for x in sorted(diseases, key=len, reverse=True) if x in q), None)

    # 泛民族：问题里没有具体民族，但出现了「各民族 / 少数民族 / 多民族」这类词。
    # 放在具体民族之后判断——「各民族中白族的…」这种仍然按白族精准处理。
    if ethnicity is None and any(k in q for k in _GENERAL_ETHNIC_KEYWORDS):
        ethnicity = ETHNICITY_GENERAL

    intent = None
    for rule in d["intentRules"]:
        if any(k in q for k in rule["keywords"]):
            intent = rule["intent"]
            break
    return {"ethnicity": ethnicity, "disease": disease, "intent": intent}


def _normalize(raw: dict, d: dict) -> dict:
    ethnicity = raw.get("ethnicity")
    disease = raw.get("disease")
    intent = raw.get("intent")
    # 泛民族是受控哨兵值，不走民族词表校验
    if ethnicity != ETHNICITY_GENERAL:
        ethnicity = ethnicity if ethnicity in d["ethnicities"] else None
    disease = disease if disease in d["diseases"] else None
    intent = intent if intent in VALID_INTENTS else None
    return {"ethnicity": ethnicity, "disease": disease, "intent": intent}


# 上下文继承使用的最近轮次数：追问通常紧接上一轮，取最近几轮足够，
# 又不至于把早已跑偏的话题带回来
_HISTORY_TURNS = 3


def _history_context(history) -> str:
    """把最近几轮对话压成一段上下文文本，供 LLM 补全缺失实体。"""
    if not history:
        return ""
    lines: list[str] = []
    for h in list(history)[-_HISTORY_TURNS:]:
        if isinstance(h, str):
            if h.strip():
                lines.append(f"用户：{h.strip()}")
            continue
        if not isinstance(h, dict):
            continue
        q = h.get("question") or h.get("text") or h.get("content") or ""
        if q:
            lines.append(f"用户：{str(q).strip()}")
        eth = h.get("ethnicity") or ""
        dis = h.get("disease") or ""
        if eth or dis:
            lines.append(f"（该轮识别结果：民族={eth or '未识别'}，疾病={dis or '未识别'}）")
    return "\n".join(lines)


def _inherit_from_history(history) -> dict:
    """规则兜底：取最近几轮里最后一个非空的民族/疾病，用于补全本轮缺失的槽位。

    LLM 不可用（或调用失败）时，追问「有没有适合白族人的日常血糖监测方法」这类
    不带疾病名的问题，也必须能接上上一轮的「糖尿病」——否则会被验证层判成
    「缺疾病」而直接拒绝回答。
    """
    eth = dis = None
    for h in reversed(list(history or [])):
        if not isinstance(h, dict):
            continue
        if dis is None and h.get("disease"):
            dis = h["disease"]
        if eth is None and h.get("ethnicity"):
            eth = h["ethnicity"]
        if eth and dis:
            break
    return {"ethnicity": eth, "disease": dis}


async def understand(question: str, history=None) -> dict:
    """Qwen 理解优先（失败静默降级规则），结果与规则理解合并。

    history 为最近几轮对话（[{question, ethnicity, disease}...]）。追问常省略疾病名
    （「有没有适合白族人的日常血糖监测方法」），必须结合上下文补全实体，否则会被
    验证层判成「缺疾病」而拒绝回答。
    """
    d = dict_provider.get()
    result = rule_understand(question)
    rule_intent = result.get("intent")  # 规则层原始意图（未识别为 None）
    llm_metric = None
    llm_medical_advice = False
    if llm.available():
        try:
            covered = kb.status()
            metric_vocab = "/".join(_METRIC_FAMILY)
            ctx = _history_context(history)
            prompt = (
                "你是一个专业的医学实体识别助手。你的任务是结合【历史对话上下文】，"
                "从用户最新的提问中提取出：民族（ethnicity）、疾病（disease）和查询意图（intent）。\n"
                "用户常用口语、长辈口吻或带生活场景的方式提问，请忽略无关的场景描述。\n"
                "【核心规则】\n"
                "1. 上下文补全（最重要）：如果用户当前问题中缺失了「疾病」，请从历史对话的最近一轮中"
                "继承。例如上一轮问「白族糖尿病」，这一轮问「怎么监测血糖」，你必须把疾病补全为"
                "「糖尿病」。民族同理。\n"
                "2. 智能推理：如果历史里也找不到，但用户的意图明显指向某种疾病（如「血糖监测」对应"
                "「糖尿病」，「血压控制」对应「高血压」），请结合常识补全疾病字段。\n"
                "3. 容错处理：尽力补全后仍然缺失的字段填 null。民族绝不编造——推断不出就填 null。\n"
                "4. 泛民族：**仅当用户在问「多个/所有民族」时**（问题里出现「各民族 / 少数民族 / "
                "多民族 / 云南民族 / 各族」这类明确指向多个民族的词），ethnicity 才填 "
                f"\"{ETHNICITY_GENERAL}\"。**问题里完全没提民族**（如「我吃什么药可以治好糖尿病」）"
                "时，ethnicity 必须填 null，绝不能填 GENERAL。也不要挑一个具体民族代替。\n"
                "5. 医疗安全：如果用户在**索要具体的用药建议、药物名称或治疗方案**"
                "（如「吃什么药」「有没有适合白族的降糖药」「怎么根治」「剂量多少」「推荐什么药」），"
                "medical_advice 填 true；只是问研究、患病情况、饮食注意事项则填 false。\n"
                "6. 取值必须落在下面的受控词表内，不得自造：\n"
                f"   ethnicity 候选：{covered.get('coveredEthnicities') or d['ethnicities']}\n"
                f"   disease 候选：{covered.get('coveredDiseases') or d['diseases']}\n"
                "   intent 只能是 prevalence(患病情况)/risk(危险因素)/"
                "diet(饮食与生活方式)/genetics(遗传相关)/overview(研究概况) 之一；"
                "口语提问也要归到这五类（如「怎么监测/怎么预防」归 overview）\n"
                "   metric 用户问的具体指标，只能是 " + metric_vocab + " 之一；"
                "用户没有明确问某个指标就填 null\n"
                "只输出 JSON："
                '{"ethnicity":"...或null","disease":"...或null","intent":"...或null",'
                '"metric":"...或null","medical_advice":true或false}'
                + (f"\n\n【历史对话上下文】\n{ctx}" if ctx else "\n\n【历史对话上下文】\n（无，这是首轮提问）")
                + f"\n\n用户最新问题：{question}"
            )
            content = await llm.chat(
                [{"role": "user", "content": prompt}],
                temperature=0.0, json_mode=True, max_tokens=260)
            raw = json.loads(content)
            parsed = _normalize(raw, d)
            # Qwen 识别结果优先填补规则识别的空缺
            for key in ("ethnicity", "disease", "intent"):
                if result.get(key) is None and parsed.get(key):
                    result[key] = parsed[key]
            # 具体指标：须在受控词表内才算数（防幻觉）
            llm_metric = raw.get("metric")
            if llm_metric not in _METRIC_FAMILY:
                llm_metric = None
            llm_medical_advice = raw.get("medical_advice") is True
            result["engine"] = llm.llm_engine()
        except Exception:
            pass
    # 上下文继承兜底：规则与 LLM 都没识别出来时，用最近几轮补全（追问场景）。
    # 放在最后，是为了让「本轮问题里真实出现的实体」和「LLM 结合上下文的判断」优先。
    inherited = _inherit_from_history(history)
    for key in ("ethnicity", "disease"):
        if result.get(key) is None and inherited.get(key):
            result[key] = inherited[key]
            result[f"{key}_from_context"] = True
    if "engine" not in result:
        result["engine"] = "rules"
    # 医疗安全边界：规则层命中 **或** LLM 判定为「索要用药/治疗建议」→ 拦截。
    # 用「或」而非「与」——安全场景宁可多拦一个，也不能漏；规则层覆盖固定说法，
    # LLM 补足口语变体（「有没有适合白族的降糖药」规则可能漏，LLM 能识别）。
    result["medical_advice"] = detect_medical_advice(question) or llm_medical_advice
    # 意图明确性：供 /api/understand 决定 pass / clarify
    # inferred=True 表示问题文本里没有任何意图关键词、意图是大模型推测出来的
    result["intent_inferred"] = rule_intent is None and result.get("intent") is not None
    clear, reason = judge_intent_clarity(
        question, result.get("intent"), d, result.get("intent_inferred", False))
    result["intent_clear"] = clear
    result["clarity_reason"] = reason
    # 具体指标：问题原文命中的指标词（用户原话，最可信）优先；
    # 否则采用大模型判断的指标 —— 仅当意图已明确（clear）且与意图同族才采用，
    # 避免把「患病情况如何」这类宽泛问题替用户窄化成「患病率」
    metric = _detect_metric(question)
    if metric is None and llm_metric and clear and _METRIC_FAMILY.get(llm_metric) == result.get("intent"):
        metric = llm_metric
    result["metric"] = metric
    return result
