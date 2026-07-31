#Requires -Version 7.2
param(
    [ValidateRange(60, 600)]
    [int]$TimeoutSeconds = 600,
    [string]$EvidencePath = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $PSScriptRoot "p110-real-deep-mcp-run.json"
}

# A stable public source target keeps the required P90 citation path independent
# from the internal read-only MCP auxiliary lookup.
$query = "深度调研 OpenJDK JEP 444 Virtual Threads 的 Java 21 状态，重点核验 https://openjdk.org/jeps/444，且请调用 project_search_knowledge 作为辅助检索。必须给出至少一个 OpenJDK 或 JEP 的真实 URL，并调用报告工具生成一份短 Markdown 文档。"

& (Join-Path $PSScriptRoot "run-p110-deep-trace.ps1") `
    -TimeoutSeconds $TimeoutSeconds `
    -Query $query `
    -EvidencePath $EvidencePath

exit $LASTEXITCODE
