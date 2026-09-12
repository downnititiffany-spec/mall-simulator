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
#
# 【F-07 适配，2026-09-12 08:35 实测后改写】隔夜中断导致三个程序全部停止（8090/8091/8092
#   均无监听，PID 16568 已不存在）。因此本脚本不再假设"旧实例在跑"：
#   ① 步骤 1 改为"若 8091 无监听 ⇒ 记录'旧实例已不在运行'，并以 **F-04** 记录的旧 jar 身份
#      （mtime 19:59:25 / 33,089,738 B / sha256 851FADD7…）作对照"，同时**断言磁盘上的 jar
#      仍等于该 sha256**（证明隔夜无人重打包，对照才成立）；
#   ② 步骤 2 改为"若已无监听则跳过停止，仅断言端口空闲"；
#   ③ 新增前置断言：不得存在持有 platform-app jar 的其他 java 进程 / 8093 监听
#      （防止 P1-05 泳道自己的临时实例锁住 jar 导致打包失败）。
#
# 【有意偏差，D-040 第 6 步的"采集写路径打通"**不在本脚本内做**】在真库上跑一次采集会给
#   `ingestion_batch` 增加第 40 行并推进 `file_checkpoint`，即**移动 P1-01 冻结基线**，
#   而该动作属于 P1-06 的 T2 授权范围。故本脚本只做"结构 + 读端点"验收，写路径由
#   P1-05 泳道在副本库上取证 + P1-06 的 T2 在真链上取证。此偏差在输出与汇总 JSON 中显式标明。
#
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
$log   = Join-Path $dir ('swap-8091-run-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$base  = 'http://127.0.0.1:8091'

Set-Location $repo
New-Item -ItemType Directory -Force -Path $dir | Out-Null
# 日志名带运行时刻 ⇒ **永不覆盖**上一次运行的日志。原实现固定叫 swap-8091.log 并在启动时 Remove-Item，
# 于是 run3 把 run2（真正应用 V17 的那一次）的日志删掉了——见 F-12。历史证据不得被脚本自己抹掉。

function Say([string]$m) {
    $line = '{0}  {1}' -f (Get-Date -Format 'HH:mm:ss'), $m
    Write-Host $line
    Add-Content -Path $log -Value $line -Encoding utf8
}
# 阶段感知：V17 由应用**启动时**应用（MetaFlywayInitializer），故失败发生在 [4] 启动之前时，真库其实尚未被改。
# 首轮（09:10:04）失败时本函数只会硬写"真库迁移不可逆（V17 已加列/换唯一键）"，那句话在启动前**不准确**——见 F-12。
$script:migrated = $false
function Fail([string]$m) {
    Say ('*** FAIL-STOP: ' + $m)
    if ($script:migrated) {
        Say '*** 真库**可能已被迁移到 V17**（失败发生在 [4] 启动之后），本脚本不自动回滚；回滚需用户书面确认。'
    } else {
        Say '*** 真库**尚未被迁移**（失败发生在 [4] 启动之前；V17 由应用启动时才应用）⇒ 现场未受影响；本脚本不自动回滚。'
    }
    throw $m
}
function Sql([string]$q) {
    $out = & $mysql -u root -p123456 -B -e $q 2>&1 | Where-Object { $_ -notmatch 'Using a password' }
    return $out
}
# mysql -B **先输出表头**：标量断言若直接拿整个输出比对，'COUNT(*)' 表头会把 '0' 变成 "COUNT(*)\t0"。
# run2（09:11:59）就是这样把"回填后 0 行 NULL"这一**正确结果**误判成失败的——见 F-12。
function SqlVal([string]$q) {
    $o = @(Sql $q) | Where-Object { $_.Trim().Length -gt 0 }
    if ($o.Count -eq 0) { return '' }
    return $o[-1].Trim()
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
# 原实现硬性要求"末条必须是 V16"。V17 一旦由本轮 run2 应用（授权 D-040），该断言就永远无法再通过，
# 于是"换血后复核"这类合法重跑会被自己的前置断言挡在门外。改为模式感知，且两种模式都保持严格判据。
$vStart = SqlVal "SELECT version FROM analytics_meta.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
$sStart = SqlVal "SELECT success FROM analytics_meta.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
if ($vStart -eq '16' -and $sStart -eq '1') {
    $runMode = 'swap'
    Say '[G1] 真库为 V16（success=1）⇒ 本次为**换血运行**：V17 将由新实例启动时应用'
} elseif ($vStart -eq '17' -and $sStart -eq '1') {
    $ckStart = SqlVal "SELECT checksum FROM analytics_meta.flyway_schema_history WHERE version='17';"
    if ($ckStart -ne '-555998778') { Fail ('真库已是 V17 但 checksum=' + $ckStart + ' ≠ -555998778（设计值），停止') }
    $runMode = 'recheck'
    Say ('[G1] 真库已是 V17（success=1、checksum=' + $ckStart + ' 与设计一致）⇒ 本次为**换血后复核运行**：')
    Say '     迁移不会再执行（Flyway 幂等），[5] 的迁移形状/行数断言与 [6] 端点活体断言照跑。'
} else {
    Fail ('迁移末条既不是 V16 也不是成功的 V17（实测 version=' + $vStart + ' success=' + $sStart + '），真库状态与预期不符，停止')
}

# ---------------------------------------------------------------- 0.5 无他人占用 platform-app
# 持有者检查**不能**放在这里：8091 旧实例自己就持有 jar，放在 [2] 停止之前必然误停
# （run3 09:13:35 实测：run2 起的 PID 37500 被误判为"其他占用者"）。完整检查已挪到 [2] 之后，见 F-12。
$l8093 = Get-NetTCPConnection -LocalPort 8093 -State Listen -ErrorAction SilentlyContinue
if ($l8093) { Fail '8093 有监听（疑似泳道临时实例未停），先停它再换血' }
Say '[G2-pre] 8093 空闲（platform-app 持有者检查在 [2] 停止 8091 之后执行）'

# ---------------------------------------------------------------- 1. 换血前现场
$pre = @{}
$c = Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
$pre.jarSha = (Get-FileHash $jar -Algorithm SHA256).Hash
$pre.jarTime = (Get-Item $jar).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
if ($c) {
    $pre.pid = $c.OwningProcess
    $pre.cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($pre.pid)").CommandLine
    Say ("[1] 旧实例在运行：PID={0} jar_sha256={1} jar_mtime={2}" -f $pre.pid, $pre.jarSha.Substring(0,16), $pre.jarTime)
    Say ('    旧命令行（本次实测）: ' + $pre.cmd)
} else {
    # F-07 适配：隔夜中断后旧实例已不存在，改用 F-04 的记录身份作对照（标注来源，不冒充实测）
    $pre.pid = $null
    $pre.cmd = '[F-04 记录，非本次实测] "D:\Develop\JAVA17\bin\java.exe" -Dfile.encoding=UTF-8 -jar D:\Develop_code\GraduationProject\analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar -Dplatform.metric.publish.export-dir=D:\Develop_code\GraduationProject\metric-staging'
    Say '[1] 8091 当前无监听 ⇒ 旧实例已不在运行（F-07：隔夜随机器关闭，PID 16568 不存在）'
    Say ('    对照身份取 F-04 记录：jar_sha256={0} jar_mtime={1}（磁盘实测值）' -f $pre.jarSha.Substring(0,16), $pre.jarTime)
    # 首轮 09:10:04 的打包已把 F-04 那枚旧 jar **覆盖**（字节已不可复得，其身份保留在 F-04 与本目录 run1 日志里）。
    # 因此对照判据必须是"F-04 旧 jar **或** 本轮自己登记过的产物"，否则重跑时会拿自己的产物当外来改写而误停。
    $f04 = '851FADD7944A183576292CD8468596EEF17BDC0A36E3ABD2E8AB108E6A81F2A6'
    $builtFile = Join-Path $dir 'built-jar-history.txt'
    $built = @()
    if (Test-Path $builtFile) { $built = @(Get-Content $builtFile | Where-Object { $_ -match '^[0-9A-Fa-f]{64}' } | ForEach-Object { ($_ -split '\s+')[0].ToUpper() }) }
    $isF04 = ($pre.jarSha -eq $f04)
    if ($isF04) {
        Say '[1] 磁盘 jar 与 F-04 记录逐字节一致 ⇒ 隔夜无人重打包，对照有效'
    } elseif ($built -contains $pre.jarSha.ToUpper()) {
        Say ('[1] 磁盘 jar = **本同一轮内先前一次运行**构建的产物（已登记于 built-jar-history.txt，共 ' + $built.Count + ' 条）⇒ 非第三方改写；F-04 对照已由该次运行的日志完成')
    } else {
        Fail ('磁盘 jar 既不是 F-04 记录的旧 jar，也不是本轮登记过的自建产物（实测 ' + $pre.jarSha + '）⇒ 对照基线失效，需人工确认')
    }
}
$schemaSnap = Join-Path $dir 'pre-v17-schema.sql'
# pre-v17-schema.sql 是**首次运行（真库还是 V16 时）**的 DDL 快照，是"迁移前"的唯一证据 ⇒ 绝不被后续运行覆盖。
# 每次运行另存一份带时刻的快照（复核运行的那份自然是"迁移后"状态）。
$snapNow = Join-Path $dir ('schema-snapshot-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.sql')
$snapTarget = if (Test-Path $schemaSnap) { $snapNow } else { $schemaSnap }
# 用 mysqldump 自带 --result-file（不经 PowerShell 重定向，避免编码不可控）；保留注释头（含服务器版本与导出时刻，就是快照证据本身）
& $dump -u root -p123456 --no-data --result-file=$snapTarget analytics_meta 2>&1 | Where-Object { $_ -notmatch 'Using a password' } | ForEach-Object { Say ('    dump: ' + $_) }
Say ('[1] DDL 快照 -> ' + (Split-Path $snapTarget -Leaf) + '（' + (Get-Item $snapTarget).Length + ' B）')
if ($snapTarget -ne $schemaSnap) { Say ('    pre-v17-schema.sql 保留自首次运行（真库 V16 时），本次快照为运行模式 ' + $runMode + ' 下的现状') }
$preCounts = Sql "SELECT (SELECT COUNT(*) FROM analytics_meta.pipeline_run) r, (SELECT COUNT(*) FROM analytics_meta.ingestion_batch) b, (SELECT COUNT(*) FROM analytics_meta.file_checkpoint) c, (SELECT COUNT(*) FROM analytics_meta.source_registry) s, (SELECT COUNT(*) FROM analytics_meta.runtime_profile) p, (SELECT COUNT(*) FROM analytics_metric.metric_snapshot) ms;"
Say ('[1] 换血前行数(含活库快照): ' + ($preCounts -join ' | '))

# ---------------------------------------------------------------- 2. 停 8091
if ($pre.pid) {
    Say ('[2] 停止 8091（PID {0}）——仅此一个进程，不碰 8090/8092' -f $pre.pid)
    Stop-Process -Id $pre.pid -Force
    $t0 = Get-Date
    while ((Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue) -and ((Get-Date) - $t0).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 500 }
    if (Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue) { Fail '8091 端口 30s 内未释放' }
    Say '[2] 端口已释放（jar 文件锁随之解除）'
} else {
    if (Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue) { Fail '8091 突然出现监听，现场与步骤 1 不符，停止' }
    Say '[2] 换血前 8091 本就无监听（F-07）⇒ 无需停止'
}

# ---------------------------------------------------------------- 2b. 停库之后再断言 jar 无持有者
$others = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match 'platform-app' })
if ($others.Count -ne 0) {
    $others | ForEach-Object { Say ('  残留占用者 PID={0}: {1}' -f $_.ProcessId, $_.CommandLine) }
    Fail '停 8091 之后仍有 java 进程持有 platform-app jar（打包会因文件锁失败），先处理再换血'
}
if (Get-NetTCPConnection -LocalPort 8093 -State Listen -ErrorAction SilentlyContinue) { Fail '8093 出现监听，先停它再换血' }
Say '[G2] 8091 已停（或本就未运行）+ 无任何 platform-app java 进程 + 8093 空闲 ⇒ jar 未被占用，可安全打包'

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
if ($newSha -eq $pre.jarSha) {
    if ($isF04) { Fail '新 jar 与 F-04 记录的旧 jar 字节相同 ⇒ 未包含 P1-05 改动，停止' }
    Say '    新 jar 与上一轮自建产物字节相同 ⇒ 源码未变、构建可复现（重跑的正常结果，非缺陷）'
}
Add-Content -Path (Join-Path $dir 'built-jar-history.txt') -Value ($newSha + '  ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  sha256 size=' + (Get-Item $jar).Length) -Encoding utf8
Say '[3] 新 jar 内容断言（逐字条目名，不再用正则计数）：'
$tf = @(& 'D:\Develop\JAVA17\bin\jar.exe' tf $jar)
foreach ($n in @(
        'BOOT-INF/classes/db/meta/V17__source_dimension_for_checkpoint_and_batch.sql',
        'BOOT-INF/classes/com/graduation/analytics/controller/SourceRegistryController.class',
        'BOOT-INF/lib/connection-ingestion-0.1.0-SNAPSHOT.jar')) {
    if ($tf -notcontains $n) { Fail ('新 jar 缺少条目: ' + $n) }
    Say ('    含 ' + $n)
}
$modJar = Join-Path $repo 'analytics-server\connection-ingestion\target\connection-ingestion-0.1.0-SNAPSHOT.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/lib/connection-ingestion-0.1.0-SNAPSHOT.jar' }
    if (-not $entry) { Fail '内嵌 connection-ingestion jar 未取到，无法做字节同一性断言' }
    $ms = New-Object System.IO.MemoryStream
    $s = $entry.Open(); $s.CopyTo($ms); $s.Dispose()
    $innerSha = ([BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash($ms.ToArray())) -replace '-','')
    $ms.Dispose()
} finally { $zip.Dispose() }
$modSha = (Get-FileHash $modJar -Algorithm SHA256).Hash
Say ('    内嵌 connection-ingestion sha256 = ' + $innerSha.Substring(0,16) + ' / 模块产物 = ' + $modSha.Substring(0,16))
if ($innerSha -ne $modSha) { Fail '随包发布的内嵌 jar 与刚构建的模块产物不一致 ⇒「被测对象=交付物」不成立，停止' }
Say '    内嵌副本与模块产物逐字节相同 ⇒ 随包发布的 P1-05 类就是 E2 测过的那棵树'

