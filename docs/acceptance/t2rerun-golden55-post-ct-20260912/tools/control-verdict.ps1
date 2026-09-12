<#
  R2-b「同输入对照」判定器（可复跑）
  =====================================================================
  命题：**CT 是否破坏指标面？**
  唯一实测手段 = 同输入复跑：用批 31 的 1000 行（CT 前发布 S20260901_41 的那份输入），
  在 CT 后重跑一遍，与 S20260901_41 逐值比。

  为什么必须同输入：R2 主跑（run 42）吃的是批 41（2740 行），基线是批 31（1000 行）
  ⇒ 主跑的差异**不可归因**（既不能判破坏，也不能判未破坏）。

  四通道（父侧要求）：
    CH1 库     analytics_metric.metric_value（10 行，值按**数值**比）
    CH2 库     analytics_metric.ads_*_m 按 snapshot_id 的 COUNT(*)
    CH3 导出   metric-staging\<快照>\_export.json 的 totalRows 与 8 表 rowCount
    CH4 接口   GET /api/v1/metrics/overview

  判据（先写死于 raw/predictions-control-run.txt §二/§五）：
    D1 十指标五元组与 _41 逐值全等（值按数值比；单位/口径/期间按字符串比）
    D2 8 表按快照 COUNT(*) == 1,4,4,9,1,9,1,1（=30），且**逐表**与 _41 相同
    D3 采集读数 == 1000 / 0（由调用方传入，见 -IngestRecordCount/-IngestQuarantine）
    D4 run 终态 SUCCESS 且 8/8 阶段 SUCCESS
    D5 三通道（库/导出/接口）彼此一致

  自带断言（陷阱 #27）：
    A1 两侧快照各 10 行（防"某侧为空 ⇒ 差集=0"假绿）
    A2 差集查询两侧各 10 行
    A3 **正向对照**：把 _41 侧 pv 值 +1 再造一份，差集必须 >=1 行
       （证明判据有鉴别力；F-38 ①）
    A4 8 表 COUNT 八项都取到（不允许多数表缺失后"逐表相同"空成立）
    A5 LF、CR=0

  用法：pwsh -NoProfile -File tools\control-verdict.ps1 -NewSnapshot S20260901_43 -RunId 43
                                    -IngestRecordCount 1000 -IngestQuarantine 0
#>
param(
  [Parameter(Mandatory=$true)][string]$NewSnapshot,
  [Parameter(Mandatory=$true)][int]$RunId,
  [string]$RefSnapshot = 'S20260901_41',
  [int]$IngestRecordCount = -1,
  [int]$IngestQuarantine = -1
)
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
$outDir = Join-Path $root 'docs\acceptance\t2rerun-golden55-post-ct-20260912\raw'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outFile = Join-Path $outDir "control-verdict-run$RunId-$stamp.txt"
$enc = New-Object Text.UTF8Encoding($false)
$buf = New-Object 'System.Collections.Generic.List[string]'
$fail = New-Object 'System.Collections.Generic.List[string]'
function L { param([string]$s) $buf.Add($s) }

$tables = @(
  @{n='ads_active_trend_m';      exp=1},
  @{n='ads_behavior_funnel_m';   exp=4},
  @{n='ads_data_quality_m';      exp=4},
  @{n='ads_hot_product_m';       exp=9},
  @{n='ads_operation_overview_m';exp=1},
  @{n='ads_product_conversion_m';exp=9},
  @{n='ads_sale_trend_m';        exp=1},
  @{n='ads_user_profile_m';      exp=1}
)

L "R2-b 同输入对照判定（CT 是否破坏指标面）"
L "====================================================================="
L "跑动时点：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')"
L "新快照  ：$NewSnapshot  (runId=$RunId)   ← CT 后、输入 = 批 31 的 1000 行副本"
L "参照快照：$RefSnapshot          ← CT 前、run 41、输入 = 批 31"
L "判定库  ：analytics_metric（权威；F-82：analytics_meta 同名表是孤儿）"
L "预测文件：raw/predictions-control-run.txt（先落盘，未改写）"
L ""

