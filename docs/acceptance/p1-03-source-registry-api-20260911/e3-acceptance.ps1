# P1-03 源登记 API —— E3 真机验收脚本（真 HTTP + 真 MySQL + 真 Flyway）
#
# 前置（由本脚本之外的步骤完成，见 README「复现命令」）：
#   1) 副本库 analytics_meta_p103 / analytics_metric_p103 已由 mysqldump 造好；
#      作用域账号 p103_meta / p103_metric_pub / p103_metric_read 只被授权到副本库，
#      **结构上不可能**写 analytics_meta / analytics_metric（真库只读由权限保证，不靠自觉）。
#   2) D:\p103-e3\classes\<module> 是 6 个模块的最新 target/classes 副本；
#      D:\p103-e3\base\BOOT-INF\lib 是从既有 fat jar 解出的依赖（版本由 pom 冻结）。
#      之所以不用 fat jar 起本实例：8091 正在运行的老实例锁住了
#      analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar（不得停它）。
#   3) 端口固定 8093，绝不触碰 8090/8091/8092（已在运行的三个实例）。
#
# 用法：pwsh -File e3-acceptance.ps1 -RunLabel run5-final [-Port 8093] [-Concurrency 8]
#      RunLabel 会写进产物文件名，保证每一轮真机验收各自留痕、互不覆盖。
# 产物：raw/e3-*-$RunLabel.txt|log（每个场景的原始 HTTP 状态码与响应体逐字落盘，README 的数字全部来自这些文件）

param(
    [int]$Port = 8093,
    [int]$Concurrency = 8,
    [string]$RunLabel = 'run3-final'
)

$ErrorActionPreference = 'Stop'
$repo  = 'D:\Develop_code\GraduationProject'
$raw   = Join-Path $repo 'docs\acceptance\p1-03-source-registry-api-20260911\raw'
$stage = 'D:\p103-e3'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$base  = "http://127.0.0.1:$Port"
New-Item -ItemType Directory -Force -Path $raw | Out-Null

