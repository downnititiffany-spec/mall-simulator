# M1-8 三程序独立启停验收（V2.1 §3.4-1「独立构建产物、配置、端口、日志、启动命令」+ §3.4-5「停止任一不影响其余」）
# 用法：
#   pwsh -File scripts/accept-three-programs.ps1                 # 独立构建 + 隔离启停 + 联合启停 + 无数据源可读
#   pwsh -File scripts/accept-three-programs.ps1 -SkipBuild      # 跳过独立构建（只做启停与可读性）
# 产出：docs/acceptance/m1-8-<时间戳>.md（人读报告）+ 同名 .json（原始观测）
param(
  [switch]$SkipBuild,
  [string]$OutDir = 'docs\acceptance',
  [string]$MallDbPassword = '123456'
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$env:MALL_DB_PASSWORD = $MallDbPassword
$logDir = Join-Path $root '.verify'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$obs = New-Object System.Collections.Generic.List[object]
function Note([string]$Step, [string]$What, $Value) {
  $obs.Add([pscustomobject]@{ step = $Step; what = $What; value = "$Value" }) | Out-Null
  Write-Host ("  [{0}] {1} = {2}" -f $Step, $What, $Value)
}

$progs = @(
  [pscustomobject]@{
    Name = 'analytics-platform'; JarGlob = 'analytics-server\platform-app\target\*.jar'; JarName = 'platform-app-0.1.0-SNAPSHOT.jar'; Port = 8091
    Health = 'http://127.0.0.1:8091/api/v1/metrics/health'
    Yml = 'analytics-server\platform-app\src\main\resources\application.yml'
    Log = '.verify\start-platform.log'
    Extra = @("-Dplatform.metric.publish.export-dir=$root\metric-staging")
    Build = @('mvn', '-o', '-q', '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am', 'clean', 'package', '-DskipTests')
    CmdText = 'java -Dfile.encoding=UTF-8 -jar analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar -Dplatform.metric.publish.export-dir=<root>/metric-staging'
  }
  [pscustomobject]@{
    Name = 'reference-mall'; JarGlob = 'mall-simulator\target\*.jar'; JarName = 'mall-simulator-0.1.0-SNAPSHOT.jar'; Port = 8090
    Health = 'http://127.0.0.1:8090/'
    Yml = 'mall-simulator\src\main\resources\application.yml'
    Log = '.verify\start-mall.log'
    Extra = @()
    Build = @('mvn', '-o', '-q', '-f', 'mall-simulator/pom.xml', 'clean', 'package', '-DskipTests')
    CmdText = 'java -Dfile.encoding=UTF-8 -jar mall-simulator/target/mall-simulator-0.1.0-SNAPSHOT.jar'
  }
  [pscustomobject]@{
    Name = 'synthetic-data-generator'; JarGlob = 'synthetic-data-generator\target\*.jar'; JarName = 'synthetic-data-generator-0.1.0-SNAPSHOT.jar'; Port = 8092
    Health = 'http://127.0.0.1:8092/api/v1/scenarios'
    Yml = 'synthetic-data-generator\src\main\resources\application.yml'
    Log = '.verify\start-generator.log'
    Extra = @()
    Build = @('mvn', '-o', '-q', '-f', 'synthetic-data-generator/pom.xml', 'clean', 'package', '-DskipTests')
    CmdText = 'java -Dfile.encoding=UTF-8 -jar synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar  (CLI 亦可：java -jar <jar> generate ...)'
  }
)

function Get-Jar($p) {
  Get-ChildItem (Join-Path $root $p.JarGlob) -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*original*' -and $_.Name -notlike '*sources*' } | Select-Object -First 1
}
function Get-Proc($p) {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like ('*' + $p.JarName + '*') }
}
function Stop-ByPort([int]$Port) {
  # 兜底：按端口占用者杀（命令行匹配失败时仍能停干净；$pid 是 PowerShell 保留变量，改用 $owner）
  foreach ($l in (netstat -ano | Select-String (':' + $Port + '\s+.*LISTENING'))) {
    $owner = ($l.Line -split '\s+')[-1]
    if ($owner -match '^\d+$') { Stop-Process -Id ([int]$owner) -Force -ErrorAction SilentlyContinue }
  }
}
function PortOpen([int]$Port) {
  $c = New-Object System.Net.Sockets.TcpClient
  try { $t = $c.ConnectAsync('127.0.0.1', $Port); return $t.Wait(700) -and $c.Connected } catch { return $false } finally { $c.Dispose() }
}
function HttpCode([string]$Url) {
  try { $r = Invoke-WebRequest -Uri $Url -TimeoutSec 5 -UseBasicParsing; return [int]$r.StatusCode } catch {
    if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return -1 }
  }
}
function Wait-Up($p, [int]$Tries = 40) {
  foreach ($i in 1..$Tries) { Start-Sleep -Milliseconds 800; if ((HttpCode $p.Health) -ge 200 -and (HttpCode $p.Health) -lt 500) { return $true } }
  return $false
}
function Start-Prog($p) {
  $jar = Get-Jar $p
  if (-not $jar) { throw "未找到 $($p.Name) 的 jar：$($p.JarGlob)（先跑独立构建）" }
  $log = Join-Path $root $p.Log
  Start-Process -FilePath 'java' -ArgumentList (@('-Dfile.encoding=UTF-8', '-jar', $jar.FullName) + $p.Extra) `
    -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err" | Out-Null
  return (Wait-Up $p)
}
function Stop-Prog($p) {
  Get-Proc $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  foreach ($i in 1..15) { Start-Sleep -Milliseconds 400; if (-not (PortOpen $p.Port)) { return $true } }
  Stop-ByPort $p.Port
  foreach ($i in 1..15) { Start-Sleep -Milliseconds 400; if (-not (PortOpen $p.Port)) { return $true } }
  return $false
}
function Stop-All { foreach ($p in $progs) { Stop-Prog $p | Out-Null } ; Start-Sleep -Seconds 1 }

Write-Host "=== M1-8 三程序独立启停验收（$stamp） ==="

# ── 0. 前置：清场
Stop-All

# ── 1. 独立配置（各程序自己的 yml：端口 / 库 / 日志）
Write-Host '[1] 独立配置（端口 / 数据库 / 日志文件）'
foreach ($p in $progs) {
  $yml = Get-Content (Join-Path $root $p.Yml) -Raw
  $port = [regex]::Match($yml, '(?m)^\s*port:\s*(\d+)').Groups[1].Value
  $jdbc = [regex]::Match($yml, 'jdbc:mysql://[^/\s]+/([A-Za-z0-9_]+)').Groups[1].Value
  $logFile = [regex]::Match($yml, '(?s)logging:.*?file:\s*(?:name:\s*)?(\S+)').Groups[1].Value
  if (-not $logFile) { $logFile = "$($p.Log)（启动命令 stdout/stderr 重定向；该程序未配 logging.file.name）" }
  Note 'config' "$($p.Name).port" "$port (期望 $($p.Port)) => $(if ($port -eq "$($p.Port)") { 'OK' } else { 'MISMATCH' })"
  Note 'config' "$($p.Name).db" $jdbc
  Note 'config' "$($p.Name).log" $logFile
  Note 'config' "$($p.Name).start" $p.CmdText
}
$ports = $progs | ForEach-Object { $_.Port }
Note 'config' 'port-unique' "$($ports -join ',') => $(if (($ports | Select-Object -Unique).Count -eq 3) { 'OK(三个互不相同)' } else { 'MISMATCH' })"

# ── 2. 独立构建（每个程序只用自己的构建命令；同一时间只有一个 Maven）
if (-not $SkipBuild) {
  Write-Host '[2] 独立构建（逐程序，串行）'
  foreach ($p in $progs) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Push-Location $root
    & $p.Build[0] $p.Build[1..($p.Build.Count - 1)] 2>&1 | Out-Null
    $code = $LASTEXITCODE
    Pop-Location
    $sw.Stop()
    $jar = Get-Jar $p
    Note 'build' "$($p.Name).exit" $code
    Note 'build' "$($p.Name).seconds" ([math]::Round($sw.Elapsed.TotalSeconds, 1))
    Note 'build' "$($p.Name).jar" "$(if ($jar) { "$($jar.Name) $([math]::Round($jar.Length/1MB,2)) MB $($jar.LastWriteTime.ToString('HH:mm:ss'))" } else { '缺失' })"
  }
} else {
  Write-Host '[2] 独立构建：跳过（-SkipBuild）'
}

# ── 3. 单程序隔离启动：只起一个，其余端口必须关闭
Write-Host '[3] 单程序隔离启动（起一个 → 另两个端口必须关闭）'
foreach ($p in $progs) {
  Stop-All
  $up = Start-Prog $p
  Note 'isolation' "$($p.Name).up" "$up (HTTP $(HttpCode $p.Health))"
  foreach ($o in ($progs | Where-Object { $_.Name -ne $p.Name })) {
    Note 'isolation' "$($p.Name)-only: $($o.Name):$($o.Port) open" (PortOpen $o.Port)
  }
  $stopped = Stop-Prog $p
  Note 'isolation' "$($p.Name).port-closed-after-stop" "$stopped ($(-not (PortOpen $p.Port)))"
}

# ── 4. 三程序联合启停：停任一，其余两个必须仍可用
Write-Host '[4] 三程序联合启停（停 X → 另两个仍可用 → 重启 X）'
Stop-All
foreach ($p in $progs) { Note 'joint' "$($p.Name).start" (Start-Prog $p) }
foreach ($p in $progs) {
  $down = Stop-Prog $p
  Note 'joint' "stop($($p.Name))" "$down"
  foreach ($o in ($progs | Where-Object { $_.Name -ne $p.Name })) {
    Note 'joint' "after stop($($p.Name)): $($o.Name) HTTP" (HttpCode $o.Health)
  }
  Note 'joint' "restart($($p.Name))" (Start-Prog $p)
}

# ── 5. 无数据源：停商城 + 停生成器，平台必须仍可启停/登录/读历史，并对采集给出明确答复
Write-Host '[5] 无数据源时的平台行为（停商城 + 停生成器）'
$gateFail = $false
Stop-Prog ($progs | Where-Object Name -eq 'reference-mall') | Out-Null
Stop-Prog ($progs | Where-Object Name -eq 'synthetic-data-generator') | Out-Null
$plat = $progs | Where-Object Name -eq 'analytics-platform'
Note 'nodatasource' 'platform.health' (HttpCode $plat.Health)
$token = $null
try {
  $login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8091/api/v1/auth/login' -ContentType 'application/json' `
    -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 8
  $token = $login.data.token
  Note 'nodatasource' 'login' "OK (token 长度 $(($token | Measure-Object -Character).Characters))"
} catch { Note 'nodatasource' 'login' "FAILED: $($_.Exception.Message)" }
$hdr = @{ Authorization = "Bearer $token" }
foreach ($probe in @(
    @{ n = 'ingestion/status'; u = 'http://127.0.0.1:8091/api/v1/ingestion/status' },
    @{ n = 'ingestion/batches?limit=3'; u = 'http://127.0.0.1:8091/api/v1/ingestion/batches?limit=3' },
    @{ n = 'pipeline-runs?limit=3'; u = 'http://127.0.0.1:8091/api/v1/pipeline-runs?limit=3' },
    @{ n = 'runtime-profiles/active'; u = 'http://127.0.0.1:8091/api/v1/runtime-profiles/active' },
    @{ n = 'metrics/snapshots?limit=5'; u = 'http://127.0.0.1:8091/api/v1/metrics/snapshots?limit=5' },
    @{ n = 'metrics/overview（历史 ACTIVE 快照指标）'; u = 'http://127.0.0.1:8091/api/v1/metrics/overview' },
    @{ n = 'metrics/quality?limit=3'; u = 'http://127.0.0.1:8091/api/v1/metrics/quality?limit=3' })) {
  try {
    $r = Invoke-RestMethod -Method Get -Uri $probe.u -Headers $hdr -TimeoutSec 8
    $body = ($r | ConvertTo-Json -Depth 4 -Compress)
    if ($body.Length -gt 400) { $body = $body.Substring(0, 400) + '…' }
    Note 'nodatasource' $probe.n "HTTP 200 $body"
  } catch {
    $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { -1 }
    Note 'nodatasource' $probe.n "HTTP $code FAILED: $($_.Exception.Message)"
  }
}
# B-08 夹具：往 landing/events 放一个**当前可采集**的小文件（验收夹具，不是生产者产出——
# 商城与生成器此刻都已停机）。这样"第一次采集有新数据 / 第二次采集无新数据"的对照不依赖
# landing 目录里恰好剩着什么，脚本可重复执行（DEF-12 的教训：残行会让残留状态不可信）。
$eventsDir = Join-Path $root 'landing\events'
$fixtureOk = $false
try {
  New-Item -ItemType Directory -Force -Path $eventsDir | Out-Null
  $fixtureName = "b08-gate-$stamp.jsonl"
  $fx = @(
    '{"event_id":"b08-gate-001","event_type":"user_registered","event_time":"2026-09-01T10:00:00+08:00","ingest_time":"2026-09-01T10:00:01+08:00","source_system":"mock-mall","schema_version":"1.0","trace_id":"b08-trace-001","payload":{"user_id":"9001","age_group":"25-34","member_level":"gold"}}'
    '{"event_id":"b08-gate-002","event_type":"user_registered","event_time":"2026-09-01T10:05:00+08:00","ingest_time":"2026-09-01T10:05:01+08:00","source_system":"mock-mall","schema_version":"1.0","trace_id":"b08-trace-002","payload":{"user_id":"9002","age_group":"35-44","member_level":"silver"}}'
  )
  # 显式无 BOM：BOM 会让首行 JSON 解析失败
  [System.IO.File]::WriteAllLines((Join-Path $eventsDir $fixtureName), $fx, (New-Object System.Text.UTF8Encoding($false)))
  $fixtureOk = $true
  Note 'b08' '夹具' "$fixtureName 已写入（2 条 user_registered，均为契约合法事件）"
} catch {
  Note 'b08' '夹具' "写入失败: $($_.Exception.Message)"
  $gateFail = $true
}
$runs1NoNew = $null
try {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $rr = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8091/api/v1/ingestion/runs' -Headers $hdr -TimeoutSec 300
  $sw.Stop()
  $runs1NoNew = $rr.data.noNewData
  $body = ($rr | ConvertTo-Json -Depth 4 -Compress)
  if ($body.Length -gt 600) { $body = $body.Substring(0, 600) + '…' }
  Note 'nodatasource' 'POST ingestion/runs 第1次（数据源已停机）' "HTTP 200 用时 $([math]::Round($sw.Elapsed.TotalSeconds,1))s $body"
  if ($fixtureOk) {
    Note 'b08' 'runs1（有夹具数据）' "noNewData=$($rr.data.noNewData)（期望 False） recordCount=$($rr.data.recordCount)（期望 2） quarantineCount=$($rr.data.quarantineCount)（期望 0）"
  }
} catch {
  $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { -1 }
  Note 'nodatasource' 'POST ingestion/runs 第1次（数据源已停机）' "HTTP $code FAILED: $($_.Exception.Message)"
  $gateFail = $true
}
# B-08（D-022 最小明示）：数据源停机且上一步已排空 → 再采集必须 noNewData=true，状态本身可自证"没有新字节"
try {
  $rr2 = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8091/api/v1/ingestion/runs' -Headers $hdr -TimeoutSec 300
  $st = Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8091/api/v1/ingestion/status' -Headers $hdr
  Note 'b08' 'runs2.noNewData' "$($rr2.data.noNewData)（期望 True）"
  Note 'b08' 'runs2.status/recordCount/quarantineCount' "$($rr2.data.status) / $($rr2.data.recordCount) / $($rr2.data.quarantineCount)"
  Note 'b08' 'status.newFileCount' "$($st.data.newFileCount)（期望 0）"
  Note 'b08' 'status.lastArrivalAt' "$($st.data.lastArrivalAt)"
  Note 'b08' 'status.pendingFiles/pendingBytes/checkpointFiles' "$($st.data.pendingFiles) / $($st.data.pendingBytes) / $($st.data.checkpointFiles)"
  $bad = @()
  if ($fixtureOk -and $runs1NoNew -ne $false) { $bad += '有夹具数据时 runs1.noNewData 不为 false' }
  if ($rr2.data.noNewData -ne $true) { $bad += 'noNewData 不为 true' }
  if ($fixtureOk -and $rr2.data.recordCount -ne 0) { $bad += 'runs2.recordCount 不为 0' }
  if ($st.data.newFileCount -ne 0) { $bad += 'newFileCount 不为 0' }
  if (-not $st.data.lastArrivalAt) { $bad += 'lastArrivalAt 为空' }
  # DEF-13/B-11：checkpointFiles 曾是"全表行数"（含别的环境、别的路径写法的历史行），
  # 于是出现过 101 对 pendingFiles=51。结构不变量：断点数不可能多于目录里的文件数。
  if ($st.data.checkpointFiles -gt $st.data.pendingFiles) {
    $bad += "checkpointFiles($($st.data.checkpointFiles)) 大于 pendingFiles($($st.data.pendingFiles))：断点数不可能多于目录文件数（DEF-13）"
  }
  if ($bad.Count -eq 0) { Note 'b08' '自检' 'PASS' } else { Note 'b08' '自检' "FAIL: $($bad -join '; ')"; $gateFail = $true }
} catch {
  Note 'b08' '自检' "FAIL: $($_.Exception.Message)"
  $gateFail = $true
}
Note 'nodatasource' 'mall:8090 / generator:8092 open' "$(PortOpen 8090) / $(PortOpen 8092)"