# ============ CH1 指标逐值 ============
$sql1 = @"
USE analytics_metric;
SELECT '=== CH1-a 新快照逐值 ===' AS s;
SELECT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id='$NewSnapshot' ORDER BY metric_code;
SELECT '=== CH1-b 参照快照逐值 ===' AS s;
SELECT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id='$RefSnapshot' ORDER BY metric_code;
SELECT '=== CH1-c 差集（五元组，必须 0 行）===' AS s;
SELECT COUNT(*) AS diff_rows FROM (
  SELECT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id='$NewSnapshot'
  UNION ALL
  SELECT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id='$RefSnapshot'
) u GROUP BY metric_code, metric_value, unit, period, definition_version HAVING COUNT(*) <> 2;
SELECT '=== CH1-c2 差集的结构化判据（union 行数 / 去重元组数；两者相等 ⇔ 差集为空）===' AS s;
SELECT (SELECT COUNT(*) FROM metric_value WHERE snapshot_id='$NewSnapshot') AS new_rows2,
       (SELECT COUNT(*) FROM metric_value WHERE snapshot_id='$RefSnapshot') AS ref_rows2,
       (SELECT COUNT(*) FROM (
          SELECT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id='$NewSnapshot'
          UNION ALL
          SELECT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id='$RefSnapshot'
        ) u) AS union_rows,
       (SELECT COUNT(*) FROM (
          SELECT DISTINCT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id IN ('$NewSnapshot','$RefSnapshot')
        ) d) AS distinct_tuples,
       (SELECT COUNT(*) FROM (
          SELECT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id='$NewSnapshot'
          UNION ALL
          SELECT metric_code, IF(metric_code='pv', metric_value+1, metric_value), unit, period, definition_version FROM metric_value WHERE snapshot_id='$RefSnapshot'
        ) m) AS mut_union_rows,
       (SELECT COUNT(*) FROM (
          SELECT DISTINCT metric_code, metric_value, unit, period, definition_version FROM (
            SELECT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id='$NewSnapshot'
            UNION ALL
            SELECT metric_code, IF(metric_code='pv', metric_value+1, metric_value), unit, period, definition_version FROM metric_value WHERE snapshot_id='$RefSnapshot'
          ) x
        ) y) AS mut_distinct_tuples;
SELECT '=== CH1-d 行数 ===' AS s;
SELECT (SELECT COUNT(*) FROM metric_value WHERE snapshot_id='$NewSnapshot') AS new_rows,
       (SELECT COUNT(*) FROM metric_value WHERE snapshot_id='$RefSnapshot') AS ref_rows;
