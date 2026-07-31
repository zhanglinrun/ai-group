#Requires -Version 7.0
<#
Runs the P120 Java evaluation gates without exposing bearer tokens.

- Default (PR): deterministic offline fixtures and all `ai-agent-eval` unit tests.
- `-Nightly`: requires the Gateway URL and bearer token environment variables, runs three real SSE trials.

The Java CLI writes result.json, report.md, report.html, and regression-set.jsonl. A non-zero exit code
is a gate failure, and the failed cases are retained in the generated regression set for follow-up.
#>
param(
    [switch]$Nightly,
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$agentPom = Join-Path $root "ai-agent/pom.xml"
$maven = @("-f", $agentPom, "-pl", "ai-agent-eval", "-am")

& mvn @maven test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($Nightly) {
    if ([string]::IsNullOrWhiteSpace($env:RESEARCHPILOT_EVAL_GATEWAY_URL)) {
        throw "RESEARCHPILOT_EVAL_GATEWAY_URL is required for -Nightly"
    }
    if ([string]::IsNullOrWhiteSpace($env:RESEARCHPILOT_EVAL_BEARER_TOKEN)) {
        throw "RESEARCHPILOT_EVAL_BEARER_TOKEN is required for -Nightly"
    }
    $target = if ($OutputDirectory) { $OutputDirectory } else { "docs/evals/reports/p120-nightly" }
    $arguments = "--gateway-url $($env:RESEARCHPILOT_EVAL_GATEWAY_URL) --trials 3 --case-ids standard-arithmetic --output $target"
    if (-not [string]::IsNullOrWhiteSpace($env:RESEARCHPILOT_EVAL_SAA_ELASTICSEARCH_URL)) {
        $arguments += " --saa-elasticsearch-url $($env:RESEARCHPILOT_EVAL_SAA_ELASTICSEARCH_URL)"
    }
} else {
    $target = if ($OutputDirectory) { $OutputDirectory } else { "docs/evals/reports/p120-pr" }
    $arguments = "--output $target"
}

& mvn @maven "exec:java" "-Dexec.mainClass=com.linrun.agent.eval.EvalCli" "-Dexec.args=$arguments"
exit $LASTEXITCODE