# 收尾：全部停掉，并确认三个端口都关闭（不留后台进程）
Stop-All
foreach ($p in $progs) { Note 'cleanup' "$($p.Name).port-closed" (-not (PortOpen $p.Port)) }

# ── 6. 报告
New-Item -ItemType Directory -Force -Path (Join-Path $root $OutDir) | Out-Null
$md = Join-Path $root (Join-Path $OutDir "m1-8-$stamp.md")
$json = Join-Path $root (Join-Path $OutDir "m1-8-$stamp.json")
$obs | ConvertTo-Json -Depth 5 | Set-Content $json -Encoding utf8
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# M1-8 三程序独立启停验收记录（$stamp）")
$lines.Add('')
$lines.Add("命令：``pwsh -File scripts/accept-three-programs.ps1$(if ($SkipBuild) { ' -SkipBuild' })``")
$lines.Add("原始观测：``$((Split-Path $json -Leaf))``（每条一行 ``step / what / value``）")
$lines.Add('')
$lines.Add('| 程序 | 端口 | jar | 启动命令 | 日志 |')
$lines.Add('|---|---|---|---|---|')
foreach ($p in $progs) {
  $jar = Get-Jar $p
  $lines.Add("| $($p.Name) | $($p.Port) | $($jar.Name) | ``$($p.CmdText)`` | ``$($p.Log)`` |")
}
$lines.Add('')
$lines.Add('## 观测明细')
$lines.Add('')
$lines.Add('| step | what | value |')
$lines.Add('|---|---|---|')
foreach ($o in $obs) { $lines.Add("| $($o.step) | $($o.what) | $($o.value -replace '\|', '\|') |") }
$lines.Add('')
$lines.Add("门禁自检（B-08 无新字节语义）：$(if ($gateFail) { 'FAIL' } else { 'PASS' })")
$lines | Set-Content $md -Encoding utf8
Write-Host "报告：$md"
Write-Host "原始观测：$json"
if ($gateFail) { Write-Host '[FAIL] B-08 门禁自检未通过，见报告'; exit 5 }
Write-Host '[PASS] B-08 门禁自检通过'