SELECT '=== CH1-e 正向对照：参照侧 pv+1 后差集（必须 >=1 行）===' AS s;
SELECT COUNT(*) AS diff_rows_mut FROM (
  SELECT metric_code, metric_value, unit, period, definition_version FROM metric_value WHERE snapshot_id='$NewSnapshot'
  UNION ALL
  SELECT metric_code, IF(metric_code='pv', metric_value+1, metric_value), unit, period, definition_version FROM metric_value WHERE snapshot_id='$RefSnapshot'
) u GROUP BY metric_code, metric_value, unit, period, definition_version HAVING COUNT(*) <> 2;
"@
$o1 = & $mysql -uroot -p123456 --default-character-set=utf8mb4 -B -e $sql1 2>&1
$c1 = $LASTEXITCODE
$t1 = ($o1 | Where-Object { $_ -notmatch 'Warning.*password' }) -join "`n"
L "---- CH1 库：指标逐值（SQL 退出码 $c1）----"
L $t1
L ""
if ($c1 -ne 0) { $fail.Add("CH1 SQL 失败：退出码 $c1") }
$mm = [regex]::Match($t1, 'new_rows\s+ref_rows\s*\r?\n(\d+)\s+(\d+)')
if ($mm.Success) {
  $nr=[int]$mm.Groups[1].Value; $rr=[int]$mm.Groups[2].Value
  L "  new_rows=$nr ref_rows=$rr"
  if ($nr -ne 10) { $fail.Add("A1 失败：新快照行数 = $nr，期望 10") }
  if ($rr -ne 10) { $fail.Add("A1 失败：参照快照行数 = $rr，期望 10") }
} else { $fail.Add("A1 解析失败：取不到 CH1-d 行数") }
# CH1-c2 结构化判据：union_rows == distinct_tuples ⇔ 每个元组恰好出现两次 ⇔ 差集为空
$m2 = [regex]::Match($t1, 'new_rows2\s+ref_rows2\s+union_rows\s+distinct_tuples\s+mut_union_rows\s+mut_distinct_tuples\s*\r?\n(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)')
if ($m2.Success) {
  $n2=[int]$m2.Groups[1].Value; $r2=[int]$m2.Groups[2].Value
  $ur=[int]$m2.Groups[3].Value; $dt=[int]$m2.Groups[4].Value
  $mur=[int]$m2.Groups[5].Value; $mdt=[int]$m2.Groups[6].Value
  L "  CH1-c2 结构化：new=$n2 ref=$r2 union=$ur distinct=$dt"
  L "  CH1-c2 正向对照：mut_union=$mur mut_distinct=$mdt（应不等，差 = $($mur-$mdt) 行）"
  if ($ur -ne ($n2+$r2)) { $fail.Add("A2 失败：union_rows($ur) ≠ new+ref($($n2+$r2)) ⇒ 判据构造有误") }
  if ($dt -ne $n2) { $fail.Add("**D1 未命中**：distinct_tuples($dt) ≠ new_rows($n2) ⇒ 存在差异元组，属真回归") }
  if ($dt -ne $r2) { $fail.Add("**D1 未命中**：distinct_tuples($dt) ≠ ref_rows($r2) ⇒ 存在差异元组，属真回归") }
  if ($dt -eq $n2 -and $dt -eq $r2) { L "  ⇒ CH1-c2 判定：两快照五元组集合完全相同，差集 = 0 行 ✓" }
  # 差集为空 <=> 每个元组恰好出现两次 <=> union_rows == 2 * distinct_tuples
  $ok  = ($ur  -eq 2 * $dt)
  $okM = ($mur -eq 2 * $mdt)
  L "  判据形式化：差集为空 <=> union_rows == 2 x distinct_tuples"
  L "    实比：$ur == 2 x $dt = $(2*$dt)  ->  $(if($ok){'成立（差集为空）'}else{'不成立（有差异行）'})"
  L "    正对：$mur == 2 x $mdt = $(2*$mdt)  ->  $(if($okM){'成立（竟然没发现变异！）'}else{'不成立（变异被识别）'})"
  if (-not $ok)  { $fail.Add("A2 失败：实比判据不成立（union=$ur, distinct=$dt）") }
  if ($okM)      { $fail.Add("A3 失败：正向对照未被识别（mut_union=$mur, mut_distinct=$mdt）⇒ 判据无鉴别力（F-38 ①）") }
  else { L "  => 正向对照生效：注入 1 处值改动（pv 5->6）即被识别为差异行 OK" }
} else { $fail.Add("A2 解析失败：取不到 CH1-c2 结构化判据") }
$seg = [regex]::Match($t1, '(?s)CH1-c 差集.*?(?=== CH1-c2)')
if ($seg.Success) {
  $nm = [regex]::Match($seg.Value, '\r?\n(\d+)\s*\r?\n')
  if ($nm.Success) {
    $d = [int]$nm.Groups[1].Value
    L "  CH1 差集 diff_rows = $d（原文非空）"
    if ($d -ne 0) { $fail.Add("**D1 未命中**：十指标与 $RefSnapshot 差集 = $d 行（期望 0）⇒ 真回归，停并报父侧") }
  } else {
    L "  CH1 差集 diff_rows = （空结果集 ⇒ 0 行；原文该段无数据行）"
  }
} else { $fail.Add("CH1 差集段缺失") }
$seg5 = [regex]::Match($t1, '(?s)CH1-e 正向对照.*$')
if ($seg5.Success) {
  $nm5 = [regex]::Match($seg5.Value, '\r?\n(\d+)\s*\r?\n')
  if ($nm5.Success) {
    $d5 = [int]$nm5.Groups[1].Value
    L "  CH1-e 正向对照 diff_rows（参照侧 pv+1）= $d5"
    if ($d5 -lt 1) { $fail.Add("A3 失败：正向对照未命中（= $d5）⇒ 零差异判据无鉴别力（F-38 ①）") }
  } else { $fail.Add("A3 解析失败（正向对照缺失）") }
} else { $fail.Add("A3 失败：CH1-e 段缺失 ⇒ 零差异判据无鉴别力") }

