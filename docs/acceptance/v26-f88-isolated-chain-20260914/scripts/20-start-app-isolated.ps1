<#
V26-F88 | 以**启动期环境变量覆盖**方式让 platform-app 指向隔离实例 3307，绑 127.0.0.1:8091。

【本泳道相对 E3 10-start-app-isolated.ps1 的强制增量 —— 铁律 4「起 8091 之前必须自检」】
  在 `& java` **之前**执行 GATE：
    G1  application.yml 里三条 URL 必须确实是 ${PLATFORM_*_URL:...} 占位符（证明 env 覆盖是官方生效位）。
    G2  将要注入的三条 URL：host 必须 = 127.0.0.1、port 必须 = 3307、库名必须含本次 runId token。
    G3  三条 URL 任一出现 `:3306` / `3306/` ⇒ 立即拒绝。
    G4  自检通过前/后都不执行任何 SQL 写；自检失败 exit 9 且**不启动** java。
  任一条不满足 ⇒ 打印失败项、exit 9、绝不启动。

用法：pwsh -NoProfile -File <this> -RunId <id> [-Port 8091]
#>
param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [string]$RepoRoot = 'D:\Develop_code\GraduationProject',
  [string]$JavaExe = 'D:\Develop\JAVA17\bin\java.exe',
  [int]$Port = 8091,
  [string]$ExpectDbHost = '127.0.0.1',
  [int]$ExpectDbPort = 3307
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$scratch  = Join-Path $RepoRoot "target\f88-run\$RunId"
$credFile = Join-Path $scratch 'credref.properties'
if (-not (Test-Path $credFile)) { throw "缺少凭据文件 $credFile（先跑 10-create-isolation.ps1）" }
$cred = @{}
foreach ($line in Get-Content $credFile) {
  if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
  $kv = $line -split '=', 2
  $cred[$kv[0].Trim()] = $kv[1].Trim()
}
$token    = $RunId -replace '-', '_'
$metaDb   = $cred['metaDb']; $metricDb = $cred['metricDb']
$qs = 'useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true'

$landing   = Join-Path $scratch 'landing'
$events    = Join-Path $landing 'events'
$warehouse = Join-Path $scratch 'spark-warehouse'
$metastore = Join-Path $scratch 'derby-metastore'
New-Item -ItemType Directory -Force -Path $events | Out-Null

$fixtures = @('gen-s3b-1000-20260911.jsonl', 'golden-r73-clean-20260910.jsonl')
foreach ($f in $fixtures) {
  $src = Join-Path $RepoRoot "landing\events\$f"
  if (-not (Test-Path $src)) { throw "缺夹具 $src" }
  Copy-Item $src (Join-Path $events $f) -Force
}

$metaUrl   = "jdbc:mysql://${ExpectDbHost}:${ExpectDbPort}/${metaDb}?$qs"
$pubUrl    = "jdbc:mysql://${ExpectDbHost}:${ExpectDbPort}/${metricDb}?$qs"
$readUrl   = "jdbc:mysql://${ExpectDbHost}:${ExpectDbPort}/${metricDb}?$qs"

# ══════════════════════════════════════════════════════════════════════
#  GATE —— 启动前自检（铁律 4）。任何一条不过：不启动。
# ══════════════════════════════════════════════════════════════════════
$fail = New-Object System.Collections.Generic.List[string]

# G1：application.yml 必须是 env 占位符（否则我们的覆盖不是「官方生效位」）
$yml = Join-Path $RepoRoot 'analytics-server\platform-app\src\main\resources\application.yml'
if (-not (Test-Path $yml)) { $fail.Add("G1 application.yml 不存在: $yml") }
else {
  $ymlText = Get-Content $yml -Raw
  foreach ($ph in @('${PLATFORM_META_URL:', '${PLATFORM_METRIC_PUBLISH_URL:', '${PLATFORM_METRIC_READ_URL:')) {
    if ($ymlText -notlike "*$ph*") { $fail.Add("G1 application.yml 缺占位符 $ph") }
  }
  $ymlDefault3306 = ([regex]::Matches($ymlText, ':jdbc:mysql://127\.0\.0\.1:3306/')).Count
  Write-Host "[GATE-G1] application.yml 占位符检查：meta/publish/read 三处 env 覆盖位 + 默认值指向 3306 的处数 = $ymlDefault3306"
}

