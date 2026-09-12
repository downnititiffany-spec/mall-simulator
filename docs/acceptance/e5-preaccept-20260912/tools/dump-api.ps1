# 只读接口取证：把 8091 的关键接口原文落到 raw/（GET 为主；POST 仅登录与采集触发由调用方另跑）。
# 用法: pwsh -File docs/acceptance/e5-preaccept-20260912/tools/dump-api.ps1 -Tag before-stop
param(
  [Parameter(Mandatory = $true)][string]$Tag
)
$ErrorActionPreference = 'Stop'
$raw = 'D:\Develop_code\GraduationProject\docs\acceptance\e5-preaccept-20260912\raw'
$b = 'http://127.0.0.1:8091'
$log = @()
$log += "===== 8091 接口取证 tag=$Tag  at $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss')) ====="
$login = Invoke-WebRequest -Method Post -Uri "$b/api/v1/auth/login" -ContentType 'application/json' `
  -Body '{"username":"admin","password":"admin123"}' -UseBasicParsing
$log += "POST /api/v1/auth/login => HTTP $($login.StatusCode)  (body 略：含 token)"
$tok = ($login.Content | ConvertFrom-Json).data.token
$h = @{ Authorization = "Bearer $tok" }
$eps = @(
  @{ n = 'health'; u = '/api/v1/health' },
  @{ n = 'dashboards-overview'; u = '/api/v1/dashboards/overview' },
  @{ n = 'metrics-overview'; u = '/api/v1/metrics/overview' },
  @{ n = 'metrics-snapshots'; u = '/api/v1/metrics/snapshots?limit=15' },
  @{ n = 'metrics-quality'; u = '/api/v1/metrics/quality?limit=20' },
  @{ n = 'pipeline-runs'; u = '/api/v1/pipeline-runs?limit=20' },
  @{ n = 'pipeline-run-46'; u = '/api/v1/pipeline-runs/46' },
  @{ n = 'pipeline-run-45'; u = '/api/v1/pipeline-runs/45' },
  @{ n = 'ingestion-status'; u = '/api/v1/ingestion/status' },
  @{ n = 'ingestion-batches'; u = '/api/v1/ingestion/batches?limit=5' }
)
foreach ($e in $eps) {
  $file = Join-Path $raw "api-$Tag-$($e.n).json"
  try {
    $r = Invoke-WebRequest -Uri "$b$($e.u)" -Headers $h -UseBasicParsing -TimeoutSec 30
    $body = $r.Content
    $head = "# GET $b$($e.u)`n# at $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))  HTTP $($r.StatusCode)  bytes=$($body.Length)`n"
    ($head + $body) | Set-Content -LiteralPath $file -Encoding utf8NoBOM
    $log += "GET $($e.u) => HTTP $($r.StatusCode) bytes=$($body.Length) -> $(Split-Path -Leaf $file)"
  } catch {
    $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'n/a' }
    $head = "# GET $b$($e.u)`n# at $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))  HTTP $code  ERROR`n"
    ($head + $_.Exception.Message) | Set-Content -LiteralPath $file -Encoding utf8NoBOM
    $log += "GET $($e.u) => ERROR HTTP $code $($_.Exception.Message) -> $(Split-Path -Leaf $file)"
  }
}
$log -join "`n" | Set-Content -LiteralPath (Join-Path $raw "api-$Tag-index.txt") -Encoding utf8NoBOM
$log -join "`n"
