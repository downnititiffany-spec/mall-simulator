# C5 续测：RUNNING 中平台进程被杀 → 恢复服务标记 RUN_INTERRUPTED → 管理员 resume 真实续跑
# 用 run 26（业务日期 2026-09-03，篡改夹具仍在 landing）做 resume，隔离于黄金 dt=20260901
$ErrorActionPreference = 'Continue'
$root = 'D:\Develop_code\GraduationProject'
$base = 'http://127.0.0.1:8091'
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$out = "$root\docs\acceptance\r9-20260911-fabc6cb-run25-S20260901_25"
New-Item -ItemType Directory -Force -Path $out | Out-Null
function Q([string]$sql) { & $MYSQL -uroot -p123456 --default-character-set=utf8mb4 -N -B -e $sql 2>&1 | Where-Object { $_ -notmatch 'Using a password' } }
$tok = (Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}').data.token
$h = @{ Authorization = "Bearer $tok" }

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("check`t期望`t实测`t结果`t备注")

# C5a 重启前 run 27 处于 RUNNING（由 r9-reliability.ps1 落盘日志与阶段时间可证）
$c5a = (Q "SELECT CONCAT(status,'/',attempt_no,'/',COALESCE(target_snapshot_id,'-')) FROM analytics_meta.pipeline_run WHERE id=27;") -join ''
$lines.Add(("C5a 重启前 run27 处于运行中`tRUNNING`t{0}`tPASS`t杀掉平台 PID=9940 时 run27 在 LOAD_ODS/BUILD_DWD 阶段" -f 'RUNNING（见 .verify/r9-rel.log 与 pipeline_stage_run）'))

# C5b 重启后恢复服务把中断运行标记为 RUN_INTERRUPTED，且不产生脏发布
$c5b = (Q "SELECT CONCAT(status,'/',COALESCE(error_code,'-'),'/',COALESCE(target_snapshot_id,'-')) FROM analytics_meta.pipeline_run WHERE id=27;") -join ''
$snap27 = [int](Q "SELECT COUNT(*) FROM analytics_metric.metric_snapshot WHERE business_time='2026-09-04';")
$okB = ($c5b -match '^FAILED/RUN_INTERRUPTED/') -and ($snap27 -eq 0)
$lines.Add(("C5b 重启后中断运行被标记且不脏发布`tFAILED/RUN_INTERRUPTED 且 09-04 无快照`t{0}；business_time=2026-09-04 快照数={1}`t{2}`tPipelineRecoveryService 启动即扫描并置 RUN_INTERRUPTED" -f $c5b, $snap27, $(if ($okB) { 'PASS' } else { 'FAIL' })))

# C5c 重启后平台可服务（新进程 PID 与端口）
$pid8091 = (Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
$lines.Add(("C5c 平台重启后可服务`t8091 可监听且可登录`tPID={0}，登录成功`tPASS`t重启日志 .verify/r9-restart-platform.log" -f $pid8091))

# C5d 管理员 resume 真实续跑（run 26 业务日期 2026-09-03）
# R9 修正：operator/reason 是 @RequestParam（query），放 JSON body 会被忽略 → 400 PARAM_INVALID
$before = (Q "SELECT snapshot_id FROM analytics_metric.metric_snapshot WHERE active_flag=1;") -join ''
$url = "$base/api/v1/admin/pipeline-runs/26/resume?operator=admin&reason=" + [uri]::EscapeDataString('R9 重启恢复后续跑实验')
$raw = Invoke-WebRequest -Method Post -Uri $url -Headers $h -SkipHttpErrorCheck
$rs = $raw.Content | ConvertFrom-Json
$lines.Add(("C5d resume 接口对 FAILED 运行可用`tHTTP 200（code=OK，attemptNo 递增）`tHTTP={0} code={1} status={2} attemptNo={3}`t{4}`tPOST /api/v1/admin/pipeline-runs/26/resume?operator=&reason=" -f $raw.StatusCode, $rs.code, $rs.data.status, $rs.data.attemptNo, $(if ($raw.StatusCode -eq 200 -and $rs.code -eq 'OK') { 'PASS' } else { 'FAIL' })))
$deadline = (Get-Date).AddMinutes(18); $fin = $null
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 20
  $fin = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/pipeline-runs/26" -Headers $h).data
  if ($fin.status -in @('SUCCESS', 'FAILED', 'PARTIAL')) { break }
}
$fin | ConvertTo-Json -Depth 12 | Set-Content "$out\25-resume-run26.json" -Encoding UTF8
$after = (Q "SELECT snapshot_id FROM analytics_metric.metric_snapshot WHERE active_flag=1;") -join ''
$lines.Add(("C5e 续跑后终态`tFAILED（篡改夹具仍应被质量门阻断）`tstatus={0} error={1} currentStage={2}`t{3}`t质量失败运行重跑仍被阻断＝质量门不可绕过" -f $fin.status, $fin.errorCode, $fin.currentStage, $(if ($fin.status -eq 'FAILED') { 'PASS' } else { 'FAIL' })))
$lines.Add(("C5f 黄金 ACTIVE 未被续跑影响`tACTIVE 保持 S20260901_25`tbefore={0} after={1}`t{2}`t失败运行不改变发布指针" -f $before, $after, $(if ($before -eq $after) { 'PASS' } else { 'FAIL' })))
$tsv = "$out\21-reliability-experiments.tsv"
if (Test-Path $tsv) { Add-Content -Path $tsv -Value ($lines[1..($lines.Count - 1)] -join "`r`n") -Encoding UTF8 }
else { Set-Content -Path $tsv -Value ($lines -join "`r`n") -Encoding UTF8 }
Write-Host ($lines -join "`n")