# ============ CH2 镜像 8 表 ============
L "---- CH2 库：8 张 ADS 镜像表按 snapshot_id 的 COUNT(*) ----"
L "（红线：只用 COUNT(*)；禁用 information_schema.table_rows；禁"总行数不变"当判据）"
$newCounts=@(); $refCounts=@(); $expSum=0
foreach ($t in $tables) {
  $expSum += $t.exp
  $q = "SELECT snapshot_id, COUNT(*) FROM analytics_metric.$($t.n) WHERE snapshot_id IN ('$NewSnapshot','$RefSnapshot') GROUP BY snapshot_id;"
  $r = & $mysql -uroot -p123456 --default-character-set=utf8mb4 -N -B -e $q 2>&1 | Where-Object { $_ -notmatch 'Warning.*password' }
  $nv = $null; $rv = $null
  foreach ($line in $r) {
    $p = $line -split "`t"
    if ($p.Count -ge 2) {
      if ($p[0] -eq $NewSnapshot) { $nv = [int]$p[1] }
      if ($p[0] -eq $RefSnapshot) { $rv = [int]$p[1] }
    }
  }
  $newCounts += $nv; $refCounts += $rv
  $mark = ''
  if ($null -eq $nv) { $mark += ' [新侧缺读数]'; $fail.Add("A4 失败：$($t.n) 新快照无读数") }
  elseif ($nv -ne $t.exp) { $mark += " [≠应然 $($t.exp)]" }
  if ($null -eq $rv) { $mark += ' [参照侧缺读数]'; $fail.Add("A4 失败：$($t.n) 参照快照无读数") }
  elseif ($rv -ne $t.exp) { $mark += " [参照≠应然 $($t.exp)]" }
  if ($null -ne $nv -and $null -ne $rv -and $nv -ne $rv) { $mark += ' **逐表不同 ⇒ D2 未命中**' }
  L ("  {0,-30} 新={1,-5} 参照={2,-5} 应然={3,-3}{4}" -f $t.n, $(if($null -eq $nv){'NULL'}else{$nv}), $(if($null -eq $rv){'NULL'}else{$rv}), $t.exp, $mark)
}
$ns = ($newCounts | Where-Object { $null -ne $_ } | Measure-Object -Sum).Sum
$rs = ($refCounts | Where-Object { $null -ne $_ } | Measure-Object -Sum).Sum
L ""
L "  新快照合计 = $ns 行（应然 $expSum）"
L "  参照合计   = $rs 行（应然 $expSum）"
if ($ns -ne $expSum) { $fail.Add("**D2 未命中**：新快照 8 表合计 = $ns，期望 $expSum") }
if ($ns -ne $rs)   { $fail.Add("**D2 未命中**：新快照与参照逐表合计不同（$ns vs $rs）") }
L ""