# G2/G3：注入的三条 URL 逐条断言
$checks = @(
  @{ Name = 'meta';   Url = $metaUrl; Db = $metaDb },
  @{ Name = 'pub';    Url = $pubUrl;  Db = $metricDb },
  @{ Name = 'read';   Url = $readUrl; Db = $metricDb }
)
foreach ($c in $checks) {
  if ($c.Url -notmatch "^jdbc:mysql://$([regex]::Escape($ExpectDbHost)):$ExpectDbPort/") {
    $fail.Add("G2 $($c.Name) URL 的 host/port 不是 $ExpectDbHost`:$ExpectDbPort ⇒ $($c.Url)")
  }
  if ($c.Url -notlike "*/$($c.Db)?*") { $fail.Add("G2 $($c.Name) URL 的库名不是 $($c.Db) ⇒ $($c.Url)") }
  if ($c.Db -notlike "*$token*") { $fail.Add("G2 $($c.Name) 库名不含本 runId token '$token' ⇒ $($c.Db)") }
  if ($c.Url -match ':3306[/?]') { $fail.Add("G3 $($c.Name) URL 出现 3306 ⇒ $($c.Url)") }
}
# G3b：landing/warehouse 等落点也必须在本 run 的 scratch 内
foreach ($p in @($landing, $warehouse, $metastore)) {
  if ($p -notlike "$scratch*") { $fail.Add("G3b 落点不在本次 scratch 内: $p") }
}

Write-Host '=========== 启动前自检（GATE）==========='
Write-Host ("  meta.url           = {0}" -f $metaUrl)
Write-Host ("  metric.publish.url = {0}" -f $pubUrl)
Write-Host ("  metric.read.url    = {0}" -f $readUrl)
Write-Host ("  landing.local-root = {0}" -f $landing)
Write-Host ("  spark.warehouse    = {0}" -f $warehouse)
Write-Host ("  工作目录(CWD)       = {0}" -f $scratch)
Write-Host ("  绑定               = 127.0.0.1:{0}" -f $Port)
if ($fail.Count -gt 0) {
  $fail | ForEach-Object { Write-Host "  [GATE-FAIL] $_" }
  Write-Host '[GATE] 自检未通过 ⇒ 拒绝启动（exit 9）。'
  exit 9
}
Write-Host '[GATE] 自检通过：host=127.0.0.1 / port=3307 / 库名含本次 runId / 无 3306。'
Write-Host '[GATE] 生效机制：application.yml 用 ${PLATFORM_*_URL:...} 占位符，下列 env 覆盖之（Spring relaxed binding）。'

$env:PLATFORM_META_URL = $metaUrl
$env:PLATFORM_META_USER = $cred['meta.username']
$env:PLATFORM_META_PASSWORD = $cred['meta.password']
$env:PLATFORM_METRIC_PUBLISH_URL = $pubUrl
$env:PLATFORM_METRIC_PUBLISH_USER = $cred['publish.username']
$env:PLATFORM_METRIC_PUBLISH_PASSWORD = $cred['publish.password']
$env:PLATFORM_METRIC_READ_URL = $readUrl
$env:PLATFORM_METRIC_READ_USER = $cred['read.username']
$env:PLATFORM_METRIC_READ_PASSWORD = $cred['read.password']
$env:PLATFORM_LANDING_LOCAL_ROOT = $landing
$env:PLATFORM_SOURCE_PROFILE_ROOT = $RepoRoot
$env:PLATFORM_SPARK_WAREHOUSE_DIR = $warehouse
$env:PLATFORM_SPARK_METASTORE_DIR = $metastore

Write-Host ("  meta.user          = {0}  password=<redacted:{1} chars>" -f $env:PLATFORM_META_USER, $env:PLATFORM_META_PASSWORD.Length)
Write-Host ("  metric.publish.user= {0}" -f $env:PLATFORM_METRIC_PUBLISH_USER)
Write-Host ("  metric.read.user   = {0}" -f $env:PLATFORM_METRIC_READ_USER)

Set-Location $scratch
$jar = Join-Path $RepoRoot 'analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar'
$javaArgs = @('-Dfile.encoding=UTF-8', '-Xmx2g', '-jar', $jar, '--server.address=127.0.0.1', "--server.port=$Port")
Write-Host ("  启动命令: {0} {1}" -f $JavaExe, ($javaArgs -join ' '))
Write-Host ("  PID(本进程 shell 内 java 的父) = {0}" -f $PID)
& $JavaExe @javaArgs