$script:log = [System.Collections.Generic.List[string]]::new()
function Say([string]$line) {
    $script:log.Add($line)
    Write-Host $line
}
function Save([string]$name) {
    $script:log | Set-Content -Encoding UTF8 (Join-Path $raw $name)
}
function Sql([string]$q) {
    & $mysql -uroot -p123456 -N -B --default-character-set=utf8mb4 -e $q 2>&1 | Select-Object -Skip 1
}
# 记一次调用：状态码 + 响应体逐字入日志（禁改写、禁只记结论）
function Call([string]$label, [string]$method, [string]$path, $body) {
    $uri = "$base$path"
    $headers = @{ Authorization = "Bearer $script:token" }
    if ($null -eq $body) {
        $r = Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -SkipHttpErrorCheck
    } else {
        $json = ($body | ConvertTo-Json -Depth 6 -Compress)
        $r = Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -Body $json `
                -ContentType 'application/json; charset=utf-8' -SkipHttpErrorCheck
    }
    $text = [string]$r.Content
    Say ("[{0}] {1} {2} -> HTTP {3}" -f $label, $method, $path, [int]$r.StatusCode)
    Say ("    body: {0}" -f $text)
    $script:last = $r
    return $r
}
function CodeOf($response) {
    try { return ($response.Content | ConvertFrom-Json).code } catch { return '<非 JSON>' }
}
function DataOf($response) {
    return ($response.Content | ConvertFrom-Json).data
}

# ---------------------------------------------------------------- 启动实例
$cpParts = @()
foreach ($m in 'platform-app','connection-ingestion','platform-common','warehouse-pipeline','metric-analysis','ai-decision') {
    $cpParts += (Join-Path $stage "classes\$m")
}
$cpParts += (Join-Path $stage 'base\BOOT-INF\lib\*')
$cp = $cpParts -join ';'

$env:PLATFORM_META_URL          = 'jdbc:mysql://127.0.0.1:3306/analytics_meta_p103?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true'
$env:PLATFORM_META_USER         = 'p103_meta'
$env:PLATFORM_META_PASSWORD     = 'p103_meta_pw_2026'
$env:PLATFORM_METRIC_PUBLISH_URL      = 'jdbc:mysql://127.0.0.1:3306/analytics_metric_p103?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true'
$env:PLATFORM_METRIC_PUBLISH_USER     = 'p103_metric_pub'
$env:PLATFORM_METRIC_PUBLISH_PASSWORD = 'p103_metric_pub_pw_2026'
$env:PLATFORM_METRIC_READ_URL         = 'jdbc:mysql://127.0.0.1:3306/analytics_metric_p103?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true'
$env:PLATFORM_METRIC_READ_USER        = 'p103_metric_read'
$env:PLATFORM_METRIC_READ_PASSWORD    = 'p103_metric_read_pw_2026'

Say ("== 启动实例：port={0} cwd={1} run={2}" -f $Port, $repo, $RunLabel)
$stdout = Join-Path $raw "e3-02-instance-$RunLabel-stdout.log"
$stderr = Join-Path $raw "e3-02-instance-$RunLabel-stderr.log"
# 用 javaw.exe（GUI 子系统，无控制台窗口）而不是 java.exe：本机是多人/多 agent 共用环境，
# 实测过一次实例在启动第 3 秒被"控制台关闭事件"杀掉（exit=-1073741510 = 0xC000013A = STATUS_CONTROL_C_EXIT，
# 无 hs_err 崩溃日志，说明不是 JVM 自身崩溃，而是外部关闭控制台/按进程名清理所致）。
# javaw 不带控制台、镜像名也不同（java.exe vs javaw.exe），这类误伤打不到它；
# stdout/stderr 仍由 Start-Process 显式重定向到文件，启动日志证据不丢。
$javaExe = 'D:\Develop\JAVA17\bin\javaw.exe'
$javaArgs = @(
    '-Dfile.encoding=UTF-8'
    "-Dserver.port=$Port"
    '-Dplatform.spark.job-timeout-ms=60000'
    '-cp', $cp
    'com.graduation.analytics.AnalyticsApplication'
)
$proc = $null
$attempts = 0
while ($attempts -lt 2) {
    $attempts++
    $proc = Start-Process -FilePath $javaExe -ArgumentList $javaArgs `
        -WorkingDirectory $repo -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Say ("   pid={0}（第 {1} 次启动，exe={2}）" -f $proc.Id, $attempts, (Split-Path $javaExe -Leaf))
    Start-Sleep -Seconds 6
    if (-not $proc.HasExited) { break }
    Say ("   !! 第 {1} 次启动的实例在 6s 内退出（exit={0}），重试一次（环境误伤，非被测代码问题）" -f $proc.ExitCode, $attempts)
    $proc = $null
}
if ($null -eq $proc) { throw "实例两次启动都在 6s 内被外部杀掉，放弃本次验收（见 $stderr）" }

try {
    # 等健康检查（最多 120s）
    $ready = $false
    for ($i = 0; $i -lt 240; $i++) {
        Start-Sleep -Milliseconds 500
        if ($proc.HasExited) { throw "实例提前退出（exit=$($proc.ExitCode)），见 $stderr" }
        try {
            $h = Invoke-WebRequest -Uri "$base/api/v1/health" -SkipHttpErrorCheck -TimeoutSec 3
            if ([int]$h.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
    }
    if (-not $ready) { throw '实例 120s 内未就绪' }
    Say '== 健康检查通过 /api/v1/health -> HTTP 200'

    # ------------------------------------------------------------ 登录
    Say '== 登录（demo 账号，无新增鉴权后门）'
    $login = Invoke-WebRequest -Uri "$base/api/v1/auth/login" -Method Post `
        -Body (@{ username = 'admin'; password = 'admin123' } | ConvertTo-Json -Compress) `
        -ContentType 'application/json; charset=utf-8' -SkipHttpErrorCheck
    Say ("[login] POST /api/v1/auth/login -> HTTP {0}" -f [int]$login.StatusCode)
    Say ("    body: {0}" -f (([string]$login.Content) -replace '"token":"[^"]+"', '"token":"<32位UUID，已省略>"'))
    $script:token = (DataOf $login).token
    if (-not $script:token) { throw '登录未拿到 token' }

    # 无 token / 无权限：证明目录权限真的在生效（不是"谁都能调"）
    $noTok = Invoke-WebRequest -Uri "$base/api/v1/sources" -SkipHttpErrorCheck
    Say ("[authz-1] GET /api/v1/sources（无 token）-> HTTP {0} code={1}" -f [int]$noTok.StatusCode, (CodeOf $noTok))
    $opLogin = Invoke-WebRequest -Uri "$base/api/v1/auth/login" -Method Post `
        -Body (@{ username = 'operator'; password = 'operator123' } | ConvertTo-Json -Compress) `
        -ContentType 'application/json; charset=utf-8' -SkipHttpErrorCheck
    $opToken = (DataOf $opLogin).token
    $opGet = Invoke-WebRequest -Uri "$base/api/v1/sources" -Headers @{ Authorization = "Bearer $opToken" } -SkipHttpErrorCheck
    Say ("[authz-2] GET /api/v1/sources（operator token）-> HTTP {0} code={1}" -f [int]$opGet.StatusCode, (CodeOf $opGet))

    # ------------------------------------------------------------ 迁移版本（副本库）
    Say '== 副本库 Flyway 状态（启动即迁移）'
    Say ('    ' + (Sql "SELECT CONCAT('flyway_max=', MAX(CAST(version AS UNSIGNED)), ' rows=', COUNT(*)) FROM analytics_meta_p103.flyway_schema_history;"))
    Say ('    ' + (Sql "SELECT CONCAT('v17_applied=', COUNT(*)) FROM analytics_meta_p103.flyway_schema_history WHERE version='17';"))

    $auditBase = [int](Sql "SELECT COUNT(*) FROM analytics_meta_p103.operation_audit_log;")
    Say ("== 场景前基线：operation_audit_log={0}（副本库）" -f $auditBase)

    # ------------------------------------------------------------ S1..S3 读路径
    Say ''
    Say '---- S1 list ----'
    $s1 = Call 'S1' 'GET' '/api/v1/sources' $null
    $list1 = DataOf $s1
    Say ("    rows={0} current_count={1} statuses={2}" -f @($list1).Count,
        @($list1 | Where-Object { $_.current }).Count,
        (($list1 | ForEach-Object { "$($_.id):$($_.sourceCode):$($_.status):current=$($_.current)" }) -join ' | '))
    $seedId = ($list1 | Where-Object { $_.sourceCode -eq 'mock-mall' }).id

    Say ''
    Say '---- S2 get 存在的源 ----'
    $s2 = Call 'S2' 'GET' "/api/v1/sources/$seedId" $null

    Say ''
    Say '---- S3 get 不存在的源（期望 404 SOURCE_NOT_FOUND）----'
    $s3 = Call 'S3' 'GET' '/api/v1/sources/999999' $null
    Say ("    code={0} http={1}" -f (CodeOf $s3), [int]$s3.StatusCode)

    # ------------------------------------------------------------ S4..S7 写路径
    Say ''
    Say '---- S4 create 默认 DRAFT ----'
    # 源编码必须与画像文件内的 "sourceCode" 逐字一致（p1-03-probe-1.v1.json 里写的是 p1-03-probe-1）：
    # 首次 E3 用 p1-03-probe-a 登记，activate 全部按预期报 SOURCE_PROFILE_INVALID，
    # 成功路径因此一次都没被真机走到 —— 这正是"登记值 vs 文件值"这条校验该有的行为。
    $s4 = Call 'S4' 'POST' '/api/v1/sources' @{
        sourceCode = 'p1-03-probe-1'; displayName = 'P1-03 探针源 A'; ingestMode = 'FILE'
        profilePath = 'analytics-server/source-profiles/p1-03-probe-1.v1.json'
        timezone = 'Asia/Shanghai'; currency = 'CNY'; profileVersion = '1.0'
    }
    $probeA = (DataOf $s4).id
    Say ("    id={0} status={1} current={2}" -f $probeA, (DataOf $s4).status, (DataOf $s4).current)

    Say ''
    Say '---- S4b create 源编码与画像文件内 sourceCode 不一致（期望 200，登记时不做画像校验）----'
    $s4b = Call 'S4b' 'POST' '/api/v1/sources' @{
        sourceCode = 'p1-03-probe-mismatch'; displayName = '编码不一致探针'; ingestMode = 'FILE'
        profilePath = 'analytics-server/source-profiles/p1-03-probe-1.v1.json'
        timezone = 'Asia/Shanghai'; currency = 'CNY'; profileVersion = '1.0'
    }
    $probeM = (DataOf $s4b).id
    Say ("    id={0}（登记不校验画像，校验发生在 test/activate）" -f $probeM)

    Say ''
    Say '---- S4c test 编码不一致的源（期望 ok=false，sourceCode 项 false）----'
    $s4c = Call 'S4c' 'POST' "/api/v1/sources/$probeM/test" $null
    Say ("    ok={0} items={1}" -f (DataOf $s4c).ok,
        (((DataOf $s4c).items | ForEach-Object { "$($_.name)=$($_.passed)" }) -join ', '))

    Say ''
    Say '---- S4d activate 编码不一致的源（期望 409 SOURCE_PROFILE_INVALID）----'
    $s4d = Call 'S4d' 'POST' "/api/v1/sources/$probeM/activate" $null
    Say ("    code={0} http={1}" -f (CodeOf $s4d), [int]$s4d.StatusCode)

    Say ''
    Say '---- S5 create 指定 ACTIVE（期望 400 PARAM_INVALID）----'
    $s5 = Call 'S5' 'POST' '/api/v1/sources' @{
        sourceCode = 'p1-03-probe-active'; displayName = '不该被建出来的源'; ingestMode = 'FILE'
        profilePath = 'analytics-server/source-profiles/p1-03-probe-1.v1.json'
        timezone = 'Asia/Shanghai'; currency = 'CNY'; profileVersion = '1.0'; status = 'ACTIVE'
    }
    Say ("    code={0} http={1}" -f (CodeOf $s5), [int]$s5.StatusCode)

    Say ''
    Say '---- S6 update 改 source_code（期望 409 SOURCE_CODE_IMMUTABLE）----'
    $s6 = Call 'S6' 'PUT' "/api/v1/sources/$probeA" @{ sourceCode = 'p1-03-renamed' }
    Say ("    code={0} http={1}" -f (CodeOf $s6), [int]$s6.StatusCode)

    Say ''
    Say '---- S7 update 其他字段（期望 200 且真的改了）----'
    $s7 = Call 'S7' 'PUT' "/api/v1/sources/$probeA" @{
        displayName = 'P1-03 探针源 A（已改名）'; timezone = 'UTC'; profileVersion = '1.0'
    }
    Say ("    displayName={0} timezone={1} sourceCode={2}" -f (DataOf $s7).displayName, (DataOf $s7).timezone, (DataOf $s7).sourceCode)

    Say ''
    Say '---- S7b update 改 status（期望 400 PARAM_INVALID：状态只能走 activate/pause）----'
    $s7b = Call 'S7b' 'PUT' "/api/v1/sources/$probeA" @{ status = 'PAUSED' }
    Say ("    code={0} http={1}" -f (CodeOf $s7b), [int]$s7b.StatusCode)

    Say ''
    Say '---- S7c update 绝对路径 profile_path（期望 400 PARAM_INVALID，且响应里不回显该绝对路径）----'
    $abs = 'D:\Develop_code\GraduationProject\analytics-server\source-profiles\p1-03-probe-1.v1.json'
    $s7c = Call 'S7c' 'PUT' "/api/v1/sources/$probeA" @{ profilePath = $abs }
    # 注意：JSON 里反斜杠被转义成 \\，所以不能直接搜 'D:\Develop_code'；
    # 首次 E3 就是这么误判成"没泄露"的，改成搜不含反斜杠的片段 + 解析后字段双查。
    $s7cRaw = [string]$s7c.Content
    $s7cMsg = ($s7c.Content | ConvertFrom-Json).message
    Say ("    code={0} http={1}" -f (CodeOf $s7c), [int]$s7c.StatusCode)
    Say ("    裸响应含 'Develop_code'={0}；含 'p1-03-probe-1.v1.json'={1}（两者都期望 False）" -f
        $s7cRaw.Contains('Develop_code'), $s7cRaw.Contains('p1-03-probe-1.v1.json'))
    Say ("    响应是否含凭据字样={0}（期望 False）" -f ($s7cRaw -match 'credential|password|secret'))
    Say ("    脱敏后消息={0}" -f $s7cMsg)

    # ------------------------------------------------------------ S8..S9 种子源（预期失败面）
    Say ''
    Say '---- S8 test 种子源（画像文件未落盘：诚实报缺，不写状态）----'
    $s8 = Call 'S8' 'POST' "/api/v1/sources/$seedId/test" $null
    Say ("    ok={0} items={1}" -f (DataOf $s8).ok,
        (((DataOf $s8).items | ForEach-Object { "$($_.name)=$($_.passed)" }) -join ', '))
    Say ('    DB: ' + (Sql "SELECT CONCAT('seed_status=', status) FROM analytics_meta_p103.source_registry WHERE id=$seedId;"))

    Say ''
    Say '---- S9 activate 种子源（期望 409 SOURCE_PROFILE_INVALID，D-035 §11 预期非缺陷）----'
    $s9 = Call 'S9' 'POST' "/api/v1/sources/$seedId/activate" $null
    Say ("    code={0} http={1}" -f (CodeOf $s9), [int]$s9.StatusCode)

    # ------------------------------------------------------------ S10 test+activate 探针 A
    Say ''
    Say '---- S10a test 探针 A（期望 ok=true）----'
    $s10a = Call 'S10a' 'POST' "/api/v1/sources/$probeA/test" $null
    Say ("    ok={0} items={1}" -f (DataOf $s10a).ok,
        (((DataOf $s10a).items | ForEach-Object { "$($_.name)=$($_.passed)" }) -join ', '))

    Say ''
    Say '---- S10b activate 探针 A（期望 200，成为当前源）----'
    $s10b = Call 'S10b' 'POST' "/api/v1/sources/$probeA/activate" $null
    Say ('    DB: ' + (Sql "SELECT CONCAT('runtime_profile_id=', id, ' status=', status, ' source_id=', source_id) FROM analytics_meta_p103.runtime_profile;"))

    Say ''
    Say '---- S10c activate 探针 A 再来一次（幂等：期望 200 且不新增审计行）----'
    $auditBeforeIdem = [int](Sql "SELECT COUNT(*) FROM analytics_meta_p103.operation_audit_log;")
    $s10c = Call 'S10c' 'POST' "/api/v1/sources/$probeA/activate" $null
    $auditAfterIdem = [int](Sql "SELECT COUNT(*) FROM analytics_meta_p103.operation_audit_log;")
    Say ("    审计行增量={0}（期望 0）" -f ($auditAfterIdem - $auditBeforeIdem))

    # ------------------------------------------------------------ S11 pause 当前源
    Say ''
    Say '---- S11 pause 当前源（期望 409 SOURCE_IN_USE）----'
    $s11 = Call 'S11' 'POST' "/api/v1/sources/$probeA/pause" $null
    Say ("    code={0} http={1}" -f (CodeOf $s11), [int]$s11.StatusCode)

    # ------------------------------------------------------------ S12 第二个源 + 切换 + pause 非当前源
    Say ''
    Say '---- S12a create 探针 B（源编码与画像文件内 sourceCode 一致）----'
    $s12a = Call 'S12a' 'POST' '/api/v1/sources' @{
        sourceCode = 'p1-03-probe-2'; displayName = 'P1-03 探针源 B'; ingestMode = 'FILE'
        profilePath = 'analytics-server/source-profiles/p1-03-probe-2.v1.json'
        timezone = 'Asia/Shanghai'; currency = 'CNY'; profileVersion = '1.0'
    }
    $probeB = (DataOf $s12a).id
    Say ("    id={0} status={1}" -f $probeB, (DataOf $s12a).status)
    $s12a2 = Call 'S12a2' 'POST' "/api/v1/sources/$probeB/test" $null
    Say ("    ok={0}" -f (DataOf $s12a2).ok)

    Say ''
    Say '---- S12b activate 探针 B（当前源 A→B）----'
    $s12b = Call 'S12b' 'POST' "/api/v1/sources/$probeB/activate" $null
    Say ('    DB: ' + (Sql "SELECT CONCAT('runtime_profile_id=', id, ' status=', status, ' source_id=', source_id, ' hive_database_prefix=', IFNULL(hive_database_prefix,'<NULL>')) FROM analytics_meta_p103.runtime_profile;"))
    Say ('    源状态: ' + (Sql "SELECT GROUP_CONCAT(CONCAT(id,':',source_code,':',status) ORDER BY id SEPARATOR ' | ') FROM analytics_meta_p103.source_registry;"))

    Say ''
    Say '---- S12c pause 非当前源 A（期望 200，A 从 ACTIVE→PAUSED）----'
    $s12c = Call 'S12c' 'POST' "/api/v1/sources/$probeA/pause" $null
    Say ('    DB: ' + (Sql "SELECT GROUP_CONCAT(CONCAT(id,':',source_code,':',status) ORDER BY id SEPARATOR ' | ') FROM analytics_meta_p103.source_registry;"))

    Say ''
    Say '---- S12d list 复核 current 唯一性 ----'
    $s12d = Call 'S12d' 'GET' '/api/v1/sources' $null
    $list2 = DataOf $s12d
    Say ("    rows={0} current_count={1} current_id={2}" -f @($list2).Count,
        @($list2 | Where-Object { $_.current }).Count,
        (@($list2 | Where-Object { $_.current }).id -join ','))

    # ------------------------------------------------------------ S13 并发激活
    Say ''
    Say ("---- S13a 并发 {0} 个 activate 同一源（A 当前非当前源：A=PAUSED、当前源=B）----" -f $Concurrency)
    $auditBeforeA = [int](Sql "SELECT COUNT(*) FROM analytics_meta_p103.operation_audit_log WHERE action='SOURCE_ACTIVATE' AND result='SUCCESS';")
    $auditFailBeforeA = [int](Sql "SELECT COUNT(*) FROM analytics_meta_p103.operation_audit_log WHERE action='SOURCE_ACTIVATE' AND result='FAILED';")
    $startAt = (Get-Date).ToUniversalTime().AddSeconds(4).ToString('o')
    $uriA = "$base/api/v1/sources/$probeA/activate"
    $tok = $script:token
    $resA = 1..$Concurrency | ForEach-Object -Parallel {
        $t = [DateTime]::Parse($using:startAt).ToUniversalTime()
        while ([DateTime]::UtcNow -lt $t) { Start-Sleep -Milliseconds 5 }
        try {
            $r = Invoke-WebRequest -Uri $using:uriA -Method Post -Headers @{ Authorization = "Bearer $using:tok" } -SkipHttpErrorCheck
            [pscustomobject]@{ idx = $_; http = [int]$r.StatusCode; body = [string]$r.Content }
        } catch {
            [pscustomobject]@{ idx = $_; http = -1; body = $_.Exception.Message }
        }
    } -ThrottleLimit $Concurrency
    foreach ($row in ($resA | Sort-Object idx)) {
        Say ("    #{0} HTTP {1} {2}" -f $row.idx, $row.http, $row.body)
    }
    $auditAfterA = [int](Sql "SELECT COUNT(*) FROM analytics_meta_p103.operation_audit_log WHERE action='SOURCE_ACTIVATE' AND result='SUCCESS';")
    $auditFailAfterA = [int](Sql "SELECT COUNT(*) FROM analytics_meta_p103.operation_audit_log WHERE action='SOURCE_ACTIVATE' AND result='FAILED';")
    Say ("    并发 {0} 个 activate(A)：SUCCESS 审计行增量={1}（期望 1：只有一次真实变更）FAILED 增量={2}（期望 0）" -f
        $Concurrency, ($auditAfterA - $auditBeforeA), ($auditFailAfterA - $auditFailBeforeA))
    Say ("    响应 200 数={0}/{1}" -f @($resA | Where-Object { $_.http -eq 200 }).Count, $Concurrency)
    Say ('    DB: ' + (Sql "SELECT CONCAT('active_runtime_rows=', COUNT(*)) FROM analytics_meta_p103.runtime_profile WHERE status='ACTIVE';"))
    Say ('    DB: ' + (Sql "SELECT CONCAT('runtime_profile.source_id=', source_id) FROM analytics_meta_p103.runtime_profile WHERE status='ACTIVE';"))
    Say ('    DB: ' + (Sql "SELECT GROUP_CONCAT(CONCAT(id,':',source_code,':',status) ORDER BY id SEPARATOR ' | ') FROM analytics_meta_p103.source_registry;"))

    Say ''
    Say ("---- S13b 并发 {0} 个混合 activate（{1}×A + {1}×B）----" -f ($Concurrency * 2), $Concurrency)
    $uriB = "$base/api/v1/sources/$probeB/activate"
    $startAt2 = (Get-Date).ToUniversalTime().AddSeconds(4).ToString('o')
    $auditBeforeMix = [int](Sql "SELECT COUNT(*) FROM analytics_meta_p103.operation_audit_log WHERE action='SOURCE_ACTIVATE' AND result='SUCCESS';")
    $targets = @()
    for ($i = 1; $i -le $Concurrency; $i++) { $targets += $uriA; $targets += $uriB }
    $resB = $targets | ForEach-Object -Parallel {
        $t = [DateTime]::Parse($using:startAt2).ToUniversalTime()
        while ([DateTime]::UtcNow -lt $t) { Start-Sleep -Milliseconds 5 }
        try {
            $r = Invoke-WebRequest -Uri $_ -Method Post -Headers @{ Authorization = "Bearer $using:tok" } -SkipHttpErrorCheck
            [pscustomobject]@{ uri = ($_ -replace '.*/sources/', ''); http = [int]$r.StatusCode; body = [string]$r.Content }
        } catch {
            [pscustomobject]@{ uri = $_; http = -1; body = $_.Exception.Message }
        }
    } -ThrottleLimit ($Concurrency * 2)
    $resB | ForEach-Object { Say ("    {0} HTTP {1} {2}" -f $_.uri, $_.http, $_.body) }
    $auditAfterMix = [int](Sql "SELECT COUNT(*) FROM analytics_meta_p103.operation_audit_log WHERE action='SOURCE_ACTIVATE' AND result='SUCCESS';")
    Say ("    响应 200 数={0}/{1}；SUCCESS 审计行增量={2}" -f
        @($resB | Where-Object { $_.http -eq 200 }).Count, $targets.Count, ($auditAfterMix - $auditBeforeMix))
    Say '    注：混合并发**不设**审计行数上界——请求交错时 A/B 可能被反复切回，每次都是真实变更、各留一行；'
    Say '        不变式是「ACTIVE runtime_profile 恰好 1 行」，不是「只写一次」。'
    Say ('    DB: ' + (Sql "SELECT CONCAT('active_runtime_rows=', COUNT(*)) FROM analytics_meta_p103.runtime_profile WHERE status='ACTIVE';"))
    Say ('    DB: ' + (Sql "SELECT CONCAT('runtime_profile.source_id=', source_id) FROM analytics_meta_p103.runtime_profile WHERE status='ACTIVE';"))
    $s13c = Call 'S13c' 'GET' '/api/v1/sources' $null
    $list3 = DataOf $s13c
    Say ("    list 复核：current_count={0} current_id={1}" -f @($list3 | Where-Object { $_.current }).Count,
        (@($list3 | Where-Object { $_.current }).id -join ','))

    # ------------------------------------------------------------ 审计总账
    Say ''
    Say '== 审计总账（副本库 operation_audit_log，仅本任务动作码）'
    Say ('    ' + (Sql "SELECT CONCAT('total_before=', $auditBase, ' total_after=', COUNT(*)) FROM analytics_meta_p103.operation_audit_log;"))
    foreach ($row in (Sql "SELECT CONCAT(action, ' ', result, ' n=', COUNT(*)) FROM analytics_meta_p103.operation_audit_log WHERE action LIKE 'SOURCE_%' GROUP BY action, result ORDER BY action, result;")) {
        Say ('    ' + $row)
    }
    Say '    -- 明细（id/action/result/resource_id/reason/actor/时间）--'
    foreach ($row in (Sql "SELECT CONCAT(id, ' | ', action, ' | ', result, ' | res=', IFNULL(resource_id,'-'), ' | ', IFNULL(user_id,'-'), '/', IFNULL(role,'-'), ' | ', LEFT(IFNULL(reason,''), 80), ' | ', created_at) FROM analytics_meta_p103.operation_audit_log WHERE action LIKE 'SOURCE_%' ORDER BY id;")) {
        Say ('    ' + $row)
    }
    Say '    -- before/after 摘要（证明不含凭据）--'
    foreach ($row in (Sql "SELECT CONCAT(id, ' | before=', IFNULL(before_digest,'-'), ' | after=', IFNULL(after_digest,'-')) FROM analytics_meta_p103.operation_audit_log WHERE action LIKE 'SOURCE_%' ORDER BY id;")) {
        Say ('    ' + $row)
    }

    # ------------------------------------------------------------ 真库未变证明（同一会话内再测一次）
    Say ''
    Say '== analytics_meta / analytics_metric（真库）未被本次验收触碰'
    Say ('    ' + (Sql "SELECT CONCAT('pipeline_run=', COUNT(*)) FROM analytics_meta.pipeline_run;"))
    Say ('    ' + (Sql "SELECT CONCAT('metric_snapshot=', COUNT(*)) FROM analytics_metric.metric_snapshot;"))
    Say ('    ' + (Sql "SELECT CONCAT('runtime_profile=', COUNT(*), ' source_id=', MAX(source_id)) FROM analytics_meta.runtime_profile;"))
    Say ('    ' + (Sql "SELECT CONCAT('source_registry=', COUNT(*)) FROM analytics_meta.source_registry;"))
    Say ('    ' + (Sql "SELECT CONCAT('operation_audit_log=', COUNT(*)) FROM analytics_meta.operation_audit_log;"))
}
finally {
    Say ''
    Say ("== 收尾：停止 {0} 实例（不动 8090/8091/8092）" -f $Port)
    $procId = if ($proc) { $proc.Id } else { $null }
    if ($procId -and -not $proc.HasExited) {
        Stop-Process -Id $procId -Force
        Say ("   已停止 pid={0}" -f $procId)
    } else {
        Say ("   进程已退出，pid={0}" -f $procId)
    }
    $left = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    Say ("   {0} 仍在监听={1}" -f $Port, ([bool]$left))
    Save "e3-03-scenarios-$RunLabel.txt"
}