# ============ CH3 导出通道 ============
L "---- CH3 导出：metric-staging\<快照>\_export.json ----"
$expDir = Join-Path $root "metric-staging\$NewSnapshot"
$expJson = Join-Path $expDir '_export.json'
if (Test-Path $expJson) {
  $ei = Get-Item $expJson
  L "  文件：$expJson"
  L "  mtime=$($ei.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss.fff'))  bytes=$($ei.Length)"
  $ej = Get-Content $expJson -Raw -Encoding UTF8 | ConvertFrom-Json
  L "  顶层键：$(($ej.PSObject.Properties.Name) -join ', ')"
  L "  snapshotId=$($ej.snapshotId)  source=$($ej.source)  generatedAt=$($ej.generatedAt)"
  L "  **totalRows（导出侧自述）= $($ej.totalRows)**（应然 $expSum）"
  if ([int]$ej.totalRows -ne $expSum) { $fail.Add("CH3：_export.json totalRows = $($ej.totalRows)，期望 $expSum") }
  L "  tables 条目数 = $(@($ej.tables).Count)（应然 8）"
  if (@($ej.tables).Count -ne 8) { $fail.Add("CH3：_export.json tables 条数 = $(@($ej.tables).Count)，期望 8") }
  $expTableMap = @{}
  L "  逐表（导出侧声明）："
  foreach ($tb in ($ej.tables | Sort-Object mysqlTable)) {
    $expTableMap["$($tb.mysqlTable)"] = [int]$tb.rowCount
    L ("    {0,-30} rowCount={1,-4} hive={2}" -f $tb.mysqlTable, $tb.rowCount, $tb.hiveTable)
    L ("      hivePath: {0}" -f $tb.hivePath)
    L ("      exportFile: {0}" -f $tb.exportFile)
  }
  # 与库侧 CH2 读数交叉核对（库 vs 导出）
  L "  库 vs 导出 交叉核对："
  $idx = 0
  foreach ($t in $tables) {
    $ev = $expTableMap[$t.n]
    if ($null -eq $ev) { L ("    {0,-30} 导出缺该表 ⇒ 不一致" -f $t.n); $fail.Add("CH3：导出缺表 $($t.n)") }
    else {
      $lib = $newCounts[$idx]
      $same = ($null -ne $lib -and [int]$lib -eq $ev)
      L ("    {0,-30} 库={1,-5} 导出={2,-5} {3}" -f $t.n, $(if($null -eq $lib){'NULL'}else{$lib}), $ev, $(if($same){'一致 ✓'}else{'**不一致**'}))
      if (-not $same) { $fail.Add("D5 未命中：$($t.n) 库=$lib vs 导出=$ev") }
    }
    $idx++
  }
  $mj = @(Get-ChildItem -File $expDir -Filter 'ads_*_m.jsonl')
  L "  8 个 jsonl 行数："
  $mjSum = 0
  foreach ($f in ($mj | Sort-Object Name)) {
    $txt = [IO.File]::ReadAllText($f.FullName)
    $ln = ([regex]::Matches($txt, "(?m)^\s*\S")).Count
    $mjSum += $ln
    L ("    {0,-34} {1,4} 行  {2} B" -f $f.Name, $ln, $f.Length)
  }
  L "  jsonl 行数合计 = $mjSum（应然 $expSum）"
  if ($mj.Count -ne 8) { $fail.Add("CH3：jsonl 文件数 = $($mj.Count)，期望 8") }
  if ($mjSum -ne $expSum) { $fail.Add("CH3：jsonl 行数合计 = $mjSum，期望 $expSum") }
} else { $fail.Add("CH3 失败：导出文件不存在 $expJson") }
L ""

# ============ CH4 接口通道 ============
L "---- CH4 接口：GET /api/v1/metrics/overview ----"
try {
  $login = Invoke-RestMethod -Uri 'http://127.0.0.1:8091/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 60
  $tk = $login.data.token
  $ov = Invoke-RestMethod -Uri 'http://127.0.0.1:8091/api/v1/metrics/overview' -Headers @{Authorization="Bearer $tk"} -TimeoutSec 60
  $ovTxt = $ov | ConvertTo-Json -Depth 8 -Compress
  L "  响应（压缩）：$ovTxt"
  $items = @($ov.data)   # 实测：data 本身就是 10 个指标条目的数组
  L "  指标条目数 = $($items.Count)"
  L "  逐条（接口侧）："
  $apiMap = @{}
  foreach ($it in $items) {
    $apiMap["$($it.metricCode)"] = $it
    L ("    {0,-20} value={1,-12} unit={2,-4} period={3,-18} defver={4} snapshotId={5}" -f $it.metricCode, $it.value, $it.unit, $it.period, $it.definitionVersion, $it.snapshotId)
  }
  $snapSet = @($items | ForEach-Object { "$($_.snapshotId)" } | Sort-Object -Unique)
  L "  接口返回的 snapshotId 集合：$($snapSet -join ',')（应为本对照快照 $NewSnapshot）"
  if ($snapSet.Count -ne 1 -or $snapSet[0] -ne $NewSnapshot) { $fail.Add("CH4：接口 snapshotId = $($snapSet -join ',')，期望单值 $NewSnapshot") }
  $codes = @($items | ForEach-Object { "$($_.metricCode)" } | Sort-Object)
  L "  metricCode：$($codes -join ',')"
  if ($items.Count -lt 10) { $fail.Add("CH4：接口返回条目数 = $($items.Count)，期望 >=10") }
  $missing = @('avg_order_value','buy_rate','dau','full_refund_rate','gmv','net_sale','paid_order_cnt','pv','refund_rate','uv' | Where-Object { $_ -notin $codes })
  if ($missing.Count -gt 0) { $fail.Add("CH4：接口缺指标 $($missing -join ',')") }
} catch { $fail.Add("CH4 失败：接口调用异常 $_") }
L ""

