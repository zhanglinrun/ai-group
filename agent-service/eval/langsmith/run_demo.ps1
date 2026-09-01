param(
  [string]$BaseUrl = "http://localhost:8090",
  [int]$MaxConcurrency = 2,
  [switch]$WithLlmJudge,
  [switch]$Formal,
  [ValidateSet("full_flow", "direct")][string]$Mode = "full_flow",
  [string]$OutputJson = ""
)

$env:PYTHONPATH = "."
$env:AGENT_EVAL_BASE_URL = $BaseUrl
$arguments = @("-m", "eval.langsmith.run_eval", "--max-concurrency", $MaxConcurrency, "--mode", $Mode)
if ($Formal) { $arguments += "--formal" } else { $arguments += "--smoke" }
if ($WithLlmJudge) { $arguments += "--with-llm-judge" }
if ($OutputJson) { $arguments += @("--output-json", $OutputJson) }
python @arguments