# ---------------------------------------------------------------- 4. 启新实例（启动即迁移）
$out = Join-Path $dir '8091-stdout.log'
$err = Join-Path $dir '8091-stderr.log'
Say '[4] 启动新实例：导出目录用 JVM 属性（-jar 之前）+ WorkingDirectory=仓库根'
$jvmArgs = @('-Dfile.encoding=UTF-8', ('-Dplatform.metric.publish.export-dir=' + (Join-Path $repo 'metric-staging')), '-jar', $jar)
$proc = Start-Process -FilePath $java -ArgumentList $jvmArgs -WorkingDirectory $repo -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
$script:migrated = $true   # 从这一刻起，应用可能已在启动过程中应用 V17 ⇒ 之后的失败必须按"可能已迁移"报告
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
# 字段级精确判定：原来只 `-match 'NO'`，任何含 NO 的串都会让它假通过（表头/其他列都可能带）
$srcRow = @($src | Where-Object { $_ -match '^source_id\s' })
if ($srcRow.Count -ne 1) { Fail ('information_schema 里 source_id 列信息不是预期的 1 行（实测 ' + $srcRow.Count + ' 行）') }
$srcCols = $srcRow[0] -split "`t"
if ($srcCols.Count -lt 2 -or $srcCols[1] -ne 'NO') { Fail ('file_checkpoint.source_id 不是 NOT NULL（实测字段: ' + ($srcCols -join '/') + '）') }
$idx = Sql "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) cols FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='file_checkpoint' GROUP BY INDEX_NAME;"
Say '[5] file_checkpoint 索引:'
$idx | ForEach-Object { Say ('    ' + $_) }
if (($idx -join ' ') -match 'uk_ckpt\b') { Fail '旧唯一键 uk_ckpt 仍存在' }
if (($idx -join ' ') -notmatch 'uk_ckpt_source') { Fail '新唯一键 uk_ckpt_source 缺失' }
$nulls = Sql "SELECT COUNT(*) FROM analytics_meta.file_checkpoint WHERE source_id IS NULL;"
$nullCnt = SqlVal "SELECT COUNT(*) FROM analytics_meta.file_checkpoint WHERE source_id IS NULL;"
Say ('[5] source_id 为空的行数（应为 0）: ' + $nullCnt + '   [原始输出: ' + ($nulls -join ' | ') + ']')
if ($nullCnt -ne '0') { Fail ('回填后有 source_id 为 NULL 的行（实测 ' + $nullCnt + '）') }
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
Say '[6] 【有意偏差】D-040 第 6 步的"采集写路径打通"**不在本脚本做**：在真库跑一次采集会写第 40 条'
Say '    ingestion_batch 并推进 file_checkpoint ⇒ 移动 P1-01 冻结基线，属 P1-06 T2 的授权范围。'
Say '    写路径取证 = P1-05 泳道(副本库 E3，断点带 source_id) + P1-06 T2(真链)。'

