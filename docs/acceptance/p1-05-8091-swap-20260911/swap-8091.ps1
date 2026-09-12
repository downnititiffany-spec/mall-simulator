# =============================================================================
# P1-05 轮 · D-040 原子换血：对真库 analytics_meta 应用 V17 + 8091 换新 jar（同批）
# -----------------------------------------------------------------------------
# 依据：决策记录 D-039（用户授权 P1-05 轮对真库应用 V17）
#       D-040（用户裁决：V17 与 8091 换新 jar 必须同批原子完成；由总控执行）
#
# 为什么必须原子（已实测的时序风险）：V17 一旦应用，旧 jar 的写入路径会因
#   `file_checkpoint.source_id` NOT NULL 无默认值而报
#   `Field 'source_id' doesn't have a default value`（读路径不受影响）。
#   故「先迁库后换 jar」会让中间窗口内的采集写入失败。
#
# 与常驻实例的差异（有意为之，须记录）：
#   旧实例命令行把 -Dplatform.metric.publish.export-dir=... 写在 `-jar <jar>` **之后**，
#   那是「应用参数」位置；Spring Boot 只把 `--k=v` 当属性，`-D` 形式不绑定 ⇒ 该参数
#   很可能一直无效，导出目录实际取 `@Value("${platform.metric.publish.export-dir:metric-staging}")`
#   的**相对默认值**（相对 JVM 工作目录）。本脚本改为：① 该属性放在 `-jar` **之前**
#   （JVM 系统属性，绑定无疑义）② 显式指定 WorkingDirectory=仓库根 ⇒ 导出目录确定。
#   （旧实例行为未取证：只能证明 metric-staging 在 09-11 15:12 被写过，不能证明 -D 生效。）
#
# 失败即停（fail-stop）：任何断言失败立即中止，**不自动回滚**（回滚需用户书面确认）。
# 用法：pwsh -File swap-8091.ps1
# =============================================================================

$ErrorActionPreference = 'Stop'
$repo  = 'D:\Develop_code\GraduationProject'
$java  = 'D:\Develop\JAVA17\bin\java.exe'
$mvn   = 'D:\apache-maven-3.9.14\bin\mvn.cmd'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$dump  = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$jar   = Join-Path $repo 'analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar'
$dir   = Join-Path $repo 'docs\acceptance\p1-05-8091-swap-20260911'
$log   = Join-Path $dir 'swap-8091.log'
$base  = 'http://127.0.0.1:8091'

Set-Location $repo
New-Item -ItemType Directory -Force -Path $dir | Out-Null
if (Test-Path $log) { Remove-Item $log -Force }

function Say([string]$m) {
    $line = '{0}  {1}' -f (Get-Date -Format 'HH:mm:ss'), $m
    Write-Host $line
    Add-Content -Path $log -Value $line -Encoding utf8
}
function Fail([string]$m) {
    Say ('*** FAIL-STOP: ' + $m)
    Say '*** 未自动回滚。真库迁移不可逆（V17 已加列/换唯一键），回滚需用户书面确认。'
    throw $m
}
function Sql([string]$q) {
    $out = & $mysql -u root -p123456 -B -e $q 2>&1 | Where-Object { $_ -notmatch 'Using a password' }
    return $out
}

Say '================ P1-05 轮 · 原子换血开始 ================'
Say ('捕获时刻 = ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$head = (git rev-parse HEAD).Trim()
Say ('HEAD = ' + $head)

# ---------------------------------------------------------------- 0. 前置硬断言
$dirty = @(git status --porcelain -- analytics-server)
if ($dirty.Count -ne 0) { $dirty | ForEach-Object { Say ('  未提交: ' + $_) }; Fail '打包树 != 提交树（analytics-server 下有未提交改动）' }
Say '[G0] analytics-server 工作区干净 ⇒ 打包树 == HEAD 提交树'

$last = Sql "SELECT installed_rank, version, success FROM analytics_meta.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
Say ('[G1] 迁移末条（按 installed_rank）: ' + ($last -join ' | '))
if (($last -join ' ') -notmatch '\b16\b') { Fail '迁移末条不是 V16，真库状态与预期不符，停止' }

# ---------------------------------------------------------------- 1. 换血前现场
$pre = @{}
$c = Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $c) { Fail '8091 无监听，现场与预期不符（本协议假设旧实例在跑）' }
$pre.pid = $c.OwningProcess
$pre.cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($pre.pid)").CommandLine
$pre.jarSha = (Get-FileHash $jar -Algorithm SHA256).Hash
$pre.jarTime = (Get-Item $jar).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
Say ("[1] 旧实例 PID={0} jar_sha256={1} jar_mtime={2}" -f $pre.pid, $pre.jarSha.Substring(0,16), $pre.jarTime)
Say ('    旧命令行: ' + $pre.cmd)
$schemaSnap = Join-Path $dir 'pre-v17-schema.sql'
# 用 mysqldump 自带 --result-file（不经 PowerShell 重定向，避免编码不可控）；保留注释头（含服务器版本与导出时刻，就是快照证据本身）
& $dump -u root -p123456 --no-data --result-file=$schemaSnap analytics_meta 2>&1 | Where-Object { $_ -notmatch 'Using a password' } | ForEach-Object { Say ('    dump: ' + $_) }
Say ('[1] DDL 快照 -> pre-v17-schema.sql（' + (Get-Item (Join-Path $dir 'pre-v17-schema.sql')).Length + ' B）')
$preCounts = Sql "SELECT (SELECT COUNT(*) FROM analytics_meta.pipeline_run) r, (SELECT COUNT(*) FROM analytics_meta.ingestion_batch) b, (SELECT COUNT(*) FROM analytics_meta.file_checkpoint) c, (SELECT COUNT(*) FROM analytics_meta.source_registry) s, (SELECT COUNT(*) FROM analytics_meta.runtime_profile) p, (SELECT COUNT(*) FROM analytics_metric.metric_snapshot) ms;"
Say ('[1] 换血前行数(含活库快照): ' + ($preCounts -join ' | '))

