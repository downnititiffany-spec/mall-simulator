# E3 收口自检：E3-b（指标逐值）/ E3-c（镜像按快照 + Hive 侧）/ E3-f（数仓前后）/ E3-g（落地区时点）
#
# 纪律（陷阱 #27 / #35 / #39）：
#   ① 每条判据都打印「应然值 vs 实际值」；② 判据必须有鉴别力（带正向对照）；
#   ③ 查询失败**不得**退化为读数 0（stderr 非空即记 ERROR）；④ 末尾条数自检。
#
# 只读：不写库、不改文件、不启停服务。落盘由调用方重定向。
param(
  [string]$Repo = 'D:\Develop_code\GraduationProject',
  [string]$Mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$Snapshot = 'S20260901_43',
  [string]$PrevSnapshot = 'S20260901_41',
  [string]$PreState = 'docs\acceptance\t2rerun-golden55-post-ct-20260912\raw\state-pre-20260912-171915.txt',
  [string]$PostFrame = 'docs\acceptance\t2rerun-golden55-post-ct-20260912\raw\state-post-20260912-172840.txt',
  [string]$BaselineJson = 'docs\acceptance\p1-baseline-r39-20260911\raw\api-metrics-overview.json'
)

Set-Location -LiteralPath $Repo
$script:rows = New-Object System.Collections.Generic.List[object]

function Add-Row([string]$id, [string]$what, [string]$expect, [string]$actual, [string]$verdict) {
  $script:rows.Add([pscustomobject]@{ Id = $id; What = $what; Expect = $expect; Actual = $actual; Verdict = $verdict })
  '   [{0,-4}] {1,-10} 应然={2,-28} 实际={3}' -f $verdict, $id, $expect, $actual
}

function Sql([string]$q) {
  $out = & $Mysql -uroot -p123456 -N -B -e $q 2>&1
  $err = @($out | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] -or "$_" -match '^mysql: \[Warning\]' -eq $false -and "$_" -match 'ERROR' })
  $data = @($out | Where-Object { "$_" -notmatch '^mysql: \[Warning\]' -and "$_" -notmatch 'ERROR \d+' })
  if (@($out | Where-Object { "$_" -match 'ERROR \d+' }).Count -gt 0) { throw ('SQL 失败（不得当作 0）：' + ($out -join ' | ') + '  :: ' + $q) }
  return , @($data)
}

function One([string]$q) { $r = Sql $q; if ($r.Count -eq 0) { throw ('SQL 无返回（不得当作 0）：' + $q) } return $r[0].Trim() }

'==== E3 收口自检 ===='
'仓库：' + (Get-Location).Path
'时点：' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
'快照：' + $Snapshot + '（对照 ' + $PrevSnapshot + '）'
''
'---- E3-b：指标逐值（DB 自连接差集，不依赖被验脚本自述）----'
$pairs = One "SELECT COUNT(*) FROM analytics_metric.metric_value a JOIN analytics_metric.metric_value b ON a.metric_code=b.metric_code WHERE a.snapshot_id='$Snapshot' AND b.snapshot_id='$PrevSnapshot';"
Add-Row 'B0' '配对行数（防"零配对=零差异"假绿）' '10' $pairs $(if ($pairs -eq '10') { 'PASS' } else { 'FAIL' })
$diff = One "SELECT COUNT(*) FROM analytics_metric.metric_value a JOIN analytics_metric.metric_value b ON a.metric_code=b.metric_code WHERE a.snapshot_id='$Snapshot' AND b.snapshot_id='$PrevSnapshot' AND (a.metric_value<>b.metric_value OR a.unit<>b.unit OR a.period<>b.period OR a.definition_version<>b.definition_version);"
Add-Row 'B1' '值/单位/期间/版本 四项差集' '0' $diff $(if ($diff -eq '0') { 'PASS' } else { 'FAIL' })
$ctrl = One "SELECT COUNT(*) FROM analytics_metric.metric_value a JOIN analytics_metric.metric_value b ON a.metric_code=b.metric_code WHERE a.snapshot_id='$Snapshot' AND b.snapshot_id='$Snapshot' AND a.metric_value+1<>b.metric_value;"
Add-Row 'B2' '正向对照（+1 扰动应全差）' '10' $ctrl $(if ($ctrl -eq '10') { 'PASS' } else { 'FAIL' })
$mvCount = One "SELECT COUNT(*) FROM analytics_metric.metric_value WHERE snapshot_id='$Snapshot';"
Add-Row 'B3' '快照指标行数' '10' $mvCount $(if ($mvCount -eq '10') { 'PASS' } else { 'FAIL' })

