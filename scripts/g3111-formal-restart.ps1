<#
g3111-formal-restart.ps1 — G31-11 P3：正式常驻栈受影响终验（V33 落地 + no-op 腿 + G31-07 非发布型腿重跑）
  对象：g3110 resident stack（正式 3307 stage7q1_20260918_152245 双库，终验锚 S20260901_23）。
  五段：A 预检 + READY 清单隔离 → B 新 jar 重启（V33 随 MetaFlywayInitializer 落地）→
        C V33/回填核验 → D 终验 no-op 腿（判据1 在正式栈复证）→ E G31-07 非发布型腿重跑。
  与 g3110-restart.ps1 同契约、三处随 D-047/D-048 迁移：
    - mini-gate 锚 S20260901_23 唯一（终验锚点迁移）；DEC 记录实际值（>=10，不硬编码）
    - 3307 root 口令经 gitignored credref 通道读取，WSLENV/MYSQL_PWD 传入（零硬编码、零 argv）
    - V25_IT_* 进程内新生成，零落盘零 argv 零 git
  零 DROP；prep 幂等（仅轮换应用账号口令）；不触碰 3306（F2b 断言）；
  发布型腿绝不在此脚本执行（发布腿由 P2 隔离栈 g3111iso_20260926_073200 承担）。
  已裁定偏差（脚本内断言 + restart-result-g3111.json 双记录）：
    - BAD|7：7 个 SUCCESS run（id 4,12,15,16,17,19,20）input_batch_id 有值但 source_id 为 NULL
      （S3-36 落库之前的旧 run）→ 不入 V33 回填。其批次（6,14,15,16,17,20,21）的 manifests 已不
      存在（manifests/ 仅 22-27）→ 无重消费风险，回填「防重发布保锚」目的不受损，登记后继续；
      本脚本仍保留硬门：若任一 BAD run 的批次在 manifests/ 仍有 READY 清单 → Fail 15 拒绝回填。
    - manifests-quarantine-g3111/：未消费 READY 清单 23/25/26/27 隔离（23/25/27 从未被任何 run
      消费；26 仅被 FAILED run 24/25 尝试、未发布），否则 V33 落地后普通 run 会 FIFO 捡 23 号
      真发布、漂移 ACTIVE 锚 S20260901_23。隔离 = 移出 manifests/（选择器契约只认该目录），
      字节级保留 + SHA256 台账 + README；accepted/ 数据零改动。
#>
param([switch]$Confirm)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = 'D:\Develop_code\GraduationProject-wt\v3-dev'
$residentRoot = Join-Path $root 'target\v25-it\g3110_20260925_191753'
$dataRunId = 'stage7q1_20260918_152245'
$metaDb = "${dataRunId}_analytics_meta"
$metricDb = "${dataRunId}_analytics_metric"
$landingRoot = Join-Path $residentRoot 'landing'
$manifestsDir = Join-Path $landingRoot 'manifests'
$quarantineDir = Join-Path $landingRoot 'manifests-quarantine-g3111'
$logDir = Join-Path $residentRoot 'logs'
$evDir = Join-Path $residentRoot 'evidence-g3111'
$terminalDir = Join-Path $residentRoot 'terminal'
$identityFile = Join-Path $residentRoot 'platform.identity.json'
$platformLog = Join-Path $logDir 'platform.log'
$platformJar = Join-Path $root 'analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar'
$expectedJarSha = '69990aadb898645d09335f24f5ca50ec166b7c25fa369917cfcb54effdc4ddb7'
$stubDir = Join-Path $root 'target\v25-it\g3103_20260920_052106'
$anchor = 'S20260901_23'
$base = 'http://127.0.0.1:8091'
$TERMINAL = @('SUCCESS', 'FAILED', 'RUN_INTERRUPTED', 'CANCELLED', 'DEGRADED')

New-Item -ItemType Directory -Force -Path $evDir, $terminalDir, $quarantineDir | Out-Null