# ---------------------------------------------------------------- 2. 停 8091
Say ('[2] 停止 8091（PID {0}）——仅此一个进程，不碰 8090/8092' -f $pre.pid)
Stop-Process -Id $pre.pid -Force
$t0 = Get-Date
while ((Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue) -and ((Get-Date) - $t0).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 500 }
if (Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue) { Fail '8091 端口 30s 内未释放' }
Say '[2] 端口已释放（jar 文件锁随之解除）'

# ---------------------------------------------------------------- 3. 打包新 jar
$buildLog = Join-Path $dir 'package.log'
Say '[3] mvn -o package -pl platform-app -am -DskipTests（E2 已在同一提交上单独跑过）'
$env:MAVEN_OPTS = '-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
& $mvn -o package -f (Join-Path $repo 'analytics-server\pom.xml') -pl platform-app -am '-DskipTests' *>&1 |
    Tee-Object -FilePath $buildLog | Select-String -Pattern 'BUILD SUCCESS|BUILD FAILURE|ERROR' | Select-Object -First 6 | ForEach-Object { Say ('    ' + $_.Line.Trim()) }
if ($LASTEXITCODE -ne 0) { Fail ('打包失败，见 ' + $buildLog) }
$newSha = (Get-FileHash $jar -Algorithm SHA256).Hash
$newTime = (Get-Item $jar).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
Say ("[3] 新 jar mtime={0} sha256={1} size={2}" -f $newTime, $newSha, (Get-Item $jar).Length)
if ($newSha -eq $pre.jarSha) { Fail '新 jar 与旧 jar 字节相同 ⇒ 未包含 P1-05 改动，停止' }
Say '[3] 新 jar 内含 P1-03/P1-05 端点类检查：'
$hits = @(& 'D:\Develop\JAVA17\bin\jar.exe' tf $jar | Select-String 'SourceRegistryController|SourceRegistryServiceImpl')
Say ('    SourceRegistry* 命中 = ' + $hits.Count)
if ($hits.Count -lt 2) { Fail '新 jar 未包含 P1-03 端点类，停止' }

# ---------------------------------------------------------------- 4. 启新实例（启动即迁移）
$out = Join-Path $dir '8091-stdout.log'
$err = Join-Path $dir '8091-stderr.log'
Say '[4] 启动新实例：导出目录用 JVM 属性（-jar 之前）+ WorkingDirectory=仓库根'
$jvmArgs = @('-Dfile.encoding=UTF-8', ('-Dplatform.metric.publish.export-dir=' + (Join-Path $repo 'metric-staging')), '-jar', $jar)
$proc = Start-Process -FilePath $java -ArgumentList $jvmArgs -WorkingDirectory $repo -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
Say ('[4] 新实例 PID = ' + $proc.Id)

$ready = $false
$t0 = Get-Date
while (((Get-Date) - $t0).TotalSeconds -lt 150) {
    try { $h = Invoke-WebRequest -Uri "$base/api/v1/health" -SkipHttpErrorCheck -TimeoutSec 3; if ([int]$h.StatusCode -eq 200) { $ready = $true; break } } catch { }
    if ($proc.HasExited) { break }
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    Say '    stderr 尾部:'
    if (Test-Path $err) { Get-Content $err -Tail 15 | ForEach-Object { Say ('    | ' + $_) } }
    Fail '新实例 150s 内未就绪'
}
Say ('[4] /api/v1/health -> HTTP 200（启动耗时约 {0:N0}s）' -f ((Get-Date) - $t0).TotalSeconds)

