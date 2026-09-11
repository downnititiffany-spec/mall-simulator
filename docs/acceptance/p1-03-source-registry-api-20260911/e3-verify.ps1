# P1-03 E3 断言器：把 e3-acceptance.ps1 的原始场景日志逐条对照期望值。
#
# 为什么要有这个文件：e3-acceptance.ps1 只负责**跑**并把原始 HTTP 状态码/响应体落盘，
# 它本身不断言结局（"跑完了"不等于"全都对"）。本脚本独立地读同一份原始日志，
# 把每一条 DoD/失败码的期望写成断言，任一条不符就 FAIL 并以非 0 退出。
# 它读的是日志里的字面量（人眼看到的同一份证据），因此断言失败无法被日志格式掩盖。
#
# 用法：pwsh -File e3-verify.ps1 -RunLabel run5-final [-Port 8093]
# 产物：raw/e3-06-verify-<RunLabel>.txt（PASS/FAIL 全表）
#
# 断言分两层，别混：
#   不变式（invariant）—— 任何一轮、任何端口都必须成立，例如「ACTIVE runtime_profile 恰好 1 行」
#     「同一目标并发 8 次只产生 1 次真实变更」「每次真实变更都留 SUCCESS 行」。
#   本轮快照（observed）—— 只在某一轮为真，例如审计总行数、SOURCE_ACTIVATE SUCCESS 的行数、
#     收尾时当前源 id。并发交错本身会让这些数字在轮次间不同（实测 run5=116 / smoke=115），
#     所以它们**不作为断言**，只如实记录，避免把"某轮的观测值"冒充成"系统不变量"。
param(
    [string]$RunLabel = 'run5-final',
    [int]$Port = 8093
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$raw  = Join-Path $here 'raw'
$logPath = Join-Path $raw "e3-03-scenarios-$RunLabel.txt"
if (-not (Test-Path $logPath)) { throw "找不到场景日志：$logPath" }
$text = Get-Content -Raw -Encoding UTF8 $logPath

$script:rows = [System.Collections.Generic.List[string]]::new()
$script:failed = 0
$script:checks = 0

function Check([string]$name, [bool]$ok, [string]$evidence) {
    $script:checks++
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    if (-not $ok) { $script:failed++ }
    $script:rows.Add(("  [{0}] {1} | 证据: {2}" -f $tag, $name, $evidence))
}
function Has([string]$pattern) { return [bool]($text -match $pattern) }
function Count([string]$pattern) { return ([regex]::Matches($text, $pattern)).Count }

$script:rows.Add("P1-03 E3 断言报告  run=$RunLabel  日志=$logPath")
$script:rows.Add("日志字符数=$($text.Length)")
$script:rows.Add('')

# ---------------- 实例与鉴权 ----------------
Check '健康检查 200' (Has '== 健康检查通过 /api/v1/health -> HTTP 200') '健康检查行'
Check '登录 200' (Has '\[login\] POST /api/v1/auth/login -> HTTP 200') 'login 行'
Check '无 token -> 401 UNAUTHORIZED' (Has '\[authz-1\][^\r\n]*HTTP 401 code=UNAUTHORIZED') 'authz-1 行'
Check 'operator token -> 403 FORBIDDEN_PERMISSION（权限门是真的）' (Has '\[authz-2\][^\r\n]*HTTP 403 code=FORBIDDEN_PERMISSION') 'authz-2 行'

# ---------------- Flyway / 副本库基线 ----------------
Check '副本库启动即迁移到 V17' (Has 'flyway_max=17 rows=16') 'flyway_max 行'
Check 'V17 已应用' (Has 'v17_applied=1') 'v17_applied 行'
Check '场景前审计基线 92 行' (Has '场景前基线：operation_audit_log=92') '基线行'

# ---------------- S1/S2/S3 list/get/404 ----------------
Check 'S1 list 200' (Has '\[S1\] GET /api/v1/sources -> HTTP 200') 'S1 行'
Check 'S1 种子源恰好 1 行且为当前源' (Has 'rows=1 current_count=1 statuses=1:mock-mall:ACTIVE:current=True') 'S1 明细行'
Check 'S2 get 200' (Has '\[S2\] GET /api/v1/sources/1 -> HTTP 200') 'S2 行'
Check 'S3 不存在 -> 404 SOURCE_NOT_FOUND' ((Has '\[S3\] GET /api/v1/sources/999999 -> HTTP 404') -and (Has 'code=SOURCE_NOT_FOUND http=404')) 'S3 行'

# ---------------- S4 创建与三大失败码 ----------------
Check 'S4 create 200 + DRAFT' ((Has '\[S4\] POST /api/v1/sources -> HTTP 200') -and (Has 'status=DRAFT')) 'S4 行'
Check 'S4c /test 编码不一致 -> ok=false 且 sourceCode 项 false' ((Has '\[S4c\] POST /api/v1/sources/3/test -> HTTP 200') -and (Has 'ok=False items=[^\r\n]*profile_source_code_matches=False')) 'S4c 行'
Check 'S4c 不一致项给出「登记=..文件=..」可定位明细' (Has '画像 sourceCode 与登记不一致：登记=p1-03-probe-mismatch 文件=p1-03-probe-1') 'S4c detail'
Check 'S4c 版本项与文件项各自独立为 true（不再共用一句 detail）' (Has '画像 profileVersion 与登记一致：1\.0') 'S4c 版本项 detail'
Check 'S4d activate 编码不一致 -> 409 SOURCE_PROFILE_INVALID' ((Has '\[S4d\][^\r\n]*HTTP 409') -and (Has 'code=SOURCE_PROFILE_INVALID http=409')) 'S4d 行'
Check 'S5 非法 status -> 400 PARAM_INVALID' ((Has '\[S5\] POST /api/v1/sources -> HTTP 400') -and (Has 'code=PARAM_INVALID http=400')) 'S5 行'
Check 'S6 改 source_code -> 409 SOURCE_CODE_IMMUTABLE' ((Has '\[S6\] PUT /api/v1/sources/2 -> HTTP 409') -and (Has 'code=SOURCE_CODE_IMMUTABLE http=409')) 'S6 行'

# ---------------- S7 更新与路径脱敏 ----------------
Check 'S7 update 200 且改名/改时区生效' ((Has '\[S7\] PUT /api/v1/sources/2 -> HTTP 200') -and (Has 'displayName=P1-03 探针源 A（已改名） timezone=UTC sourceCode=p1-03-probe-1')) 'S7 行'
Check 'S7b PUT 改 status -> 400（status 只能走 activate/pause）' (Has '\[S7b\] PUT /api/v1/sources/2 -> HTTP 400') 'S7b 行'
Check 'S7c 绝对路径 -> 400 且响应不回显绝对路径/文件名' ((Has '\[S7c\] PUT /api/v1/sources/2 -> HTTP 400') -and (Has "裸响应含 'Develop_code'=False；含 'p1-03-probe-1\.v1\.json'=False")) 'S7c 行'
Check '审计 reason 也不回显绝对路径（PARAM_INVALID 行结尾为「已脱敏不回显」）' (Has 'PARAM_INVALID: profile_path 必须是仓库相对路径[^\r\n]*收到的值违反该策略') '审计 100 行'

# ---------------- S8/S9 种子源画像缺失（D-035 §11 预期非缺陷） ----------------
Check 'S8 种子源 /test ok=false 且文件项 false' ((Has '\[S8\] POST /api/v1/sources/1/test -> HTTP 200') -and (Has 'ok=False items=profile_path_policy=True, profile_file_exists=False')) 'S8 行'
Check 'S8 前置未通过的项 applicable=false（没评估 != 通过）' (Has '"name":"profile_source_code_matches","passed":false,"applicable":false,"detail":"前置项未通过，无法评估"') 'S8 items'
Check 'S9 激活种子源 -> 409 SOURCE_PROFILE_INVALID' ((Has '\[S9\][^\r\n]*HTTP 409') -and (Has 'code=SOURCE_PROFILE_INVALID http=409')) 'S9 行'

# ---------------- S10 激活 / 幂等 ----------------
Check 'S10a 探针 A /test 全项 ok=true' ((Has '\[S10a\][^\r\n]*HTTP 200') -and (Has 'ok=True items=profile_path_policy=True, profile_file_exists=True, profile_json_object=True, profile_source_code_matches=True, profile_profile_version_matches=True, profile_required_top_level_keys=True, status_transition_allowed=True')) 'S10a 行'
Check 'S10b activate 200 且当前源切到 A（runtime_profile.source_id=2）' ((Has '\[S10b\][^\r\n]*HTTP 200') -and (Has 'DB: runtime_profile_id=1 status=ACTIVE source_id=2')) 'S10b 行'
Check 'S10c 重复 activate 幂等：200 且审计增量 0' ((Has '\[S10c\][^\r\n]*HTTP 200') -and (Has '审计行增量=0（期望 0）')) 'S10c 行'

# ---------------- S11 SOURCE_IN_USE ----------------
Check 'S11 暂停当前源 -> 409 SOURCE_IN_USE' ((Has '\[S11\][^\r\n]*HTTP 409') -and (Has 'code=SOURCE_IN_USE http=409')) 'S11 行'
Check 'S11 文案点明「源状态未改动」' (Has '该源是当前激活源，暂停前请先切换当前源（源状态未改动）') 'S11 body'

# ---------------- S12 切换当前源 / 暂停非当前源 ----------------
Check 'S12a 建探针 B 200' (Has '\[S12a\] POST /api/v1/sources -> HTTP 200') 'S12a 行'
Check 'S12a2 探针 B /test ok=true' ((Has '\[S12a2\][^\r\n]*HTTP 200') -and (Has 'ok=True')) 'S12a2 行'
Check 'S12b 激活 B 200 且当前源切到 B（source_id=4）' ((Has '\[S12b\][^\r\n]*HTTP 200') -and (Has 'DB: runtime_profile_id=1 status=ACTIVE source_id=4')) 'S12b 行'
Check 'S12b 未写 hive_database_prefix（源级命名空间接管属 P2，本次未做）' (Has 'hive_database_prefix=<NULL>') 'S12b 行'
Check 'S12c 暂停非当前源 200' (Has '\[S12c\] POST /api/v1/sources/2/pause -> HTTP 200') 'S12c 行'
Check 'S12d list 恰好 1 个当前源（id=4）' ((Has '\[S12d\] GET /api/v1/sources -> HTTP 200') -and (Has 'rows=4 current_count=1 current_id=4')) 'S12d 行'

# ---------------- S13 并发（DoD：并发激活只有一个当前源） ----------------
Check 'S13a 并发 8 次激活同一目标：SUCCESS 审计增量恰好 1' (Has '并发 8 个 activate\(A\)：SUCCESS 审计行增量=1（期望 1：只有一次真实变更）FAILED 增量=0（期望 0）') 'S13a 行'
Check 'S13a 8/8 请求都返回 200（失败者读到的是一致状态，不是报错）' (Has '响应 200 数=8/8') 'S13a 响应行'
Check 'S13a 结束后 ACTIVE runtime_profile 恰好 1 行' (Has 'DB: active_runtime_rows=1') 'S13a DB 行'
Check 'S13b 混合并发 16/16 返回 200' (Has '响应 200 数=16/16') 'S13b 响应行'
Check 'S13b 有真实交错切换（SUCCESS 增量 >= 2，证明不是在测一个假并发）' ((Has '响应 200 数=16/16；SUCCESS 审计行增量=(\d+)') -and ([int]([regex]::Match($text, '响应 200 数=16/16；SUCCESS 审计行增量=(\d+)').Groups[1].Value) -ge 2)) 'S13b 增量行'
Check 'S13b 结束后 ACTIVE runtime_profile 恰好 1 行' ((Count 'DB: active_runtime_rows=1') -ge 2) '两处 DB 行'
Check 'S13c list 复核仍恰好 1 个当前源' ((Has '\[S13c\] GET /api/v1/sources -> HTTP 200') -and (Has 'list 复核：current_count=1 current_id=\d+')) 'S13c 行'

# ---------------- 审计总账 ----------------
# 确定性动作（每次请求的结局与交错无关）用精确值；SOURCE_ACTIVATE 的次数取决于并发交错，
# 只用下界（每个场景至少一次真实变更）——见文件头「两层断言」说明。
$auditBefore = [int]([regex]::Match($text, 'total_before=(\d+) total_after=(\d+)').Groups[1].Value)
$auditAfter  = [int]([regex]::Match($text, 'total_before=(\d+) total_after=(\d+)').Groups[2].Value)
Check '审计总账：本轮每类变更都新增了行（total_after - total_before >= 20）' (($auditAfter -gt $auditBefore) -and (($auditAfter - $auditBefore) -ge 20)) "before=$auditBefore after=$auditAfter 增量=$($auditAfter - $auditBefore)"
Check 'SOURCE_CREATE 精确计数：3 成功（S4/S4b/S12a）+ 1 失败（S5 非法 status）' ((Has 'SOURCE_CREATE SUCCESS n=3') -and (Has 'SOURCE_CREATE FAILED n=1')) 'CREATE 分组行'
Check 'SOURCE_PAUSE 精确计数：1 成功（S12c）+ 1 失败（S11 当前源）' ((Has 'SOURCE_PAUSE SUCCESS n=1') -and (Has 'SOURCE_PAUSE FAILED n=1')) 'PAUSE 分组行'
Check 'SOURCE_UPDATE 精确计数：1 成功（S7）+ 3 失败（S6/S7b/S7c）' ((Has 'SOURCE_UPDATE SUCCESS n=1') -and (Has 'SOURCE_UPDATE FAILED n=3')) 'UPDATE 分组行'
Check 'SOURCE_ACTIVATE：2 次失败（S4d 编码不一致 / S9 种子源无画像）' (Has 'SOURCE_ACTIVATE FAILED n=2') 'ACTIVATE 失败行'
Check 'SOURCE_ACTIVATE：真实变更 >= 11 次，每次一行（S10b + 并发 1 + 交错 9 + S12b 等）' ((Has 'SOURCE_ACTIVATE SUCCESS n=(\d+)') -and ([int]([regex]::Match($text, 'SOURCE_ACTIVATE SUCCESS n=(\d+)').Groups[1].Value) -ge 11)) 'ACTIVATE 成功行'
Check '明细里能同时看到「设为当前源」落到两个不同源（当前源真的被切换过）' ((Has 'SOURCE_ACTIVATE \| SUCCESS \| res=2 \| admin/admin \| 设为当前源 p1-03-probe-1') -and (Has 'SOURCE_ACTIVATE \| SUCCESS \| res=4 \| admin/admin \| 设为当前源 p1-03-probe-2')) '明细行'
Check '明细里能看到「暂停源」成功与「当前源」失败并存的证据' ((Has 'SOURCE_PAUSE \| SUCCESS \| res=2') -and (Has 'SOURCE_PAUSE \| FAILED \| res=2')) 'PAUSE 明细行'

# 摘要通用不变式：所有带 before/after 的 SUCCESS 行 before != after；摘要不含凭据字样
$digestLines = [regex]::Matches($text, '(?m)^\s+(\d+) \| before=([^|]*)\| after=(.+)$')
$digestBad = 0; $digestSame = 0; $digestCount = $digestLines.Count
foreach ($m in $digestLines) {
    $before = $m.Groups[2].Value.Trim(); $after = $m.Groups[3].Value.Trim()
    if ($before -ne '-' -and $before -eq $after) { $digestSame++ }
    if ($after -match '(?i)password|secret|token|credential') { $digestBad++ }
}
Check "审计摘要行数 >= 12（实测 $digestCount）" ($digestCount -ge 12) "摘要行数=$digestCount"
Check '摘要里没有 before==after 的行（改了什么必须看得出来）' ($digestSame -eq 0) "相同行数=$digestSame"
Check '摘要不含任何凭据字样' ($digestBad -eq 0) "含凭据字样行数=$digestBad"
Check '只改 displayName/timezone 的那次更新，before/after 能看出差异（旧摘要看不出，已修）' ((Has 'displayName=P1-03 探针源 A;ingestMode=FILE') -and (Has 'displayName=P1-03 探针源 A（已改名）;ingestMode=FILE')) '摘要 98 行'

# ---------------- 真库未变 ----------------
Check '真库 pipeline_run=38' (Has 'pipeline_run=38') '真库行'
Check '真库 metric_snapshot=8' (Has 'metric_snapshot=8') '真库行'
Check '真库 runtime_profile 仍 1 行且 source_id=1（未被切走）' (Has 'runtime_profile=1 source_id=1') '真库行'
Check '真库 source_registry 仍 1 行' (Has 'source_registry=1') '真库行'
Check '真库 operation_audit_log 仍 92 行' (Has 'operation_audit_log=92') '真库行'

# ---------------- 收尾 ----------------
Check "只用 $Port，收尾后不再监听" (Has "$Port 仍在监听=False") '收尾行'
Check '启动用的不是 java.exe 而是 javaw.exe（无控制台，防环境误杀）' (Has 'exe=javaw\.exe') '启动行'

# ---------------- 本轮快照（不做断言，只如实记录轮次相关的观测值） ----------------
$script:rows.Add('')
$script:rows.Add('-- 本轮快照（observed，非断言：这些数字在并发交错下轮次间会不同）--')
$script:rows.Add("  审计总行数: before=$auditBefore after=$auditAfter 增量=$($auditAfter - $auditBefore)")
foreach ($m in [regex]::Matches($text, '(?m)^\s+(SOURCE_\w+) (SUCCESS|FAILED) n=(\d+)\r?$')) {
    $script:rows.Add("  $($m.Groups[1].Value) $($m.Groups[2].Value) n=$($m.Groups[3].Value)")
}
$lastCurrent = [regex]::Match($text, 'list 复核：current_count=1 current_id=(\d+)').Groups[1].Value
$script:rows.Add("  收尾时当前源 id=$lastCurrent（交错决定，轮次间可能不同）")
$s13bInc = [regex]::Match($text, '响应 200 数=16/16；SUCCESS 审计行增量=(\d+)').Groups[1].Value
$script:rows.Add("  S13b 混合并发真实变更次数=$s13bInc（>=2 即证明存在真实交错）")

# ---------------- 输出 ----------------
$script:rows.Add('')
$script:rows.Add("断言总数=$script:checks  失败=$script:failed")
if ($script:failed -eq 0) { $script:rows.Add('结论：全部通过') } else { $script:rows.Add("结论：有 $($script:failed) 条未通过") }
$out = Join-Path $raw "e3-06-verify-$RunLabel.txt"
$script:rows | Set-Content -Encoding UTF8 $out
$script:rows | ForEach-Object { Write-Host $_ }
Write-Host "报告：$out"
if ($script:failed -gt 0) { exit 1 }