function Fail([int]$Code, [string]$Message) {
  Write-Host ("[G3111-FORMAL-RESTART-FAIL exit={0}] {1}" -f $Code, $Message)
  @{ outcome = 'FAIL'; code = $Code; error = $Message; finalizedAt = (Get-Date).ToString('s') } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $residentRoot 'restart-result-g3111.json') -Encoding utf8
  exit $Code
}
function Leg([string]$Name, $Obj) {
  $p = Join-Path $evDir "$Name.json"
  $Obj | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $p -Encoding utf8
  Write-Host "  leg saved: $Name"
}
function Wait-Http([string]$Url, [int]$Tries = 120) {
  foreach ($i in 1..$Tries) {
    try { $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { return $true } } catch {}
    Start-Sleep -Milliseconds 1000
  }
  return $false
}
function Get-PlatformJavaCount {
  # 不能用 '*platform-app*.jar'：java 命令行以 ...SNAPSHOT.jar"（收尾引号+空格）结束，.jar 尾锚永不匹配（G31-11 收口空转教训）
  return @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { "$($_.CommandLine)" -like '*platform-app*' }).Count
}
# 3307 root 通道（D-048）：credref 读入口令（零回显），WSLENV/MYSQL_PWD 进 WSL 执行 SQL
$script:ROOTPW = $null
function Invoke-Sql([string]$Db, [string]$Sql, [string]$Name) {
  $sqlPath = Join-Path $evDir ("sql-{0}.sql" -f $Name)
  $outPath = Join-Path $evDir ("sql-{0}.out.txt" -f $Name)
  $Sql | Set-Content -LiteralPath $sqlPath -Encoding utf8
  $sqlW = $sqlPath -replace '\\', '/' -replace '^D:', '/mnt/d'
  $outW = $outPath -replace '\\', '/' -replace '^D:', '/mnt/d'
  $mysqlCmd = 'mysql -h127.0.0.1 -P3307 -uroot --default-character-set=utf8mb4'
  if ($Db) { $mysqlCmd += " $Db" }
  $env:MYSQL_PWD = $script:ROOTPW; $env:WSLENV = 'MYSQL_PWD/u'
  try {
    & wsl.exe bash -c "$mysqlCmd < '$sqlW' > '$outW' 2>&1; exit `$?" | Out-Null
  } finally {
    Remove-Item Env:MYSQL_PWD, Env:WSLENV -ErrorAction SilentlyContinue
  }
  return (Get-Content -LiteralPath $outPath -Raw)
}
function Sql-Lines([string]$Raw, [string]$Prefix) {
  # 前置逗号防单元素解包：否则命中 1 行时返回标量字符串，调用方 [0] 会取到字符串首字符（如 'DBS|2' → 'D'）
  return ,@($Raw -split "`n" | ForEach-Object { ($_ -replace "`r", '').Trim() } | Where-Object { $_ -like "$Prefix|*" })
}
function Api([string]$Method, [string]$Path, $Body, $Headers) {
  $args = @{ Method = $Method; Uri = "$base$Path"; ContentType = 'application/json'; TimeoutSec = 30 }
  if ($Body) { $args.Body = ($Body | ConvertTo-Json -Depth 6) }
  if ($Headers) { $args.Headers = $Headers }
  return Invoke-RestMethod @args
}
function Wait-RunTerminal([long]$TargetRunId, [int]$TimeoutSec, [string]$Label, $Headers) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  $last = $null; $errs = 0
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    try { $last = Api Get "/api/v1/pipeline-runs/$TargetRunId" $null $Headers; $errs = 0 } catch { $errs++; if ($errs -gt 20) { throw "poll 连续失败 $errs 次：$($_.Exception.Message)" }; continue }
    if ($TERMINAL -contains $last.data.status) { return $last.data }
  }
  throw "run $TargetRunId 超时未终态($Label)：status=$($last.data.status) stage=$($last.data.currentStage)"
}
function Find-SnapEntries {
  param($Obj)
  $list = New-Object System.Collections.Generic.List[object]
  $walk = { param($node)
    if ($node -is [pscustomobject]) {
      if ($null -ne $node.PSObject.Properties['snapshotId'] -and $null -ne $node.PSObject.Properties['status']) { $list.Add($node) }
      foreach ($p in $node.PSObject.Properties) { & $walk $p.Value }
    } elseif ($node -is [System.Collections.IEnumerable] -and $node -isnot [string]) {
      foreach ($x in @($node)) { & $walk $x }
    }
  }
  & $walk $Obj
  return $list
}
function Find-PropArrays {
  param($Obj, [string]$KeyName)
  $list = New-Object System.Collections.Generic.List[object]
  $walk = { param($node)
    if ($node -is [pscustomobject]) {
      foreach ($p in $node.PSObject.Properties) {
        if ($p.Name -eq $KeyName -and $p.Value -is [System.Collections.IEnumerable] -and $p.Value -isnot [string]) { $list.Add($p.Value) }
        & $walk $p.Value
      }
    } elseif ($node -is [System.Collections.IEnumerable] -and $node -isnot [string]) {
      foreach ($x in @($node)) { & $walk $x }
    }
  }
  & $walk $Obj
  return $list
}

Write-Host '=== g3111-formal-restart: V33 landing + no-op leg + G31-07 non-publishing legs (anchor S20260901_23) ==='
if (-not $Confirm) { Fail 5 '真执行必须显式给出 -Confirm。' }

# ───────────────────────── 段A：预检 + READY 清单隔离 ─────────────────────────
Write-Host '=== [段A] 预检：credref / 正式库对 / flyway / 回填预检 / READY 清单隔离 ==='
$credRef = Join-Path $root 'credref-mysql3307-root.properties'
if (-not (Test-Path $credRef)) { Fail 5 "缺凭据引用 $credRef（D-048 通道）。" }
$script:ROOTPW = @((Get-Content -LiteralPath $credRef | Where-Object { $_ -like 'MYSQL3307_ROOT_PASSWORD=*' }) -replace '^MYSQL3307_ROOT_PASSWORD=', '')[0].Trim()
if ($script:ROOTPW -notmatch '^[0-9a-fA-F]{40}$') { Fail 5 'credref 的 MYSQL3307_ROOT_PASSWORD 非 40-hex；拒绝执行。' }

$ping = Invoke-Sql $null "SELECT CONCAT('PING|', 1); SELECT CONCAT('DBS|', COUNT(*)) FROM information_schema.SCHEMATA WHERE schema_name IN ('$metaDb', '$metricDb');" 'a-ping'
if ((Sql-Lines $ping 'PING').Count -ne 1) { Fail 5 "root 通道 ping 失败：$ping" }
$dbs = (Sql-Lines $ping 'DBS')[0]
if ($dbs -ne 'DBS|2') { Fail 5 "正式库对不齐（$dbs，期望 DBS|2）。" }

$fly = Invoke-Sql $null "SELECT CONCAT('FLYPRE|', COUNT(*)) FROM $metaDb.flyway_schema_history; SELECT CONCAT('FLYTOP|', version, '|', success) FROM $metaDb.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;" 'a-flyway'
$flypre = (Sql-Lines $fly 'FLYPRE')[0]
$flytop = (Sql-Lines $fly 'FLYTOP')[0]
$v33Already = $false
if ($flypre -eq 'FLYPRE|31' -and $flytop -eq 'FLYTOP|32|1') { Write-Host '  flyway 预检：31 行 V32 顶（V33 待落地）。' }
elseif ($flypre -eq 'FLYPRE|32' -and $flytop -eq 'FLYTOP|33|1') { $v33Already = $true; Write-Host '  flyway 预检：V33 已落地（重入核验模式）。' }
else { Fail 5 "flyway 状态异常：$flypre / $flytop（期望 31 行 V32 顶，或重入态 32 行 V33 顶）。" }

