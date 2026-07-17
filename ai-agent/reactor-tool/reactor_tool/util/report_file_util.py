import re


_STRICT_META_INSTRUCTION_MARKERS = (
    "不要写",
    "不得写",
    "禁止写",
    "禁止补写",
    "不能从",
    "只允许使用",
    "仅允许使用",
    "只能写",
    "未给出的内容",
    "报告必须包含",
    "执行计划严格",
    "整个运行中",
    "最终交付物请",
    "输出格式要求",
    "不得重复生成",
    "不得调用",
    "请勿",
    "严禁",
)


def _normalize_strict_fact_candidate(candidate: str) -> str:
    """Keep factual clauses while removing inline report-control instructions."""
    factual_clauses = []
    for raw_clause in re.split(r"(?<=[。！？；;])\s*", (candidate or "").strip()):
        clause = raw_clause.strip()
        if not clause:
            continue

        if re.fullmatch(
            r"MCP\s*全称\s*(?:只能|仅能|必须)?\s*写(?:作|为)?\s*"
            r"Model Context Protocol[。.]?",
            clause,
            flags=re.IGNORECASE,
        ):
            factual_clauses.append("MCP 全称为 Model Context Protocol。")
            continue

        if any(marker in clause for marker in _STRICT_META_INSTRUCTION_MARKERS):
            continue
        factual_clauses.append(clause)

    return "".join(factual_clauses).strip()


def sanitize_report_html_content(content: str) -> str:
    """清洗报告模型输出的 HTML 包装，确保最终落盘为可直接预览的纯 HTML。"""
    normalized = (content or "").strip()
    if normalized.lower().startswith("html:"):
        normalized = normalized[5:].lstrip()
    if normalized.startswith("```html"):
        normalized = normalized[len("```html"):].lstrip()
    elif normalized.startswith("```"):
        normalized = normalized[len("```"):].lstrip()
    if normalized.endswith("```"):
        normalized = normalized[:-3].rstrip()
    return normalized


def sanitize_strict_grounded_markdown(content: str) -> str:
    """Drop unsupported claims from Markdown produced for a closed-world request."""
    normalized = (content or "").strip()
    if not normalized:
        return normalized

    unsupported_line = re.compile(
        r"https?://|example\.com|\b(?:curl|netstat|docker\s+ps|gradlew|mvn\s+test)\b|"
        r"\b(?:chrome|firefox|safari)\b|\b(?:cookie|session|localstorage|iframe|mock|ci)\b|"
        r"(?:<\s*\d+\s*ms|\d+\s*(?:ms|秒|分钟|小时|天))|(?:微信|支付宝|paypal|银行卡)支付|"
        r"(?:estimated_tokens|quota_used|remaining_quota|model_name|paid_quota|frozen_quota)|"
        r"(?:数据库|事务日志|原子性|固定换算系数|换算系数|系数\s*=)",
        re.IGNORECASE,
    )
    lines = []
    for line in normalized.splitlines():
        if line.strip().startswith("```") or "tool_code" in line or "report_tool(" in line:
            continue
        if unsupported_line.search(line):
            continue
        lines.append(line)

    cleaned = "\n".join(lines)
    cleaned = re.sub(r"\[\[\d+]]\([^)]*\)", "", cleaned)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned).strip()
    return cleaned


def render_strict_query_markdown(original_query: str, title: str) -> str:
    """Render a deterministic report from an explicit closed-world fact list."""
    facts = []
    for raw_line in (original_query or "").splitlines():
        line = raw_line.strip()
        if line.startswith(("- ", "* ")):
            fact = _normalize_strict_fact_candidate(line[2:])
            if fact and fact not in facts:
                facts.append(fact)

    if not facts:
        raise ValueError("严格闭集报告未提供可提取的事实条目")

    report_title = (title or "严格事实报告").strip()
    payment_facts = [fact for fact in facts if any(token in fact for token in ("¥", "BENEFIT_GRANTED", "付费额度", "冻结额度"))]
    agent_facts = [fact for fact in facts if any(token in fact for token in ("普通聊天", "Agent Loop", "todo_write", "CompletionGate", "MCP", "utility_estimate_llm_quota", "跨会话记忆", "报告上传"))]

    lines = [
        f"# {report_title}",
        "",
        "## 演示目标",
        "本报告仅整理用户明确提供的已验证事实，用于现场演示复核；未提供的内容不作推断。",
        "",
        "## 已验证事实",
    ]
    lines.extend(f"- {fact}" for fact in facts)

    lines.extend(["", "## 完整演示流程"])
    lines.extend(f"{index}. 现场展示并核对：{fact}" for index, fact in enumerate(facts, start=1))

    lines.extend(["", "## 每步预期结果"])
    lines.extend(f"- 完成后应与已验证事实一致：{fact}" for fact in facts)

    lines.extend(["", "## 支付与额度闭环"])
    lines.extend(f"- {fact}" for fact in payment_facts)
    if not payment_facts:
        lines.append("- 未在本轮验证。")

    lines.extend(["", "## Agent 能力验证"])
    lines.extend(f"- {fact}" for fact in agent_facts)
    if not agent_facts:
        lines.append("- 未在本轮验证。")

    lines.extend([
        "",
        "## 风险提示",
        "- 除上述用户明确提供的事实外，其他信息未在本轮验证。",
        "",
        "## 现场演示前检查清单",
    ])
    lines.extend(f"- [ ] {fact}" for fact in facts)

    lines.extend([
        "",
        "## 结论",
        "以上内容均直接来自用户提供的 source-of-truth 事实列表；可按检查清单进行演示，未提供项不作推断。",
    ])
    return "\n".join(lines).strip()