# ---------------------------------------------------------------- 7. 新身份登记
Say '================ 换血完成 ================'
Say ("新实例 PID = {0}" -f $proc.Id)
Say ("新 jar sha256 = {0}" -f $newSha)
Say ("旧 jar sha256 = {0}（{1}）" -f $pre.jarSha, $pre.jarTime)
$summary = [ordered]@{
    capturedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'); head = $head; runMode = $runMode
    migrationStateAtStart = ($vStart + '/' + $sStart)
    oldPid = $pre.pid; oldJarSha256 = $pre.jarSha; oldJarMtime = $pre.jarTime; oldCmd = $pre.cmd
    newPid = $proc.Id; newJarSha256 = $newSha; newJarMtime = $newTime
    newCmd = ($jvmArgs -join ' '); workingDirectory = $repo
    preCounts = ($preCounts -join ' '); postCounts = ($postCounts -join ' ')
    migrationLast = ($last2 -join ' '); v17Checksum = ($ck -join ' ').Trim()
    endpointsProbe = "GET /api/v1/sources -> $([int]$r1.StatusCode); control /api/v1/__no_such_endpoint__ -> $([int]$r2.StatusCode)"
    oldInstanceAliveAtStart = [bool]$pre.pid
    deviations = 'D-040 第6步"采集写路径打通"未在本脚本执行（会移动 P1-01 冻结基线）；写路径取证改由 P1-05 泳道副本库 E3 + P1-06 T2 真链承担'
    baselineNote = '落地区 55 文件/404,895,418 B（基线 52/404,139,515 B，增量 3 个商城小时文件尚未被采集，见 F-07）'
}
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $dir 'swap-summary.json') -Encoding utf8
Say '汇总已写入 swap-summary.json'