$pre = Invoke-Sql $null @"
SELECT CONCAT('BAD|', COUNT(*)) FROM $metaDb.pipeline_run WHERE status='SUCCESS' AND (input_batch_id IS NULL OR source_id IS NULL);
SELECT CONCAT('SUC|', COUNT(*)) FROM $metaDb.pipeline_run WHERE status='SUCCESS';
SELECT CONCAT('EXPECTBF|', COUNT(DISTINCT source_id, input_batch_id)) FROM $metaDb.pipeline_run
 WHERE status='SUCCESS' AND source_id IS NOT NULL AND input_batch_id IS NOT NULL;
SELECT CONCAT('CONSBATCH|', id, '|', source_id, '|', input_batch_id) FROM $metaDb.pipeline_run
 WHERE status='SUCCESS' AND source_id IS NOT NULL AND input_batch_id IS NOT NULL ORDER BY id;
"@ 'a-backfill-precheck'
$badCount = [int]((Sql-Lines $pre 'BAD')[0] -replace '^BAD\|', '')
$sucCount = [int]((Sql-Lines $pre 'SUC')[0] -replace '^SUC\|', '')
$expectBf = [int]((Sql-Lines $pre 'EXPECTBF')[0] -replace '^EXPECTBF\|', '')
# 管道语境必须括号化调用：Sql-Lines 的 ,@() 包装在管道里会把整个内层数组当单个 $_ 传入，
# ($arr -split '|')[n] 先全展平再取下标 → 永远只得首行第 n 列（G31-11 attempt2/3 误隔离根因）
$consBatches = @((Sql-Lines $pre 'CONSBATCH') | ForEach-Object { ($_ -split '\|')[3] })
$badRiskBatches = @()
if ($badCount -gt 0) {
  $badRows = Invoke-Sql $null "SELECT CONCAT('BADRUN|', id, '|', input_batch_id) FROM $metaDb.pipeline_run WHERE status='SUCCESS' AND (input_batch_id IS NULL OR source_id IS NULL) ORDER BY id;" 'a-bad-runs'
  $badRiskBatches = @((Sql-Lines $badRows 'BADRUN') | ForEach-Object { $p = $_ -split '\|'; if ($p[2] -ne 'N') { $p[2] } })
}
# BAD≠0 时只有当其批次仍存在 READY 清单才是真风险（回填漏行 → FIFO 重捡重发布）；
# 批次清单已物理不存在则零风险，登记继续；存在任一 → 拒绝（D-049h fail-closed）。
$readyNow = @{}
Get-ChildItem -LiteralPath $manifestsDir -Filter '*.json' -File | ForEach-Object {
  $j = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
  if ($j.status -eq 'READY') { $readyNow[[string]$j.batchId] = $_.Name }
}
$riskyIntersection = @($badRiskBatches | Where-Object { $readyNow.ContainsKey($_) })
if ($riskyIntersection.Count -gt 0) { Fail 15 ("BAD run 的批次仍有 READY 清单（{0}）→ 回填将漏行、FIFO 可重捡重发布；停止并登记。" -f ($riskyIntersection -join ',')) }

# READY 清单隔离：READY − 已消费（source 集 SUCCESS run 的 input 批）→ 移入 manifests-quarantine-g3111/
$quarantined = @()
foreach ($b in @($readyNow.Keys)) {
  if ($consBatches -notcontains $b) {
    $src = Join-Path $manifestsDir $readyNow[$b]
    $sha = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant()
    Move-Item -LiteralPath $src -Destination (Join-Path $quarantineDir $readyNow[$b])
    $quarantined += [ordered]@{ batchId = $b; file = $readyNow[$b]; sha256 = $sha }
  }
}
if (-not (Test-Path (Join-Path $quarantineDir 'README.md'))) {
  $shaLines = foreach ($q in $quarantined) { "{0}  {1}" -f $q.sha256, $q.file }
  @"
# manifests-quarantine-g3111 — G31-11 P3 READY 清单隔离（2026-09-26）
原因：这些批次的 READY 清单从未被消费（或仅被 FAILED run 尝试未发布），V33 消费台账
落地后若留在 manifests/，普通 run 会 FIFO 捡起最老未消费批**真发布**，漂移终验锚
S20260901_23。隔离 = 移出 manifests/（LandingManifestSelector 只认该目录），字节级
保留（SHA256 见 quarantine-sha256.txt）；accepted/ 数据零改动；非消费事实伪造
（pipeline_batch_consumption 不为它们补行）。恢复 = 移回 manifests/（将重新成为候选）。
"@ | Set-Content -LiteralPath (Join-Path $quarantineDir 'README.md') -Encoding utf8
  $shaLines | Set-Content -LiteralPath (Join-Path $quarantineDir 'quarantine-sha256.txt') -Encoding ascii
} elseif ($quarantined.Count -gt 0) {
  $shaLines = foreach ($q in $quarantined) { "{0}  {1}" -f $q.sha256, $q.file }
  $shaLines | Add-Content -LiteralPath (Join-Path $quarantineDir 'quarantine-sha256.txt') -Encoding ascii
}
Leg 'quarantine' @{ dir = $quarantineDir; quarantinedNow = $quarantined; readyBefore = @($readyNow.Keys | Sort-Object); consumedBatches = $consBatches; badCount = $badCount; badRunsNote = 'BAD run 批次清单已不存在，零重消费风险（见 README/结果 JSON 偏差登记）' }
# 隔离后硬断言：剩余 READY ⊆ 已消费批次
$readyAfter = @{}
Get-ChildItem -LiteralPath $manifestsDir -Filter '*.json' -File | ForEach-Object {
  $j = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
  if ($j.status -eq 'READY') { $readyAfter[[string]$j.batchId] = $_.Name }
}
$notConsumed = @($readyAfter.Keys | Where-Object { $consBatches -notcontains $_ })
if ($notConsumed.Count -gt 0) { Fail 16 ("隔离后仍有未消费 READY 清单：{0}" -f ($notConsumed -join ',')) }
Write-Host ("  清单隔离：隔离 {0} 份（{1}）；剩余 READY {2} ⊆ 已消费 {3}。" -f $quarantined.Count, (($quarantined | ForEach-Object { $_.batchId }) -join ','), (($readyAfter.Keys | Sort-Object) -join ','), ($consBatches -join ','))