# ---------------------------------------------------------------- 5. 迁库断言
$last2 = Sql "SELECT installed_rank, version, description, success FROM analytics_meta.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
Say ('[5] 迁移末条: ' + ($last2 -join ' | '))
if (($last2 -join ' ') -notmatch '\b17\b') { Fail 'V17 未应用（末条不是 17）' }
$ck = Sql "SELECT checksum FROM analytics_meta.flyway_schema_history WHERE version='17';"
Say ('[5] V17 checksum = ' + ($ck -join ' '))
$src = Sql "SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='file_checkpoint' AND COLUMN_NAME='source_id';"
Say ('[5] file_checkpoint.source_id: ' + ($src -join ' | '))
if (($src -join ' ') -notmatch 'NO') { Fail 'file_checkpoint.source_id 不是 NOT NULL' }
$idx = Sql "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) cols FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='file_checkpoint' GROUP BY INDEX_NAME;"
Say '[5] file_checkpoint 索引:'
$idx | ForEach-Object { Say ('    ' + $_) }
if (($idx -join ' ') -match 'uk_ckpt\b') { Fail '旧唯一键 uk_ckpt 仍存在' }
if (($idx -join ' ') -notmatch 'uk_ckpt_source') { Fail '新唯一键 uk_ckpt_source 缺失' }
$nulls = Sql "SELECT COUNT(*) FROM analytics_meta.file_checkpoint WHERE source_id IS NULL;"
Say ('[5] source_id 为空的行数（应为 0）: ' + ($nulls -join ' '))
if (($nulls -join ' ').Trim() -notmatch '^0$') { Fail '回填后有 source_id 为 NULL 的行' }
$fk = Sql "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='analytics_meta' AND REFERENCED_TABLE_NAME IS NOT NULL AND TABLE_NAME IN ('file_checkpoint','ingestion_batch');"
Say '[5] 新外键:'
$fk | ForEach-Object { Say ('    ' + $_) }
$postCounts = Sql "SELECT (SELECT COUNT(*) FROM analytics_meta.pipeline_run) r, (SELECT COUNT(*) FROM analytics_meta.ingestion_batch) b, (SELECT COUNT(*) FROM analytics_meta.file_checkpoint) c, (SELECT COUNT(*) FROM analytics_meta.source_registry) s, (SELECT COUNT(*) FROM analytics_meta.runtime_profile) p, (SELECT COUNT(*) FROM analytics_metric.metric_snapshot) ms;"
Say ('[5] 换血后行数(含活库快照): ' + ($postCounts -join ' | '))
if (($preCounts -join '|') -ne ($postCounts -join '|')) { Fail '行数发生变化（迁移不应改行数）' }
Say '[5] 行数与换血前逐字一致 ⇒ 迁移未动数据'

# ---------------------------------------------------------------- 6. 端点活体断言（带会话，有区分力）
Say '[6] 带会话探测（未认证 401 无区分力，必须登录）'
$login = Invoke-WebRequest -Uri "$base/api/v1/auth/login" -Method Post -Body (@{ username='admin'; password='admin123' } | ConvertTo-Json -Compress) -ContentType 'application/json; charset=utf-8' -SkipHttpErrorCheck
$token = ($login.Content | ConvertFrom-Json).data.token
if (-not $token) { Fail '登录未拿到 token' }
$r1 = Invoke-WebRequest -Uri "$base/api/v1/sources" -Headers @{ Authorization = "Bearer $token" } -SkipHttpErrorCheck
Say ('[6] GET /api/v1/sources（admin）-> HTTP ' + [int]$r1.StatusCode)
$r2 = Invoke-WebRequest -Uri "$base/api/v1/__no_such_endpoint__" -Headers @{ Authorization = "Bearer $token" } -SkipHttpErrorCheck
Say ('[6] 对照 GET /api/v1/__no_such_endpoint__（admin）-> HTTP ' + [int]$r2.StatusCode + '（用于证明上一条有区分力）')
if ([int]$r1.StatusCode -ne 200) { Fail '换血后 /api/v1/sources 仍不可用' }
if ([int]$r1.StatusCode -eq [int]$r2.StatusCode) { Fail '对照路径同码 ⇒ 本次探测无区分力，结论无效' }

# ---------------------------------------------------------------- 7. 新身份登记
Say '================ 换血完成 ================'
Say ("新实例 PID = {0}" -f $proc.Id)
Say ("新 jar sha256 = {0}" -f $newSha)
Say ("旧 jar sha256 = {0}（{1}）" -f $pre.jarSha, $pre.jarTime)
$summary = [ordered]@{
    capturedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'); head = $head
    oldPid = $pre.pid; oldJarSha256 = $pre.jarSha; oldJarMtime = $pre.jarTime; oldCmd = $pre.cmd
    newPid = $proc.Id; newJarSha256 = $newSha; newJarMtime = $newTime
    newCmd = ($jvmArgs -join ' '); workingDirectory = $repo
    preCounts = ($preCounts -join ' '); postCounts = ($postCounts -join ' ')
    migrationLast = ($last2 -join ' '); v17Checksum = ($ck -join ' ').Trim()
    endpointsProbe = "GET /api/v1/sources -> $([int]$r1.StatusCode); control /api/v1/__no_such_endpoint__ -> $([int]$r2.StatusCode)"
}
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $dir 'swap-summary.json') -Encoding utf8
Say '汇总已写入 swap-summary.json'