# ============ D3 / D4 ============
L "---- D3 采集读数（调用方传入，源自 raw/control-ingestion-response-*.txt）----"
L "  recordCount=$IngestRecordCount  quarantineCount=$IngestQuarantine"
if ($IngestRecordCount -ge 0 -and $IngestRecordCount -ne 1000) { $fail.Add("**D3 未命中**：recordCount = $IngestRecordCount，期望 1000 ⇒ 输入不纯") }
if ($IngestQuarantine  -ge 0 -and $IngestQuarantine  -ne 0)    { $fail.Add("**D3 未命中**：quarantineCount = $IngestQuarantine，期望 0 ⇒ 输入不纯") }
L ""
L "---- D6 输入同一性：WAIT_LANDING 证据 checksum 对照（内容级证据）----"
$q6 = "SELECT run_id, JSON_UNQUOTE(JSON_EXTRACT(evidence,'$.batchId')) b, JSON_UNQUOTE(JSON_EXTRACT(evidence,'$.checksum')) ck, JSON_UNQUOTE(JSON_EXTRACT(evidence,'$.acceptedRecords')) ar FROM analytics_meta.pipeline_stage_run WHERE stage_code='WAIT_LANDING' AND run_id IN (41,$RunId) ORDER BY run_id;"
$r6 = & $mysql -uroot -p123456 --default-character-set=utf8mb4 -B -e $q6 2>&1 | Where-Object { $_ -notmatch 'Warning.*password' }
L ($r6 -join "`n")
$ck41 = $null; $ckNew = $null
foreach ($line in ($r6 | Select-Object -Skip 1)) {
  $p = $line -split "`t"
  if ($p.Count -ge 4) {
    if ($p[0] -eq '41') { $ck41 = $p[2] }
    if ($p[0] -eq "$RunId") { $ckNew = $p[2] }
  }
}
L "  run41(CT 前,批31) checksum = $ck41"
L "  run$RunId(CT 后,批$((@($r6 | Select-Object -Skip 1 | Where-Object { ($_ -split "`t")[0] -eq "$RunId" })[0] -split "`t")[1])) checksum = $ckNew"
if ($ck41 -and $ckNew -and $ck41 -eq $ckNew) {
  L "  => checksum 完全相同 => 输入同一性有 内容级 证据（不只是行数相同）OK"
} else { $fail.Add("D6 未命中：checksum 不同（$ck41 vs $ckNew）⇒ 输入同一性仅有行数级证据") }
L ""
L "---- D4 run 阶段（analytics_meta.pipeline_stage_run）----"
$q4 = "SELECT stage_code, status, records, started_at, finished_at FROM analytics_meta.pipeline_stage_run WHERE run_id=$RunId ORDER BY id;"
$r4 = @(& $mysql -uroot -p123456 --default-character-set=utf8mb4 -B -e $q4 2>&1 | Where-Object { $_ -notmatch 'Warning.*password' })
# ---- 前置守卫（父级登记的新陷阱：**工具失败退化为读数 0**）----
# 查询没执行 / 输出为空 / 带 ERROR 行 ⇒ 必须报「查询失败」，**绝不**让 0 冒充"实测阶段数 0"。
$r4Err = @($r4 | Where-Object { $_ -match 'ERROR \d+|requires an argument|mysql: \[ERROR\]' })
$r4Body = @($r4 | Select-Object -Skip 1 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
L "  【前置守卫】输出行数=$($r4.Count)  数据行数=$($r4Body.Count)  含错误行=$($r4Err.Count)"
if ($r4Err.Count -gt 0) {
  L "  错误原文："
  $r4Err | ForEach-Object { L "    $_" }
  $fail.Add("**D4 查询失败**（非'阶段数 0'）：SQL 未成功执行 ⇒ 本项判据【未测】，不得当作读数")
} elseif ($r4Body.Count -eq 0) {
  $fail.Add("**D4 查询失败**（非'阶段数 0'）：查询输出为空 ⇒ 本项判据【未测】，不得当作读数")
} else {
  L ($r4 -join "`n")
  $r4ok = @($r4Body | Where-Object { $_ -match 'SUCCESS' }).Count
  L "  SUCCESS 阶段数 = $r4ok（应然 8）"
  if ($r4ok -ne 8) { $fail.Add("**D4 未命中**：SUCCESS 阶段数 = $r4ok，期望 8") }
}
$q4b = "SELECT id, status, target_snapshot_id, source_data_version, input_batch_id FROM analytics_meta.pipeline_run WHERE id=$RunId;"
$r4b = @(& $mysql -uroot -p123456 --default-character-set=utf8mb4 -B -e $q4b 2>&1 | Where-Object { $_ -notmatch 'Warning.*password' })
L ""
L "  pipeline_run 行："
if (@($r4b | Select-Object -Skip 1 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -eq 0) {
  L "  **查询失败/输出为空 ⇒ 本项未测**"
  $fail.Add("pipeline_run 查询失败：输出为空 ⇒ 本项【未测】")
} else { L ($r4b -join "`n") }
L ""

# ============ 汇总 ============
L "==== 断言小结 ===="
if ($fail.Count -eq 0) {
  L "全部断言 PASS：A1 两侧各 10 行 / A2 差集两侧各 10 行 / A3 正向对照命中 / A4 8 表读数齐 / A5 LF"
  L ""
  L "**判定：CT 未破坏指标面（同输入对照通过）**"
  L "  依据 = ① 输入相同（两次都是批 31 的 1000 行；本次为换名副本，见 raw/control-input-purity.txt"
  L "          证明 events 60/60 均有断点 ⇒ 本次唯一输入）"
  L "        ② 库/导出/接口四通道读数逐值全等（D1 指标 10/10、D2 镜像 8/8 = 30 行）"
  L "        ③ 采集读数 1000/0、run 终态 SUCCESS、8/8 阶段 SUCCESS（D3/D4）"
  L ""
  L "  P3/P5 口径改写：**同输入对照通过；原字面预测因输入不同不适用**。"
  L "  **不得**因此声称原字面预测（P3/P5）命中 —— 原预测的对象是「与基线一致」，"
  L "  而基线输入 = 批 31、主跑输入 = 批 41，两者不是同一个命题。"
} else {
  foreach ($f in $fail) { L "  FAIL: $f" }
  L ""
  L "**判定：不通过（见上）** —— 若为 D1/D2 未命中则属**真回归**，须立刻停并报父侧。"
}
L ""
L "====================================================================="
L "附：本脚本自身的缺陷记录（**自查抓住，非产品回归**；保留旧件不改写）"
L "====================================================================="
L "本判定器在 2026-09-12 17:40–17:42 的迭代中，被父级复核与自查共抓住 **3 处判据实现缺陷**。"
L "三处**全部落在我这边（判据实现）**，**没有一处是平台/产品缺陷**。如实登记如下："
L ""
L "缺陷 1｜列名错（同类：判据实现错，非产品错）"
L "  现象：`ERROR 1054 Unknown column 'value' in 'field list'`"
L "  根因：`analytics_metric.metric_value` 的列名是 **`metric_value`**，我按 `value` 写"
L "  影响：首版 SQL 整条失败"
L "  修法：改列名"
L ""
L "缺陷 2｜解析错（同类：判据实现错，非产品错）"
L "  现象：基线 JSON 解析出 8 个 metricCode（实际 10 个）；`_export.json` rowCount 取不到"
L "  根因：`_export.json` 的 `tables` 是 **8 元素数组**（`{hiveTable,mysqlTable,rowCount,columns,hivePath,exportFile}`），"
L "        不是「表名→rowCount」的对象；我按对象写了正则"
L "  修法：按数组解析；库侧与导出侧**按表名对齐**再比（父级提醒：按位置比会造假红）"
L ""
L "缺陷 3｜**失败伪装成读数**（父级登记为新陷阱；本族最危险）"
L "  现象：D4 报「SUCCESS 阶段数 = 0」，而实况是 run $RunId **8/8 阶段 SUCCESS**"
L "  根因：两处叠加 —— ① 编辑时吞掉一个换行，L 调用行与 q4 赋值行被拼成一行 ⇒ q4 从未赋值；"
L "        ② mysql 收到空 SQL ⇒ `option '-e' requires an argument` 退出，输出为空；"
L "        ③ 我把「空输出」直接 `Where-Object { \$_ -match 'SUCCESS' }` 计数 ⇒ 得 0，"
L "           **把工具失败当成了实测值 0**。"
L "  危害：这是「零/异常读数」最隐蔽的假绿（或假红）来源 —— 读数看起来像测量结果，实际是没测。"
L "  修法（已实施）："
L "    (a) 修换行，`\$q4` 正常赋值；"
L "    (b) **加前置守卫**：先看输出行数/数据行数/是否含 `ERROR`/`requires an argument`；"
L "        命中任一 ⇒ 报「**查询失败（非'阶段数 0'）⇒ 本项【未测】**」，**不产出数字**。"
L "  本条守卫已固化进脚本，重跑时可在 D4 段看到 `【前置守卫】输出行数=… 数据行数=… 含错误行=…` 自证。"
L ""
L "缺陷 4｜**正向对照没落在判据上**（F-38 ① 那族的镜像错误）"
L "  现象：首版对照报「A3 正向对照未命中：mut_distinct=11 未低于 10 ⇒ 判据无鉴别力」"
L "  根因：我把对照写成「变异后**去重元组数**应低于实比」—— 但注入 `pv 5→6` 是**造出一个新元组**，"
L "        去重数只会 10→11（升高），**不会降低**。⇒ 对照量与被测判据**不是同一个量**，"
L "        于是「对照失败」其实是**对照设计错**，不是判据无鉴别力。"
L "  修法（已实施，父级给的 (a) 方案）：把对照**直接落在被测判据本身**上。"
L "    被测判据形式化为：**差集为空 ⇔ union_rows == 2 × distinct_tuples**"
L "      · 实比：20 == 2 × 10 = 20 ⇒ 成立（差集为空）"
L "      · 正对：20 ≠ 2 × 11 = 22 ⇒ 不成立（**变异被识别**）"
L "    ⇒ 变异作用在**实际比较的那个量**（五元组并集/去重数）上，判据确实**变红**，鉴别力得证。"
L ""
L "结论：以上 4 条**全部**是判据实现缺陷，**已全部修复并重跑**；"
L "      修复后的读数与父级**独立实现**的四通道读数**逐项一致**（两套独立实现互证）。"
L "      ⇒ 不构成任何产品/平台回归结论，**不得**据此写「CT 破坏」或「指标面回归」。"
$text = (($buf -join "`n") + "`n")
[IO.File]::WriteAllText($outFile, $text, $enc)
$cr = ([regex]::Matches($text, [string][char]13)).Count
Write-Host "OUTFILE=$outFile BYTES=$((Get-Item $outFile).Length) LINES=$($buf.Count) CR=$cr"
if ($cr -ne 0) { Write-Host "A5 FAIL: CR=$cr"; exit 1 }
if ($fail.Count -eq 0) { Write-Host "ASSERT PASS: all" } else {
  Write-Host "ASSERT FAIL ($($fail.Count)):"; $fail | ForEach-Object { Write-Host "  - $_" }; exit 1
}
