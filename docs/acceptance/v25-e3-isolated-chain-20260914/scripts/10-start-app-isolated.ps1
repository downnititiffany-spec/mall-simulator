<#
V25-E3 | 以**启动参数/环境变量覆盖**的方式让 platform-app 指向隔离实例 3307，
         工作目录 = 仓库内 gitignored 的 target/e3-run/<runId>/，绑 127.0.0.1:8091。

不修改任何已入库配置文件（application.yml 原样：仍然写着宿主 3306，靠覆盖生效）。
口令从 target/e3-run/<runId>/credref.properties 读取后放进**子进程环境变量**
（不出现在 java 命令行参数、不落 docs 证据）。

用法：pwsh -File <this> [-RunId <id>]
#>
param(
  [string]$RunId = 'v25it-20260914-1358-l4e3',
  [string]$RepoRoot = 'D:\Develop_code\GraduationProject',
  [string]$JavaExe = 'D:\Develop\JAVA17\bin\java.exe',
  [int]$Port = 8091
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$scratch = Join-Path $RepoRoot "target\e3-run\$RunId"
$credFile = Join-Path $scratch 'credref.properties'
if (-not (Test-Path $credFile)) { throw "缺少凭据文件 $credFile（先跑 00-create-isolation.ps1）" }
$cred = @{}
foreach ($line in Get-Content $credFile) {
  if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
  $kv = $line -split '=', 2
  $cred[$kv[0].Trim()] = $kv[1].Trim()
}
$metaDb = $cred['metaDb']; $metricDb = $cred['metricDb']
$qs = 'useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true'

# ── 落点（全部在仓库内）──────────────────────────────────────────────
$landing  = Join-Path $scratch 'landing'
$events   = Join-Path $landing 'events'
$warehouse= Join-Path $scratch 'spark-warehouse'
$metastore= Join-Path $scratch 'derby-metastore'
New-Item -ItemType Directory -Force -Path $events | Out-Null

# ── 事件夹具（**只读复制**仓库内既有 landing/events 下的小样本，不修改源文件）──
$fixtures = @('gen-s3b-1000-20260911.jsonl', 'golden-r73-clean-20260910.jsonl')
foreach ($f in $fixtures) {
  $src = Join-Path $RepoRoot "landing\events\$f"
  if (-not (Test-Path $src)) { throw "缺夹具 $src" }
  Copy-Item $src (Join-Path $events $f) -Force
}

# ── 覆盖项（等价于 application.yml 里 ${...} 占位符的官方覆盖机制）──
$env:PLATFORM_META_URL   = "jdbc:mysql://127.0.0.1:3307/${metaDb}?$qs"
$env:PLATFORM_META_USER  = $cred['meta.username']
$env:PLATFORM_META_PASSWORD = $cred['meta.password']
$env:PLATFORM_METRIC_PUBLISH_URL  = "jdbc:mysql://127.0.0.1:3307/${metricDb}?$qs"
$env:PLATFORM_METRIC_PUBLISH_USER = $cred['publish.username']
$env:PLATFORM_METRIC_PUBLISH_PASSWORD = $cred['publish.password']
$env:PLATFORM_METRIC_READ_URL  = "jdbc:mysql://127.0.0.1:3307/${metricDb}?$qs"
$env:PLATFORM_METRIC_READ_USER = $cred['read.username']
$env:PLATFORM_METRIC_READ_PASSWORD = $cred['read.password']
$env:PLATFORM_LANDING_LOCAL_ROOT = $landing
$env:PLATFORM_SOURCE_PROFILE_ROOT = $RepoRoot
$env:PLATFORM_SPARK_WAREHOUSE_DIR = $warehouse
$env:PLATFORM_SPARK_METASTORE_DIR = $metastore

# ── 回显（口令一律脱敏）──────────────────────────────────────────────
function Redact($s) { if (-not $s) { return '' } return ($s -replace '(?i)://([^:]+):[^@]*@', '://$1:***@') }
Write-Host '=========== 目标已覆盖为隔离实例 ==========='
Write-Host ("  meta.url          = {0}" -f $env:PLATFORM_META_URL)
Write-Host ("  meta.user         = {0}  password=<redacted:{1} chars>" -f $env:PLATFORM_META_USER, $env:PLATFORM_META_PASSWORD.Length)
Write-Host ("  metric.publish.url= {0}" -f $env:PLATFORM_METRIC_PUBLISH_URL)
Write-Host ("  metric.publish.user={0}" -f $env:PLATFORM_METRIC_PUBLISH_USER)
Write-Host ("  metric.read.url   = {0}" -f $env:PLATFORM_METRIC_READ_URL)
Write-Host ("  metric.read.user  = {0}" -f $env:PLATFORM_METRIC_READ_USER)
Write-Host ("  landing.local-root= {0}" -f $env:PLATFORM_LANDING_LOCAL_ROOT)
Write-Host ("  source.profile-root={0}" -f $env:PLATFORM_SOURCE_PROFILE_ROOT)
Write-Host ("  spark.warehouse   = {0}" -f $env:PLATFORM_SPARK_WAREHOUSE_DIR)
Write-Host ("  spark.metastore   = {0}" -f $env:PLATFORM_SPARK_METASTORE_DIR)
Write-Host ("  工作目录(CWD)      = {0}" -f $scratch)
Write-Host ("  绑定              = 127.0.0.1:{0}" -f $Port)
Write-Host ("  任何 3306 出现即为门禁失败：URL 中只有 3307")

Set-Location $scratch
$jar = Join-Path $RepoRoot 'analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar'
$javaArgs = @(
  '-Dfile.encoding=UTF-8', '-Xmx2g',
  '-jar', $jar,
  "--server.address=127.0.0.1",
  "--server.port=$Port"
)
Write-Host ("  启动命令: {0} {1}" -f $JavaExe, ($javaArgs -join ' '))
& $JavaExe @javaArgs