''
'---- E3-b 附：API 现值 vs P1-01 冻结基线（逐值，[decimal] 比较）----'
$tok = (Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8091/api/v1/auth/login' -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 30).data.token
$api = (Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8091/api/v1/metrics/overview' -Headers @{ Authorization = "Bearer $tok" } -TimeoutSec 60).data
$base = (Get-Content -LiteralPath $BaselineJson -Raw | ConvertFrom-Json).data
$bad = 0
foreach ($b in $base) {
  $a = @($api | Where-Object { $_.metricCode -eq $b.metricCode })
  if ($a.Count -ne 1) { $bad++; '      ' + $b.metricCode + ' 在现值中命中 ' + $a.Count + ' 次'; continue }
  if ([decimal]$a[0].value -ne [decimal]$b.value -or $a[0].unit -ne $b.unit -or $a[0].period -ne $b.period -or $a[0].definitionVersion -ne $b.definitionVersion) {
    $bad++; '      ' + $b.metricCode + '：基线 ' + $b.value + '/' + $b.unit + '/' + $b.period + ' vs 现值 ' + $a[0].value + '/' + $a[0].unit + '/' + $a[0].period
  }
}
Add-Row 'B4' ('基线 ' + @($base).Count + ' 项逐值四项比对') '0 处不符' ($bad.ToString() + ' 处不符') $(if ($bad -eq 0) { 'PASS' } else { 'FAIL' })

''
'---- E3-c：镜像按快照（COUNT(*) 且按 snapshot_id 分组；禁止 estimates）----'
$expectMap = [ordered]@{
  ads_active_trend_m = 1; ads_behavior_funnel_m = 4; ads_data_quality_m = 4; ads_hot_product_m = 9
  ads_operation_overview_m = 1; ads_product_conversion_m = 9; ads_sale_trend_m = 1; ads_user_profile_m = 1
}
$mirror = @{}
foreach ($t in $expectMap.Keys) {
  $c = One "SELECT COUNT(*) FROM analytics_metric.$t WHERE snapshot_id='$Snapshot';"
  $mirror[$t] = [int]$c
  Add-Row ('C-' + $t) ('镜像 ' + $t) ($expectMap[$t].ToString()) $c $(if ([int]$c -eq $expectMap[$t]) { 'PASS' } else { 'FAIL' })
}
$sum = ($mirror.Values | Measure-Object -Sum).Sum
Add-Row 'C-sum' '8 表求和' '30' $sum $(if ($sum -eq 30) { 'PASS' } else { 'FAIL' })
$prevSum = 0
foreach ($t in $expectMap.Keys) { $prevSum += [int](One "SELECT COUNT(*) FROM analytics_metric.$t WHERE snapshot_id='$PrevSnapshot';") }
Add-Row 'C-prev' ('上一快照 ' + $PrevSnapshot + ' 8 表求和') '30' $prevSum $(if ($prevSum -eq 30) { 'PASS' } else { 'FAIL' })

''
'---- E3-c 续：Hive 侧（导出 jsonl 行数，按表名对齐 —— 禁按位置比）----'
$expDir = Get-ChildItem -LiteralPath 'metric-staging' -Directory -ErrorAction SilentlyContinue |
  Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName '_export.json') } |
  Where-Object { ((Get-Content -LiteralPath (Join-Path $_.FullName '_export.json') -Raw | ConvertFrom-Json).snapshotId) -eq $Snapshot } |
  Select-Object -First 1
