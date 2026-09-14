# V25-S02/K-05 evidence driver: exit-code / fail-fast matrix for scripts/smoke-pipeline.ps1
#
# What is being proven:
#   1) "target or credential unclear" is refused BEFORE execution (exit 5), and
#      no database client is invoked at all;
#   2) a read-only query that really fails mid-run is NOT downgraded to an empty result:
#      the script must stop immediately with exit 6, print the original client error text
#      and the original client exit code, and print no gate verdict afterwards.
#      (Before the fix, a failed read became 0 rows / 0 rules, so the quality gate reported
#      "all passed" and the script could exit 0. That is the same defect shape as
#      "delete the check to get green", only expressed as "cannot read, so do not judge".)
#
# How failure is injected without touching the real database: tools/mysql-stub.cmd is passed
# as -MysqlExe. It never opens a connection, it records every invocation to a fixed temp log,
# and K04_STUB_MODE=fail / K04_STUB_FAIL_ON make a chosen statement class fail deterministically.
param(
  [string]$OutDir = (Join-Path $PSScriptRoot 'raw'),
  [string[]]$Only = @(),
  [int]$StubPort = 18099
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$stub = Join-Path $PSScriptRoot 'tools\mysql-stub.cmd'
$stubLog = Join-Path $env:TEMP 'k04-stub-invocations.tsv'
$smoke = Join-Path $repo 'scripts\smoke-pipeline.ps1'

# 用例 D 必须跑到"记录基线"那一步（在登录取 token 之后），所以需要一个能应答登录的本地
# 替身 HTTP 服务。它只提供登录/就绪类响应；链路的成功与否在 D 里根本不重要——
# D 的判据是"第一次只读取数失败时是否立刻停"。
$srvScript = Join-Path $PSScriptRoot 'tools\stub-web-server.ps1'
$srvLog = Join-Path $OutDir 'k05-stub-http-server.log'
$srv = Start-Process pwsh -PassThru -WindowStyle Hidden `
  -ArgumentList @('-NoProfile', '-File', $srvScript, '-Port', "$StubPort") `
  -RedirectStandardOutput $srvLog -RedirectStandardError "$srvLog.err"
Start-Sleep -Seconds 2
$baseUrl = "http://127.0.0.1:$StubPort"

$results = New-Object System.Collections.Generic.List[object]
function Run-Case {
  param([string]$Name, [string[]]$ExtraArgs, [hashtable]$Env = @{}, [string[]]$Assertions = @(), [int]$WantExit)
  if ($Only.Count -gt 0 -and $Only -notcontains $Name) { return }
  Remove-Item $stubLog -Force -ErrorAction SilentlyContinue
  $caseOut = Join-Path $OutDir "k05-$Name.txt"
  # 基线参数：BusinessTime 必填；其余走默认（-MetricDb analytics_metric 在白名单内）。
  $allArgs = @('-NoProfile', '-File', $smoke, '-BusinessTime', '2026-09-01T00:00:00',
               '-BaseUrl', $baseUrl, '-MysqlExe', $stub, '-OutDir', $OutDir) + $ExtraArgs
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("### case: $Name")
  $lines.Add("### 期望: 退出码 $WantExit")
  $lines.Add("### command: pwsh " + ($allArgs -join ' '))
  foreach ($k in ($Env.Keys | Sort-Object)) {
    $lines.Add("### env: $k=$(if ($k -match 'PASSWORD|PWD') { '<set>' } else { $Env[$k] })")
  }
  # 清掉脚本会自己读取的所有口令来源，避免本机环境意外"补齐"凭据
  $clear = @('MYSQL_PWD', 'SMOKE_DB_PASSWORD')
  $saved = @{}
  foreach ($k in ($Env.Keys + $clear)) {
    if (-not $saved.ContainsKey($k)) { $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process') }
  }
  foreach ($k in $clear) { [Environment]::SetEnvironmentVariable($k, $null, 'Process') }
  foreach ($k in $Env.Keys) { [Environment]::SetEnvironmentVariable($k, $Env[$k], 'Process') }
  $outText = ''
  try {
    $out = & pwsh @allArgs 2>&1
    $code = $LASTEXITCODE
    $outText = ($out | ForEach-Object { $_.ToString() }) -join "`n"
  } finally {
    foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
  }
  $invocations = if (Test-Path $stubLog) { @(Get-Content $stubLog -Encoding utf8) } else { @() }
  $lines.Add("### exit code: $code")
  $lines.Add("### mysql-stub invocations: $($invocations.Count)")
  foreach ($i in $invocations) { $lines.Add("###   stub-call: $i") }
  $joined = ($invocations -join "`n")
  $verdicts = New-Object System.Collections.Generic.List[string]
  $want = @("exit=$WantExit") + $Assertions
  foreach ($a in $want) {
    if ($a -match '^exit=(\d+)$') {
      $w = [int]$Matches[1]
      $ok = ($code -eq $w)
      $verdicts.Add("$(if ($ok) { 'PASS' } else { 'FAIL' }) 断言 退出码=$w（实测 $code）")
      continue
    }
    if ($a -match '^stubCalls=(\d+)$') {
      $w = [int]$Matches[1]
      $ok = ($invocations.Count -eq $w)
      $verdicts.Add("$(if ($ok) { 'PASS' } else { 'FAIL' }) 断言 stubCalls=$w（实测 $($invocations.Count)）")
      continue
    }
    $inStub = $a.StartsWith('s:') -or $a.StartsWith('!s:')
    $negate = $a.StartsWith('!')
    if ($a.StartsWith('!s:')) { $needle = $a.Substring(3) }
    elseif ($inStub) { $needle = $a.Substring(2) }
    elseif ($negate) { $needle = $a.Substring(1) }
    else { $needle = $a }
    $hay = if ($inStub) { $joined } else { $outText }
    $found = $hay -match [regex]::Escape($needle)
    $ok = if ($negate) { -not $found } else { $found }
    $where = if ($inStub) { 'stub 命令行' } else { '输出' }
    $verb = if ($negate) { '不得含' } else { '含' }
    $verdicts.Add("$(if ($ok) { 'PASS' } else { 'FAIL' }) 断言$where$verb '$needle'")
  }
  foreach ($v in $verdicts) { $lines.Add("### $v") }
  $failed = @($verdicts | Where-Object { $_.StartsWith('FAIL') }).Count
  $lines.Add("### case verdict: $(if ($failed -eq 0) { 'PASS' } else { "FAIL($failed)" })")
  $lines.Add('### ---- raw output ----')
  foreach ($l in $out) { $lines.Add($l.ToString()) }
  $lines | Set-Content $caseOut -Encoding utf8
  $lines | ForEach-Object { Write-Host $_ }
  Write-Host ''
  $results.Add([pscustomobject]@{ case = $Name; exit = $code; stubCalls = $invocations.Count; failed = $failed; file = (Split-Path -Leaf $caseOut) })
}

# A. 未提供任何口令 -> 执行前拒绝（exit 5），且 mysql 客户端零调用
Run-Case -Name 'a-refuse-no-password' -WantExit 5 -Assertions @(
  'stubCalls=0', '未提供数据库口令')

# B. -MetricDb 不在白名单 -> 执行前拒绝（exit 5），零调用
Run-Case -Name 'b-refuse-db-not-in-whitelist' -WantExit 5 -ExtraArgs @(
  '-MetricDb', 'analytics_metric_evil') -Env @{ SMOKE_DB_PASSWORD = 'stub-pwd' } -Assertions @(
  'stubCalls=0', '不在允许清单')

# C. 账号为 root -> 执行前拒绝（exit 5），零调用
Run-Case -Name 'c-refuse-root-account' -WantExit 5 -ExtraArgs @(
  '-MysqlUser', 'root') -Env @{ SMOKE_DB_PASSWORD = 'stub-pwd' } -Assertions @(
  'stubCalls=0', '账号为 root')

# D. 凭据齐备但**只读取数真的失败** -> 必须立即 exit 6，保留原始退出码与原始文本，且不得再打印任何门禁结论
Run-Case -Name 'd-query-fails' -WantExit 6 -ExtraArgs @(
  '-MysqlUser', 'metric_read') -Env @{
    SMOKE_DB_PASSWORD = 'stub-pwd'; K04_STUB_MODE = 'fail'; K04_STUB_EXIT = '7'
    K04_STUB_MSG = 'ERROR 2013 (HY000): Lost connection to MySQL server during query (stub)' } -Assertions @(
  'stubCalls=1',
  '只读取数失败，立即停止',
  'Lost connection to MySQL server during query (stub)',
  'mysql 退出 : 7',
  'SELECT COALESCE(MAX(id),0) FROM analytics_meta.pipeline_run',
  '退出码 6',
  '!阶段状态',
  '!已发布',
  '!BLOCKING',
  '!证据')
$summaryPath = Join-Path $OutDir 'k05-exitcode-matrix.json'
$results | ConvertTo-Json -Depth 4 | Set-Content $summaryPath -Encoding utf8
if ($srv -and -not $srv.HasExited) { Stop-Process -Id $srv.Id -Force -ErrorAction SilentlyContinue }
Write-Host '=== matrix ==='
$results | Format-Table -AutoSize
