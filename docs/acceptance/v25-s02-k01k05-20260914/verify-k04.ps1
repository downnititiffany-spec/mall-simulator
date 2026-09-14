# V25-S02/K-04 取证驱动：`scripts/run-demo.ps1 -Clean` 清场门禁的实测矩阵
#
# 做什么：把 run-demo.ps1 的**清场分支**单独逼出来跑，逐条记录
#   命令行 → stdout → 退出码 → **MySQL 客户端被调用了几次、参数是什么**。
#
# 为什么这样测：清场是删除路径，判据不是"脚本说拒绝了"，而是
#   「门禁没过时数据库客户端**一次都没被调用**」——这才是"不会误删"的证据。
#   因此用 tools/mysql-stub.cmd 作为客户端替身：它把每次调用记进文件，且**不连任何库**。
#   用真 mysql.exe 做负向测试本身不安全：门禁一旦失效就会真的删宿主 3306 上的数据。
#
# 前置：三段健康检查必须过，否则脚本在 [0] 就 exit 1、根本走不到清场。
#   本驱动因此起一个**本地替身 HTTP 服务**（不碰 8090/8091/8092 真程序）。
#   ⚠️ 替身返回固定假数据 ⇒ 只有两项是有效判据：
#     (1) [0.5] 的拒绝/放行结论；(2) stub 调用次数与参数。
#   脚本后半段打印的阶段数/行数/合计不具证据力。
param(
  [string]$OutDir = (Join-Path $PSScriptRoot 'raw'),
  [int]$StubPort = 18099
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$stub = Join-Path $PSScriptRoot 'tools\mysql-stub.cmd'
$stubLog = Join-Path $env:TEMP 'k04-stub-invocations.tsv'

$RUNID = 'v25it-20260914-1400-k04'
$ISODB = "${RUNID}_mall"
$ISOUSER = "${RUNID}_mallapp"
$UUID = 'de8ebbea-aff4-11f1-8037-00155d5dba47'

# ── 本地替身 HTTP 服务（只为让 [0]/[0.1] 通过）───────────────────────────
$srvScript = Join-Path $PSScriptRoot 'tools\stub-web-server.ps1'
$srvLog = Join-Path $OutDir 'k04-stub-http-server.log'
$srv = Start-Process pwsh -PassThru -WindowStyle Hidden `
  -ArgumentList @('-NoProfile', '-File', $srvScript, '-Port', "$StubPort") `
  -RedirectStandardOutput $srvLog -RedirectStandardError "$srvLog.err"
Start-Sleep -Seconds 2

$results = New-Object System.Collections.Generic.List[object]
function Run-Case {
  param([string]$Name, [string]$Expect, [string[]]$ExtraArgs, [hashtable]$Env = @{}, [string[]]$Assertions = @())
  # 每次清空调用记录：只关心"这一条用例里客户端被调用了几次"
  Remove-Item $stubLog -Force -ErrorAction SilentlyContinue
  $caseOut = Join-Path $OutDir "k04-$Name.txt"
  $allArgs = @('-NoProfile', '-File', (Join-Path $repo 'scripts\run-demo.ps1')) +
             @('-Mall', "http://127.0.0.1:$StubPort", '-Platform', "http://127.0.0.1:$StubPort",
               '-Generator', "http://127.0.0.1:$StubPort", '-MysqlExe', $stub) + $ExtraArgs
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("### case: $Name")
  $lines.Add("### 期望: $Expect")
  $lines.Add("### command: pwsh " + ($allArgs -join ' '))
  $outText = ''
  foreach ($k in ($Env.Keys | Sort-Object)) {
    $lines.Add("### env: $k=$(if ($k -match 'PASSWORD|PWD') { '<set>' } else { $Env[$k] })")
  }
  $saved = @{}
  foreach ($k in $Env.Keys) {
    $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process')
    [Environment]::SetEnvironmentVariable($k, $Env[$k], 'Process')
  }
  try {
    $out = & pwsh @allArgs 2>&1
    $code = $LASTEXITCODE
    $outText = ($out | ForEach-Object { $_.ToString() }) -join "`n"
  } finally {
    foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
  }
  $lines.Add("### exit code: $code")
  $invocations = if (Test-Path $stubLog) { @(Get-Content $stubLog -Encoding utf8) } else { @() }
  $lines.Add("### mysql-stub invocations: $($invocations.Count)")
  foreach ($i in $invocations) { $lines.Add("###   stub-call: $i") }
  # 判据自检：把"期望"变成可机检的断言，避免只靠人眼看输出。
  #   语法：stubCalls=<n>          断言客户端调用次数
  #         <文本>                  断言 stdout 中出现
  #         !<文本>                 断言 stdout 中不出现
  #         s:<文本>                断言 stub 记录的命令行中出现
  #         !s:<文本>               断言 stub 记录的命令行中不出现
  $joined = ($invocations -join "`n")
  $verdicts = New-Object System.Collections.Generic.List[string]
  foreach ($a in $Assertions) {
    if ($a -match '^stubCalls=(\d+)$') {
      $want = [int]$Matches[1]
      $ok = ($invocations.Count -eq $want)
      $verdicts.Add("$(if ($ok) { 'PASS' } else { 'FAIL' }) 断言 stubCalls=$want（实测 $($invocations.Count)）")
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
  $out | ForEach-Object { $lines.Add($_.ToString()) }
  $lines | Set-Content $caseOut -Encoding utf8
  $lines | ForEach-Object { Write-Host $_ }
  Write-Host ''
  $results.Add([pscustomobject]@{
      case = $Name; exit = $code; stubCalls = $invocations.Count
      failedAssertions = $failed; file = (Split-Path -Leaf $caseOut)
    })
}

try {
  # A. 目标实例是宿主 3306 -> 必须在**执行任何语句之前**拒绝（stub 调用数应为 0）
  Run-Case -Name 'a-refuse-host3306' -Expect '拒绝，stubCalls=0' -ExtraArgs @(
    '-Clean', '-ConfirmCleanTarget', '-MysqlPort', '3306',
    '-MallDbName', 'mall_simulator', '-MallDbUser', 'mall_app') -Env @{ MALL_DB_PASSWORD = 'stub-pwd' } `
    -Assertions @('stubCalls=0', '拒绝清场：端口 3306')

  # B. 库名不带 runId 前缀 -> 拒绝（stub 调用数应为 0）
  Run-Case -Name 'b-refuse-no-runid-prefix' -Expect '拒绝，stubCalls=0' -ExtraArgs @(
    '-Clean', '-ConfirmCleanTarget', '-MysqlPort', '3307', '-RunId', $RUNID,
    '-MallDbName', 'mall_simulator', '-MallDbUser', $ISOUSER) -Env @{ MALL_DB_PASSWORD = 'stub-pwd' } `
    -Assertions @('stubCalls=0', '未带 runId 前缀')

  # C. 缺口令 -> 跳过清场（stub 调用数应为 0）
  Run-Case -Name 'c-skip-no-password' -Expect '跳过，stubCalls=0' -ExtraArgs @(
    '-Clean', '-ConfirmCleanTarget', '-MysqlPort', '3307', '-RunId', $RUNID,
    '-MallDbName', $ISODB, '-MallDbUser', $ISOUSER) `
    -Assertions @('stubCalls=0', '跳过清场：未提供')

  # D. 未显式扩展库名白名单 -> 仍拒绝（R-6 原白名单只有 mall_simulator）
  Run-Case -Name 'd-refuse-db-not-in-whitelist' -Expect '拒绝（白名单），stubCalls=0' -ExtraArgs @(
    '-Clean', '-ConfirmCleanTarget', '-MysqlPort', '3307', '-RunId', $RUNID,
    '-MallDbName', $ISODB, '-MallDbUser', $ISOUSER) -Env @{ MALL_DB_PASSWORD = 'stub-pwd' } `
    -Assertions @('stubCalls=0', '不在商城自有库白名单')

  # E. 显式扩展白名单 + 指纹不符（端口对、uuid 不对）-> 拒绝；只应发生 1 次只读探针，不得有 DML
  Run-Case -Name 'e-refuse-fingerprint-mismatch' -Expect '拒绝（指纹）；stubCalls=1 且仅指纹探针' -ExtraArgs @(
    '-Clean', '-ConfirmCleanTarget', '-MysqlPort', '3307', '-RunId', $RUNID,
    '-MallDbName', $ISODB, '-MallDbUser', $ISOUSER, '-AllowedCleanDbs', $ISODB,
    '-ExpectedServerUuid', '00000000-0000-0000-0000-000000000000') -Env @{
      MALL_DB_PASSWORD = 'stub-pwd'; K04_STUB_ROWS = "3307|$UUID|/data/mysql-isolated/data/|dahaishui" } `
    -Assertions @('stubCalls=1', 's:CONCAT(@@port', '!s:DELETE', '拒绝清场：实例指纹不符')

  # F. 指纹通过、预览通过，但**删除语句失败** -> 必须如实报告失败、不得声称已清场
  Run-Case -Name 'f-delete-fails' -Expect '放行到 DML；删除失败被如实报出，不得称"已清场"' -ExtraArgs @(
    '-Clean', '-ConfirmCleanTarget', '-MysqlPort', '3307', '-RunId', $RUNID,
    '-MallDbName', $ISODB, '-MallDbUser', $ISOUSER, '-AllowedCleanDbs', $ISODB,
    '-ExpectedServerUuid', $UUID) -Env @{
      MALL_DB_PASSWORD = 'stub-pwd'; K04_STUB_ROWS = "3307|$UUID|/data/mysql-isolated/data/|dahaishui"
      K04_STUB_PREVIEW_ROWS = '7'; K04_STUB_FAIL_ON = 'DELETE' } `
    -Assertions @('stubCalls=3', 's:DELETE', 's:--port 3307', '清 event_outbox **失败**', '!已清商城 event_outbox')

  # G. 全绿路径：指纹通过 + 预览 7 行 + 删除成功 -> 三道门禁全过，3 次调用且 DML 带 --port=3307
  Run-Case -Name 'g-happy-isolated' -Expect '放行；stubCalls=3（指纹/预览/删除），DML 含 --port=3307' -ExtraArgs @(
    '-Clean', '-ConfirmCleanTarget', '-MysqlPort', '3307', '-RunId', $RUNID,
    '-MallDbName', $ISODB, '-MallDbUser', $ISOUSER, '-AllowedCleanDbs', $ISODB,
    '-ExpectedServerUuid', $UUID) -Env @{
      MALL_DB_PASSWORD = 'stub-pwd'; K04_STUB_ROWS = "3307|$UUID|/data/mysql-isolated/data/|dahaishui"
      K04_STUB_PREVIEW_ROWS = '7'; K04_STUB_DELETE_ROWS = '7' } `
    -Assertions @('stubCalls=3', 's:DELETE', 's:--port 3307', '已清商城 event_outbox：删除 7 行')
} finally {
  if ($srv -and -not $srv.HasExited) { Stop-Process -Id $srv.Id -Force -ErrorAction SilentlyContinue }
}
$summaryPath = Join-Path $OutDir 'k04-gate-matrix.json'
$results | ConvertTo-Json -Depth 4 | Set-Content $summaryPath -Encoding utf8
Write-Host '=== matrix ==='
$results | Format-Table -AutoSize