if (-not $expDir) { Add-Row 'C-exp' '导出目录（snapshotId 匹配）' $Snapshot '未找到' 'FAIL' }
else {
  $ej = Get-Content -LiteralPath (Join-Path $expDir.FullName '_export.json') -Raw | ConvertFrom-Json
  Add-Row 'C-exp-total' '_export.json totalRows' '30' $ej.totalRows $(if ([int]$ej.totalRows -eq 30) { 'PASS' } else { 'FAIL' })
  $mismatch = 0
  foreach ($t in $expectMap.Keys) {
    $f = Join-Path $expDir.FullName ($t + '.jsonl')
    if (-not (Test-Path -LiteralPath $f)) { $mismatch++; '      ' + $t + '：jsonl 缺失'; continue }
    $n = @(Get-Content -LiteralPath $f | Where-Object { $_.Trim() -ne '' }).Count
    if ($n -ne $mirror[$t]) { $mismatch++; '      ' + $t + '：Hive 侧 ' + $n + ' vs DB 侧 ' + $mirror[$t] }
  }
  Add-Row 'C-align' '8 表按名对齐（DB vs 导出）' '0 处不符' ($mismatch.ToString() + ' 处不符') $(if ($mismatch -eq 0) { 'PASS' } else { 'FAIL' })
}

''
'---- E3-f：数仓前后（跑前 vs 现静默窗口；逐层文件数/字节）----'
$preTxt = Get-Content -LiteralPath $PreState -Raw
$preTotal = [regex]::Match($preTxt, 'spark-warehouse 合计：(\d+) parquet 文件 / (\d+) B')
Add-Row 'F-pre' '跑前合计（外部文件重读）' '972 文件 / 4977445 B' ($preTotal.Groups[1].Value + ' 文件 / ' + $preTotal.Groups[2].Value + ' B') $(if ($preTotal.Groups[1].Value -eq '972' -and $preTotal.Groups[2].Value -eq '4977445') { 'PASS' } else { 'FAIL' })
$nowFiles = @(Get-ChildItem -LiteralPath 'spark-warehouse' -Recurse -File -Filter '*.parquet' -ErrorAction SilentlyContinue)
$nowBytes = ($nowFiles | Measure-Object -Property Length -Sum).Sum
Add-Row 'F-now' '现值合计（本脚本实测）' '文件数与跑前相同（972）' ($nowFiles.Count.ToString() + ' 文件 / ' + $nowBytes + ' B') $(if ($nowFiles.Count -eq 972) { 'PASS' } else { 'FAIL' })
Add-Row 'F-bytes' '字节逐值相同（E3-f 字面判据）' '4977445 B（与跑前一致）' ($nowBytes.ToString() + ' B') $(if ($nowBytes -eq 4977445) { 'PASS' } else { 'FAIL（已登记红，须逐项解释）' })
$layerNow = [ordered]@{}
Get-ChildItem -LiteralPath 'spark-warehouse' -Directory | ForEach-Object {
  $g = @(Get-ChildItem -LiteralPath $_.FullName -Recurse -File -Filter '*.parquet' -ErrorAction SilentlyContinue)
  $layerNow[$_.Name] = @($g.Count, ($g | Measure-Object -Property Length -Sum).Sum)
}
foreach ($k in @('dw_ads.db', 'dw_dim.db', 'probe_r613.db', 'dw_dwd.db', 'dw_dws.db', 'dw_ods.db')) {
  $preM = [regex]::Match($preTxt, [regex]::Escape($k) + '\s+(\d+) 文件 /\s+(\d+) B')
  $pc = $preM.Groups[1].Value; $pb = $preM.Groups[2].Value
  $nc = $layerNow[$k][0]; $nb = $layerNow[$k][1]
  $same = ($pc -eq "$nc") -and ($pb -eq "$nb")
  Add-Row ('F-' + $k) $k ('跑前 ' + $pc + ' 文件/' + $pb + ' B') ($nc.ToString() + ' 文件/' + $nb + ' B') $(if ($same) { 'PASS' } else { 'DIFF（逐项解释）' })
}
$ods = @(Get-ChildItem -LiteralPath 'spark-warehouse\dw_ods.db' -Recurse -File -Filter '*.parquet' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime)
$span = ($ods | Select-Object -Last 1).LastWriteTime - ($ods | Select-Object -First 1).LastWriteTime
Add-Row 'F-mtime' 'ODS 866 文件写入时间窗（覆盖 vs 追加的判据）' '单一窗口（< 120 s）' ('最早 ' + ($ods | Select-Object -First 1).LastWriteTime.ToString('HH:mm:ss') + ' 最晚 ' + ($ods | Select-Object -Last 1).LastWriteTime.ToString('HH:mm:ss') + ' 跨度 ' + [int]$span.TotalSeconds + ' s') $(if ($span.TotalSeconds -lt 120 -and $ods.Count -eq 866) { 'PASS（单轮写入 ⇒ 覆盖）' } else { 'FAIL' })