$snapBefore = Invoke-Sql $metricDb "SELECT CONCAT('SNAPCNT|', COUNT(*)) FROM metric_snapshot; SELECT CONCAT('ACT|', snapshot_id, '|', status) FROM metric_snapshot WHERE status='ACTIVE';" 'a-snap-before'
$snapCountBefore = [int]((Sql-Lines $snapBefore 'SNAPCNT')[0] -replace '^SNAPCNT\|', '')
$actLines = Sql-Lines $snapBefore 'ACT'
if ($actLines.Count -ne 1 -or $actLines[0] -ne "ACT|$anchor|ACTIVE") { Fail 5 "ACTIVE 预检不符：$($actLines -join ' / ')" }

$jar = Get-Item $platformJar
$jarSha = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($jarSha -ne $expectedJarSha) { Fail 5 "platform jar SHA 不符：$jarSha（期望 $expectedJarSha）——G31-11 P3 必须跑 D-049x 修复后的 jar。" }
Write-Host ("  预检完成：BAD={0}（零风险已登记）、EXPECTBF={1}、SNAPCNT={2}、jar SHA {3}…" -f $badCount, $expectBf, $snapCountBefore, $jarSha.Substring(0, 12))

# ───────────────────────── 段B：新 jar 重启常驻栈 ─────────────────────────
Write-Host '=== [段B] stub LLM + 重启平台（g3110-restart 同契约）==='
$stubUp = $false
try { $stubUp = ((Invoke-WebRequest -Uri 'http://127.0.0.1:18080/healthz' -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) } catch {}
$stubLaunched = $false
if (-not $stubUp) {
  $stubPy = Join-Path $stubDir 'stub_llm.py'
  if (-not (Test-Path $stubPy)) { Fail 5 "stub_llm.py 缺失：$stubPy" }
  $py = Get-Command python.exe -ErrorAction SilentlyContinue
  if (-not $py) { Fail 5 'python.exe 不在 PATH；无法拉起 stub。' }
  $stubProc = Start-Process -FilePath $py.Source -ArgumentList ('"{0}"' -f $stubPy) -WindowStyle Hidden -WorkingDirectory $stubDir -PassThru
  Set-Content -LiteralPath (Join-Path $stubDir 'stub_llm.pid') -Value $stubProc.Id -Encoding ascii
  $stubLaunched = $true
  if (-not (Wait-Http 'http://127.0.0.1:18080/healthz' 30)) { Fail 5 'stub LLM 拉起后 /healthz 仍未就绪。' }
}
Write-Host ("  stub preflight: /healthz 200 OK{0}." -f $(if ($stubLaunched) { ' (launched this run)' } else { ' (already up)' }))

$portBusy = Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue
if ($portBusy) { Fail 5 '8091 仍有监听进程（隔离栈收口未完成？）；拒绝并行。' }
if ((Get-PlatformJavaCount) -ne 0) { Fail 5 '已存在 platform JVM；拒绝并行启动。' }
Write-Host '  preflight: 8091 free, zero platform JVM.'

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
function New-RandomSecret {
  param([int]$Bytes = 24)
  $buf = [byte[]]::new($Bytes); $rng.GetBytes($buf)
  $s = [Convert]::ToBase64String($buf) -replace '[^A-Za-z0-9]', ''
  while ($s.Length -lt 24) { $more = [byte[]]::new($Bytes); $rng.GetBytes($more); $s += ([Convert]::ToBase64String($more) -replace '[^A-Za-z0-9]', '') }
  return $s.Substring(0, 24)
}
$env:V25_IT_META_PASSWORD = New-RandomSecret
$env:V25_IT_METRIC_PUBLISH_PASSWORD = New-RandomSecret
$env:V25_IT_METRIC_READ_PASSWORD = New-RandomSecret
$env:V25IT_ADMIN_PWD = $script:ROOTPW
Write-Host 'password channel: credref root + fresh in-process secrets SET/SET/SET.'

$prepLog = Join-Path $logDir 'prep-g3111-restart.log'
& pwsh -NoProfile -File (Join-Path $root 'scripts\it-prepare-isolation.ps1') -RunId $dataRunId -Confirm -AllowRootOnIsolated -IncludeAnalytics *> $prepLog
if ($LASTEXITCODE -ne 0) { Fail 1 ("prep 退出码 {0}；日志 {1}" -f $LASTEXITCODE, $prepLog) }
Write-Host '  prep (idempotent) exit 0.'

$miniSql = Join-Path $evDir 'sql-mini-gate.sql'
@"
SELECT CONCAT('SNAP|', snapshot_id, '|', status) FROM $metricDb.metric_snapshot WHERE status = 'ACTIVE';
SELECT CONCAT('DEC|', COUNT(*)) FROM $metaDb.decision_task;
SELECT CONCAT('USR|', COUNT(*)) FROM $metaDb.sys_user;
"@ | Set-Content -LiteralPath $miniSql -Encoding utf8
$miniText = Invoke-Sql $null (Get-Content -LiteralPath $miniSql -Raw) 'mini-gate'
$bad = @()
$activeLines = Sql-Lines $miniText 'SNAP'
if ($activeLines.Count -ne 1 -or $activeLines[0] -ne "SNAP|$anchor|ACTIVE") { $bad += "ACTIVE snapshot: $($activeLines -join ',')" }
$decLine = (Sql-Lines $miniText 'DEC')[0]
$decCount = 0
if ($decLine -match '^DEC\|(\d+)\s*$') { $decCount = [int]$Matches[1] } else { $bad += "DEC line unreadable: $decLine" }
if ($decCount -lt 10) { $bad += "decision_task count $decCount < 10" }
if ($miniText -notmatch '(?m)^USR\|3\s*$') { $bad += 'sys_user != 3' }
if ($bad.Count -gt 0) { Fail 1 ("mini-gate 未过：{0}" -f ($bad -join '; ')) }
Write-Host ("  mini-gate: $anchor ACTIVE (unique), DEC={0} (>=10, actual recorded), USR=3." -f $decCount)

. (Join-Path $root 'scripts\isolation-naming.ps1')
$jdbcSuffix = '?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true'
$env:PLATFORM_META_URL = "jdbc:mysql://127.0.0.1:3307/$metaDb$jdbcSuffix"
$env:PLATFORM_META_USER = New-IsolationUserName -RunId $dataRunId -Role 'metaapp'
$env:PLATFORM_META_PASSWORD = $env:V25_IT_META_PASSWORD
$env:PLATFORM_METRIC_PUBLISH_URL = "jdbc:mysql://127.0.0.1:3307/$metricDb$jdbcSuffix"
$env:PLATFORM_METRIC_PUBLISH_USER = New-IsolationUserName -RunId $dataRunId -Role 'metricapp'
$env:PLATFORM_METRIC_PUBLISH_PASSWORD = $env:V25_IT_METRIC_PUBLISH_PASSWORD
$env:PLATFORM_METRIC_READ_URL = $env:PLATFORM_METRIC_PUBLISH_URL
$env:PLATFORM_METRIC_READ_USER = New-IsolationUserName -RunId $dataRunId -Role 'metricread'
$env:PLATFORM_METRIC_READ_PASSWORD = $env:V25_IT_METRIC_READ_PASSWORD
$env:PLATFORM_LANDING_LOCAL_ROOT = $landingRoot
$env:PLATFORM_SOURCE_PROFILE_ROOT = $root
$env:PLATFORM_SPARK_WAREHOUSE_DIR = Join-Path $residentRoot 'spark-warehouse'
$env:PLATFORM_SPARK_METASTORE_DIR = Join-Path $residentRoot 'derby-metastore-w'
$env:PLATFORM_MAPPING_SAMPLE_ROOT = Join-Path $residentRoot 'samples'
$env:LLM_BASE_URL = 'http://127.0.0.1:18080'
$env:LLM_API_KEY = 'stub-local-key'
$env:LLM_MODEL = 'stub-local'
$guardOut = Join-Path $evDir 'guard-positive-g3111-restart.out.txt'
& pwsh -NoProfile -File (Join-Path $root 'scripts\assert-platform-env.ps1') -RequirePlatform -ExtraRequired @('PLATFORM_MAPPING_SAMPLE_ROOT') -AllowedSchemaPrefixes @("${dataRunId}_") *> $guardOut
if ($LASTEXITCODE -ne 0) { Fail 12 'F2a 正例门禁未通过。' }
Write-Host ("  F2a: {0}" -f (Get-Content -LiteralPath $guardOut | Select-Object -First 1))

$javaExe = (Get-Command java.exe).Source
$metricStaging = Join-Path $residentRoot 'metric-staging'
$inner = '-Dfile.encoding=UTF-8 -Dplatform.metric.publish.export-dir="{0}" -jar "{1}"' -f $metricStaging, $jar.FullName
$cmdArgs = '/c ""{0}" {1} >> "{2}" 2>&1"' -f $javaExe, $inner, $platformLog
if (-not (Test-Path (Join-Path $residentRoot 'platform.identity.pre-restart-g3111.json'))) {
  # 仅首次进入备份原身份（保留 g3110 时代 platform.identity.json 快照）；重入不覆写
if (-not (Test-Path (Join-Path $residentRoot 'platform.identity.pre-restart-g3111.json'))) {
  Copy-Item -LiteralPath $identityFile -Destination (Join-Path $residentRoot 'platform.identity.pre-restart-g3111.json') -Force
}
}
$wrapper = Start-Process -FilePath (Join-Path $env:WINDIR 'System32\cmd.exe') -ArgumentList $cmdArgs -WindowStyle Hidden -WorkingDirectory $root -PassThru
Set-Content -LiteralPath (Join-Path $residentRoot 'platform.pid') -Value $wrapper.Id -Encoding ascii
[ordered]@{ pid = $wrapper.Id; name = 'cmd.exe'; marker = 'g3111-formal-restart'; restartedAt = (Get-Date).ToString('s'); logPath = $platformLog; driver = 'g3111-formal-restart'; jarSha256 = $jarSha } |
  ConvertTo-Json | Set-Content -LiteralPath $identityFile -Encoding utf8
Write-Host "  platform starting (wrapper PID $($wrapper.Id))..."
if (-not (Wait-Http "$base/api/v1/metrics/health")) { Fail 1 "平台未就绪；日志：$platformLog" }
Write-Host '  platform healthy on 8091.'
Start-Sleep -Seconds 2

$allLog = Get-Content -LiteralPath $platformLog -Raw
$tailLen = [Math]::Min($allLog.Length, 200000)
$logText = $allLog.Substring($allLog.Length - $tailLen)
$g3306 = $logText.Contains(':3306')
$gMeta = $logText.Contains("3307/$metaDb")
$gMetric = $logText.Contains("3307/$metricDb")
$gLanding = $logText.Contains($landingRoot) -or $logText.Contains(($landingRoot -replace '\\', '/'))
if ($g3306 -or -not ($gMeta -and $gMetric -and $gLanding)) { Fail 13 'F2b 启动后日志守卫不符。' }
Write-Host '  F2b: 3307 URLs + landing evidenced; zero :3306.'

# ───────────────────────── 段C：V33 / 回填核验 ─────────────────────────
Write-Host '=== [段C] V33 落地 + 回填核验 ==='
$fly2 = Invoke-Sql $null "SELECT CONCAT('FLY|', COUNT(*)) FROM $metaDb.flyway_schema_history; SELECT CONCAT('FLYTOP|', version, '|', success) FROM $metaDb.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;" 'c-flyway-after'
$flyCnt = (Sql-Lines $fly2 'FLY')[0]
$flyTop2 = (Sql-Lines $fly2 'FLYTOP')[0]
if ($flyCnt -ne 'FLY|32' -or $flyTop2 -ne 'FLYTOP|33|1') { Fail 16 "V33 落地核验不符：$flyCnt / $flyTop2（期望 FLY|32 + FLYTOP|33|1）。" }
$bf = Invoke-Sql $null "SELECT CONCAT('BF|', COUNT(*)) FROM $metaDb.pipeline_batch_consumption WHERE created_via='BACKFILL_V33'; SELECT CONCAT('CONS|', COUNT(*)) FROM $metaDb.pipeline_batch_consumption; SELECT CONCAT('PIPE|', COUNT(*)) FROM $metaDb.pipeline_batch_consumption WHERE created_via='PIPELINE';" 'c-backfill'
$bfCnt = [int]((Sql-Lines $bf 'BF')[0] -replace '^BF\|', '')
$consCnt = [int]((Sql-Lines $bf 'CONS')[0] -replace '^CONS\|', '')
$pipeCnt = [int]((Sql-Lines $bf 'PIPE')[0] -replace '^PIPE\|', '')
if ($bfCnt -ne $expectBf) { Fail 16 "回填行数 $bfCnt != 预检预期 $expectBf（SUCCESS 且 source/input 双非空的去重批次数）。" }
if ($consCnt -ne $bfCnt -or $pipeCnt -ne 0) { Fail 16 "台账总数异常：CONS=$consCnt BF=$bfCnt PIPE=$pipeCnt（落地后应无 PIPELINE 行）。" }
Leg 'fly33-backfill' @{ flywayRows = 32; topVersion = '33'; backfillRows = $bfCnt; expectedBackfill = $expectBf; totalConsumptionRows = $consCnt; pipelineRows = $pipeCnt; consumedBatches = $consBatches }
Write-Host ("  V33 OK：flyway 32 行 V33 顶 success=1；回填 {0} 行（批 {1}）== 预检预期；PIPELINE 行 0。" -f $bfCnt, ($consBatches -join ','))

# ───────────────────────── 段D：终验 no-op 腿（判据1 正式栈复证） ─────────────────────────
Write-Host '=== [段D] no-op 腿：READY 全已消费 → SUCCESS + ALREADY_CONSUMED，ACTIVE 不变 ==='
$login = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType 'application/json' -Body (@{ username = 'admin'; password = 'admin123' } | ConvertTo-Json)
$adminTok = $login.data.token
if ([string]::IsNullOrWhiteSpace($adminTok)) { Fail 1 '登录冒烟未过。' }
$H = @{ Authorization = "Bearer $adminTok" }
$consBeforeD = [int]((Sql-Lines (Invoke-Sql $metaDb 'SELECT CONCAT(''CONS|'', COUNT(*)) FROM pipeline_batch_consumption;' 'd-cons-before') 'CONS')[0] -replace '^CONS\|', '')
# noop-1 已被 attempt3 的 no-op run 26 消费；重入须换新键，否则命中幂等去重不建 run
$created = Api Post '/api/v1/pipeline-runs' @{ runtimeProfileId = 1; pipelineCode = 'ODS_TO_ADS'; businessTime = '2026-09-01T00:00:00'; sourceDataVersion = 'g3111-formal-noop' } (@{ Authorization = "Bearer $adminTok"; 'Idempotency-Key' = 'g3111-formal-noop-2' })
$noopRunId = [long]$created.data.runId
if (-not $noopRunId) { Fail 17 'no-op run 创建未返回 runId' }
$noopRun = Wait-RunTerminal $noopRunId 300 'formal-noop' $H
if ($noopRun.status -ne 'SUCCESS') { Fail 17 ("no-op run 非预期终态 {0} stage={1} err={2}（期望 READY 全已消费 → M3 no-op SUCCESS）" -f $noopRun.status, $noopRun.currentStage, $noopRun.errorCode) }
Leg 'noop-run' @{ runId = $noopRunId; status = $noopRun.status; inputBatchId = $noopRun.inputBatchId; targetSnapshotId = $noopRun.targetSnapshotId }

$noopDb = Invoke-Sql $metaDb @"
SELECT CONCAT('NRUN|', status, '|', IFNULL(input_batch_id,'N'), '|', IFNULL(target_snapshot_id,'N'), '|', IFNULL(recalc_reason,'N')) FROM pipeline_run WHERE id = $noopRunId;
SELECT CONCAT('NWL|', LEFT(evidence, 400)) FROM pipeline_stage_run WHERE run_id = $noopRunId AND stage_code = 'WAIT_LANDING';
SELECT CONCAT('SNAPCNT|', COUNT(*)) FROM $metricDb.metric_snapshot;
SELECT CONCAT('CONS|', COUNT(*)) FROM pipeline_batch_consumption;
"@ 'd-noop-db'
$nrun = (Sql-Lines $noopDb 'NRUN')[0]
if ($nrun -ne 'NRUN|SUCCESS|N|N|N') { Fail 17 "no-op run 落库形状不符：$nrun（期望 SUCCESS + input/snapshot/recalc 均 NULL）。" }
$wlLine = (Sql-Lines $noopDb 'NWL')[0]
if ($wlLine -notmatch 'noNewInput' -or $wlLine -notmatch 'ALREADY_CONSUMED') { Fail 17 "WAIT_LANDING 证据缺 no-op 标记：$wlLine" }
$snapCountAfter = [int]((Sql-Lines $noopDb 'SNAPCNT')[0] -replace '^SNAPCNT\|', '')
$consAfterD = [int]((Sql-Lines $noopDb 'CONS')[0] -replace '^CONS\|', '')
if ($snapCountAfter -ne $snapCountBefore) { Fail 17 "快照数漂移：$snapCountBefore → $snapCountAfter（no-op 不得分配快照）。" }
if ($consAfterD -ne $consBeforeD) { Fail 17 "消费台账行数漂移：$consBeforeD → $consAfterD（no-op 不写台账）。" }
$actAfter = Invoke-Sql $metricDb "SELECT CONCAT('ACT|', snapshot_id, '|', status) FROM metric_snapshot WHERE status='ACTIVE';" 'd-active-after'
$actAfterLines = Sql-Lines $actAfter 'ACT'
if ($actAfterLines.Count -ne 1 -or $actAfterLines[0] -ne "ACT|$anchor|ACTIVE") { Fail 17 "no-op 后 ACTIVE 不符：$($actAfterLines -join ' / ')" }
Leg 'noop-db-evidence' @{ runId = $noopRunId; runRow = $nrun; waitLandingEvidence = $wlLine; snapshotCountBefore = $snapCountBefore; snapshotCountAfter = $snapCountAfter; consumptionRowsBefore = $consBeforeD; consumptionRowsAfter = $consAfterD; activeAfter = $actAfterLines[0] }
Write-Host ("  no-op OK：run {0} SUCCESS/ALREADY_CONSUMED，快照 {1}→{1}，台账 {2}→{2}，ACTIVE 唯一 {3}。" -f $noopRunId, $snapCountBefore, $consBeforeD, $anchor)

# ───────────────────────── 段E：G31-07 非发布型腿重跑（9 腿） ─────────────────────────
Write-Host '=== [段E] G31-07 非发布型腿（health/login/profiles/runs/snapshots/fingerprint/F2b/ai-probe/identity）==='
# 变量名禁用 $h：PowerShell 变量大小写不敏感，会覆写鉴权头 $H（attempt3 段E 404 崩溃根因）
try { $healthResp = Invoke-RestMethod -Uri "$base/api/v1/metrics/health" -Method Get -TimeoutSec 10 } catch { Fail 1 "8091 健康探针失败：$($_.Exception.Message)" }
Leg 'health' $healthResp
if (-not $healthResp.data.ok) { Fail 1 "health ok!=true: $($healthResp.data.detail)" }

$analyst = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType 'application/json' -Body (@{ username = 'analyst'; password = 'analyst123' } | ConvertTo-Json)
if (-not $analyst.data.token) { Fail 2 'analyst login 无 token' }
Leg 'login-smoke' @{ adminUser = $login.data.username; adminRole = $login.data.role; analystUser = $analyst.data.username; analystRole = $analyst.data.role }

$profiles = Invoke-RestMethod -Uri "$base/api/v1/runtime-profiles" -Headers $H -Method Get
Leg 'runtime-profiles' $profiles
$runs = Invoke-RestMethod -Uri "$base/api/v1/pipeline-runs" -Headers $H -Method Get
Leg 'pipeline-runs' $runs

$snap = Invoke-RestMethod -Uri "$base/api/v1/metrics/snapshots" -Headers $H -Method Get
Leg 'snapshots' $snap
$activeEntries = @(Find-SnapEntries $snap | Where-Object { $_.status -eq 'ACTIVE' })
if ($activeEntries.Count -ne 1) { Fail 5 "ACTIVE 快照数 $($activeEntries.Count) != 1" }
if ($activeEntries[0].snapshotId -ne $anchor) { Fail 5 "ACTIVE=$($activeEntries[0].snapshotId) != $anchor" }

$fp = Invoke-RestMethod -Uri "$base/api/v1/dashboards/overview" -Headers $H -Method Get
Leg 'overview' $fp
if ($fp.data.snapshotId -ne $anchor) { Fail 6 "overview ACTIVE=$($fp.data.snapshotId) != $anchor" }
if ($fp.data.source -ne 'spark-ads') { Fail 6 "source=$($fp.data.source) != spark-ads" }
if ($fp.data.qualityStatus -ne 'PASS') { Fail 6 "qualityStatus=$($fp.data.qualityStatus) != PASS" }
$oracle = [ordered]@{
  pv = 8.0; uv = 3.0; dau = 3.0; fav_cnt = 3.0; cart_add_cnt = 3.0
  paid_order_cnt = 5.0; gmv = 2042.0; net_sale = 1493.0; avg_order_value = 408.4
  refund_rate = 0.6; full_refund_rate = 0.2; repeat_rate = 0.3333; buy_rate = 1.0; cart_rate = 0.6667
}
$metricLists = @(Find-PropArrays $fp 'metrics')
$map = @{}
foreach ($ml in $metricLists) { foreach ($m in @($ml)) { if ($m -is [pscustomobject] -and $null -ne $m.PSObject.Properties['metricCode']) { $map[[string]$m.metricCode] = $m.value } } }
if ($map.Count -eq 0) { Fail 6 'overview 载荷未找到 metrics（形状漂移）' }
$extra = @($map.Keys | Where-Object { -not $oracle.Contains($_) })
if ($extra.Count -gt 0) { Fail 6 ("多余指标: " + ($extra -join ',')) }
$results = [ordered]@{}; $badM = 0
foreach ($k in $oracle.Keys) {
  $exp = [double]$oracle[$k]
  $act = if ($null -ne $map[$k]) { [double]$map[$k] } else { [double]::NaN }
  $ok = [Math]::Abs($act - $exp) -lt 0.0005
  $results[$k] = @{ expected = $exp; actual = $act; passed = $ok }
  if (-not $ok) { $badM++; Write-Host ("  METRIC-DIFF {0}: expected={1} actual={2}" -f $k, $exp, $act) }
}
Leg 'metric-compare' @{ snapshotId = $fp.data.snapshotId; businessTime = $fp.data.businessTime; passCount = (14 - $badM); results = $results; oracleSource = 'g3110 oracle/p6-three-way.json（oracle==DB==API 三方对账产物）' }
if ($badM -ne 0) { Fail 6 "14 指标指纹未全等（$badM 项差异）" }
Write-Host '  metric fingerprint 14/14 == P6 oracle'

$allLog2 = Get-Content -LiteralPath $platformLog -Raw
$tailLen2 = [Math]::Min($allLog2.Length, 200000)
$logText2 = $allLog2.Substring($allLog2.Length - $tailLen2)
$g3306b = $logText2.Contains(':3306')
$gMetaB = $logText2.Contains("3307/$metaDb")
$gMetricB = $logText2.Contains("3307/$metricDb")
$gLandingB = $logText2.Contains($landingRoot) -or $logText2.Contains(($landingRoot -replace '\\', '/'))
Leg 'f2b-log-guard' @{ log = $platformLog; tailChars = $tailLen2; zero3306 = (-not $g3306b); metaUrlEvidenced = $gMetaB; metricUrlEvidenced = $gMetricB; landingEvidenced = $gLandingB }
if ($g3306b -or -not ($gMetaB -and $gMetricB -and $gLandingB)) { Fail 13 'F2b 日志守卫不符' }

$HA = @{ Authorization = "Bearer $($analyst.data.token)" }
$qBytes = [System.Text.Encoding]::UTF8.GetBytes((@{ question = '最新一期的 GMV 和退款率是多少？' } | ConvertTo-Json))
$qr = Invoke-RestMethod -Method Post -Uri "$base/api/v1/ai/queries" -Headers $HA -ContentType 'application/json; charset=utf-8' -Body $qBytes
Leg 'ai-probe' $qr
$sugCount = @($qr.data.explanation.suggestions).Count
$probeOk = ($qr.data.query.providerUsed -eq 'stub-local') -and ($qr.data.query.status -eq 'EXECUTED') -and ($sugCount -ge 1)
if (-not $probeOk) { Fail 14 ("LLM 探针未过：providerUsed={0} status={1} suggestions={2}" -f $qr.data.query.providerUsed, $qr.data.query.status, $sugCount) }

$ident = Get-Content -LiteralPath $identityFile -Raw | ConvertFrom-Json
$lpid = (Get-NetTCPConnection -State Listen -LocalPort 8091 | Select-Object -First 1).OwningProcess
$lproc = Get-CimInstance Win32_Process -Filter "ProcessId=$lpid"
Leg 'process-identity' @{ listenerPid = $lpid; listenerName = $lproc.Name; listenerParent = $lproc.ParentProcessId; identityWrapperPid = $ident.pid; identityMarker = $ident.marker; identityDriver = $ident.driver; jarSha256 = $ident.jarSha256 }
if ($lproc.Name -ne 'java.exe') { Fail 9 "8091 进程名 $($lproc.Name) != java.exe" }
if ($ident.pid -and ($lproc.ParentProcessId -ne [int]$ident.pid)) { Fail 9 "父进程 $($lproc.ParentProcessId) != identity wrapper $($ident.pid)" }
if ($ident.jarSha256 -ne $expectedJarSha) { Fail 9 "identity jarSha != 期望（D-049x jar）。" }

[ordered]@{
  outcome = 'PASS'; driver = 'g3111-formal-restart'; finalizedAt = (Get-Date).ToString('s')
  stack = "g3110 resident (formal 3307 + V33 applied), jarSha256=$jarSha"
  anchor = $anchor
  deviations = @{
    badSeven = "BAD=$badCount（SUCCESS run id 4,12,15,16,17,19,20，S3-36 前 source_id 未落库）；其批次清单已不存在 → 零重消费风险，登记继续"
    quarantine = @($quarantined | ForEach-Object { $_.batchId })
  }
  v33 = @{ flywayRows = 32; backfillRows = $bfCnt; expectedBackfill = $expectBf }
  noopRun = @{ runId = $noopRunId; snapshotCountBefore = $snapCountBefore; snapshotCountAfter = $snapCountAfter }
  metricPass = 14; aiProbe = 'stub-local/EXECUTED'
  f2b = 'zero :3306 in platform.log tail'
  legs = @('precheck-quarantine','restart','fly33-backfill','noop-run','health','login','profiles','runs','snapshots-active-unique','overview-14-metrics','f2b-log-guard','ai-probe','process-identity')
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $residentRoot 'restart-result-g3111.json') -Encoding utf8
Write-Host "=== G3111-FORMAL-RESTART PASS: V33 landed, no-op leg OK, G31-07 legs OK (anchor=$anchor) ==="
exit 0
