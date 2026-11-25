import re


_TASK_VERB = r'(?:说明|解释|判断|对比|比较|分析|列出|给出|指出|回答|区分|核对|计算|描述|写出|建议)'
_TASK_BREAK = re.compile(
    r'？|\?(?=\s)|[，,；;。]\s*(?=(?:再|另外|最后|然后|也请|还要|还请)\s*' + _TASK_VERB
    + r')|(?=(?:并(?:请|且)?|同时|还要|还请)\s*' + _TASK_VERB + r')'
)
_TASK_START = re.compile(
    r'^(?:(?:并(?:请|且)?|同时|再|另外|最后|然后|也请|还要|还请|请(?:你)?|分别|逐项|简要|详细)[\s，,：:]*)*'
    + _TASK_VERB
    + r'|^(?:please\s+)?(?:explain|describe|compare|list|give|check|answer|calculate|identify|state)\b',
    re.IGNORECASE,
)
_CLAUSE_PADDING = ' \t\r\n，,；;'


def explicit_answer_parts(question: str) -> tuple[str, ...]:
    """Locate alongside the full question only short, explicitly connected requests."""
    if len(question) > 640:
        return ()
    boundaries = sorted({0, len(question), *(match.end() for match in _TASK_BREAK.finditer(question))})
    spans = []
    for start, end in zip(boundaries, boundaries[1:]):
        clause = question[start:end].strip(_CLAUSE_PADDING)
        if not clause:
            continue
        if spans and not (clause.endswith(('？', '?')) or _TASK_START.match(clause)):
            # A scope or formatting sentence qualifies the preceding task.
            spans[-1] = (spans[-1][0], end)
        else:
            spans.append((start, end))
    if not 2 <= len(spans) <= 8:
        return ()
    return tuple(question[start:end].strip(_CLAUSE_PADDING) for start, end in spans)


TASK_PROMPTS = {
    "general_qa": (
        "你是产业链数据空间平台的智能问答助手。请基于给定的数据依据回答用户问题，"
        "用中文直接回答；引用依据时标注[来源n]。指出明确缺失的资料，"
        "不要用常识补写业务事实。"
    ),
    "chain_risk_analysis": (
        "你是产业链风险分析专家。请基于数据空间中的数据依据，对用户指定的产业链场景进行风险分析。\n"
        "输出结构：\n"
        "1. 总体判断（低/中/高风险及一句话结论）\n"
        "2. 主要风险点（按严重程度排序，每项标注相关依据[来源n]）\n"
        "3. 影响分析（对供给、质量、交付、成本等维度）\n"
        "4. 应对建议（可执行的缓解措施）\n"
        "只依据给定数据依据进行分析，避免编造具体数字。"
    ),
    "demand_evaluation": (
        "你是客户需求综合评判专家。请基于数据空间中的数据依据，对用户提出的客户需求进行综合评判。\n"
        "输出结构：\n"
        "1. 需求理解（一句话概括）\n"
        "2. 需求价值评估（商业价值、紧迫度，标注依据[来源n]）\n"
        "3. 可行性评估（技术、资源、数据支撑度）\n"
        "4. 综合评级（A/B/C 及理由）\n"
        "5. 建议行动\n"
        "只依据给定数据依据进行评判，依据不足时说明缺口。"
    ),
    "data_summary": (
        "你是数据空间综合分析助手。请对给定的数据依据做综合摘要，"
        "提炼关键指标、主题与异常点，用中文分点输出，并标注依据[来源n]。"
    ),
}

RETRIEVAL_HINTS = {
    "search_service": "（本回答由检索服务召回数据空间相关内容后生成）",
    "bundle": "（本回答直接消费数据空间索引 bundle 生成）",
    "none": "（本回答基于用户提供的数据生成）",
}


def build_system_prompt(task_type: str, retrieval_mode: str, has_sources: bool = True) -> str:
    base = TASK_PROMPTS.get(task_type, TASK_PROMPTS["general_qa"])
    hint = RETRIEVAL_HINTS.get(retrieval_mode, "")
    if has_sources:
        evidence_rule = (
            "存在参考信息时，只能引用实际出现的[来源n]。明确区分程序计算事实、近似匹配与解释性推断；"
            "保留给定的单位、时间范围、定位和质量限制，不得把相似度写成尺寸、拓扑、因果或可替换性证明。"
            "来源注明部分、关键清单、示意、追溯范围或未建模内容时，结论须保留该范围限制；"
            "清单、图示和照片能支持用途判断的程度，要与完整性和合规认证分别说明。"
            "只有来源明确证明覆盖完整范围或给出适用认证声明时，才能断言完整或已经认证合规。"
        )
    else:
        evidence_rule = "当前没有参考信息，不得虚构[来源n]或声称引用了未提供的数据。"
    return base + "\n" + hint + "\n" + evidence_rule