''
'---- E3-f 续：17:28:40 那帧是否可作为"跑后"读数 ----'
$postTxt = Get-Content -LiteralPath $PostFrame -Raw
$postTot = [regex]::Match($postTxt, 'spark-warehouse 合计：(\d+) parquet 文件 / (\d+) B')
Add-Row 'F-frame' '17:28:40 帧 vs 前后两帧' '应与 972 一致' ($postTot.Groups[1].Value + ' 文件 / ' + $postTot.Groups[2].Value + ' B') $(if ($postTot.Groups[1].Value -eq '972') { 'PASS' } else { 'FAIL（与前后两帧互斥 ⇒ 不采信为跑后读数）' })

''
'---- E3-g：落地区时点（带时点表述；与跑前逐值闭合）----'
$preEv = [regex]::Match($preTxt, 'landing\\events\s+(\d+) 文件 /\s+(\d+) B')
$preMf = [regex]::Match($preTxt, 'landing\\manifests\s+(\d+) 文件 /\s+(\d+) B')
$ev = @(Get-ChildItem -LiteralPath 'landing\events' -Recurse -File -ErrorAction SilentlyContinue)
$mf = @(Get-ChildItem -LiteralPath 'landing\manifests' -Recurse -File -ErrorAction SilentlyContinue)
$evB = ($ev | Measure-Object -Property Length -Sum).Sum
$mfB = ($mf | Measure-Object -Property Length -Sum).Sum
Add-Row 'G-ev' ('events：跑前 ' + $preEv.Groups[1].Value + '/' + $preEv.Groups[2].Value + ' B → 现在') '+2 文件 / +382278 B（=18430+363848）' ($ev.Count.ToString() + ' 文件 / ' + $evB + ' B / 差 +' + ($ev.Count - [int]$preEv.Groups[1].Value) + ' 文件 +' + ($evB - [int]$preEv.Groups[2].Value) + ' B') $(if (($ev.Count - [int]$preEv.Groups[1].Value) -eq 2 -and ($evB - [int]$preEv.Groups[2].Value) -eq 382278) { 'PASS' } else { 'FAIL' })
Add-Row 'G-mf' ('manifests：跑前 ' + $preMf.Groups[1].Value + '/' + $preMf.Groups[2].Value + ' B → 现在') '+2 文件' ($mf.Count.ToString() + ' 文件 / ' + $mfB + ' B') $(if (($mf.Count - [int]$preMf.Groups[1].Value) -eq 2) { 'PASS' } else { 'FAIL' })

''
'==== 自检汇总 ===='
$p = @($script:rows | Where-Object { $_.Verdict -like 'PASS*' }).Count
$f = @($script:rows | Where-Object { $_.Verdict -like 'FAIL*' }).Count
$d = @($script:rows | Where-Object { $_.Verdict -like 'DIFF*' }).Count
'   判据条数 = ' + $script:rows.Count + '（PASS ' + $p + ' / FAIL ' + $f + ' / DIFF-登记 ' + $d + '）'
'   条数自检：上面逐条打印的行数应等于 ' + $script:rows.Count
if ($f -gt 0) { '   结论：存在 FAIL ⇒ 本脚本 exit 1（红项已在 README 逐项登记，不得改写为通过）' } else { '   结论：全部 PASS' }
exit $(if ($f -gt 0) { 1 } else { 0 })
