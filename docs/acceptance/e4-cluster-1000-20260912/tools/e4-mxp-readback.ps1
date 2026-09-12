# E4 修复后重跑 · mxp 导出物独立回读（不信 mxp 自报，直接从 HDFS 读）
# 用法: pwsh -File .verify/e4-mxp-readback.ps1 -ExportDir hdfs://node01:8020/graduation/export/e4c1000
#
# 【本版修订（2026-09-12，修复前版两处缺陷；前版读数已作废，见 §10 说明）】
#   缺陷 1：清单文件实际名为 `_export.json`，前版过滤条件只认 `manifest|清单|_SUCCESS`
#           ⇒ 把 18 行清单当成数据文件，打印出错误的「数据合计 = 156」。
#   缺陷 2：§3 对照 SQL 用的是 **v1** 的 ADS 表名（ads_gmv_day 等），本命名空间无此表
#           ⇒ 解析失败，只打印出残缺执行计划，没有数。
#   本版：清单按「文件名以 _ 开头」识别；对照 SQL 改用真实的 8 张正式 ADS 表；
#         新增「清单自报 rowCount vs 导出文件实际行数」逐表对账；内存改 lean 参数（陷阱 #26）。
param(
  [string]$ExportDir = 'hdfs://node01:8020/graduation/export/e4c1000',
  [string]$Dt = '20260901'
)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
$repo = 'D:\Develop_code\GraduationProject'
Set-Location $repo
$env:HADOOP_USER_NAME = 'root'
$env:HADOOP_CONF_DIR = Join-Path $repo 'docs\acceptance\m3-cluster-probe-20260912\conf'
$hdfs = 'D:\soft\hadoop\hadoop-3.3.4\bin\hdfs.cmd'

'==== 1) 导出目录清单（递归）===='
$ls = & $hdfs dfs -ls -R $ExportDir 2>&1
$ls | ForEach-Object { '   ' + ([string]$_).Trim() }

'==== 2) 逐文件行数：清单/数据分开计 ===='
$files = @()
foreach ($l in $ls) {
  $s = ([string]$l).Trim()
  if ($s -match '^-' -and $s -match '(\S+)$') { $files += $Matches[1] }
}
$total = 0
$manifestText = ''
$perFile = @{}
foreach ($f in $files) {
  $name = ($f -split '/')[-1]
  $content = @(& $hdfs dfs -cat $f 2>&1 | ForEach-Object { [string]$_ })
  $lines = @($content | Where-Object { $_.Trim() -ne '' })
  if ($name.StartsWith('_')) {
    '   [清单] {0}  {1} 行' -f $name, $lines.Count
    $manifestText = ($lines -join "`n")
  } elseif ($name -like '*.jsonl' -or $name -like '*.json') {
    '   [数据] {0}  {1} 行' -f $name, $lines.Count
    $perFile[$name] = $lines.Count
    $total += $lines.Count
  } else {
    '   [其他] {0}  {1} 行' -f $name, $lines.Count
  }
}
'   ---- 数据文件（*.jsonl）行数合计 = {0}（清单文件不计入）----' -f $total

'==== 3) 与正式 ADS 口径独立对照（本地 spark-sql 只读 → 集群 metastore）===='
$env:JAVA_HOME = 'D:\Develop\JAVA17'
$sql = @"
SELECT 'FORMAL_ADS_TOTAL' AS k, sum(c) AS c FROM (
  SELECT count(*) AS c FROM dw_ads.ads_operation_overview WHERE dt='$Dt'
  UNION ALL SELECT count(*) FROM dw_ads.ads_sale_trend         WHERE dt='$Dt'
  UNION ALL SELECT count(*) FROM dw_ads.ads_behavior_funnel    WHERE dt='$Dt'
  UNION ALL SELECT count(*) FROM dw_ads.ads_active_trend       WHERE dt='$Dt'
  UNION ALL SELECT count(*) FROM dw_ads.ads_hot_product        WHERE dt='$Dt'
  UNION ALL SELECT count(*) FROM dw_ads.ads_product_conversion WHERE dt='$Dt'
  UNION ALL SELECT count(*) FROM dw_ads.ads_user_profile       WHERE dt='$Dt'
  UNION ALL SELECT count(*) FROM dw_ads.ads_data_quality       WHERE dt='$Dt'
) t;
"@
$sqlFile = '.verify\e4-mxp-readback.sql'
[IO.File]::WriteAllText((Join-Path $repo $sqlFile), $sql, [Text.UTF8Encoding]::new($false))
$o = & 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-sql.cmd' --master 'local[1]' `
  --driver-memory '512m' `
  --conf 'spark.sql.shuffle.partitions=1' `
  --conf 'spark.driver.extraJavaOptions=-XX:MaxMetaspaceSize=192m -XX:+UseSerialGC' `
  --conf 'spark.hadoop.hive.metastore.uris=thrift://node01:9083' `
  --conf 'spark.sql.warehouse.dir=hdfs://node01:8020/graduation/warehouse' -f (Join-Path $repo $sqlFile) 2>&1
$hiveTotal = $null
foreach ($l in $o) {
  $s = ([string]$l).Trim()
  if ($s -match '^FORMAL_ADS_TOTAL\s+(\d+)') { $hiveTotal = [int]$Matches[1]; '   ' + $s }
  elseif ($s -match 'FORMAL_ADS_TOTAL') { '   （过程行）' + $s }
}

'==== 3b) 清单自报 vs 导出文件实际行数（逐表）===='
$manifestTotal = $null
$mismatch = 0
if ($manifestText -ne '') {
  try {
    $j = $manifestText | ConvertFrom-Json
    $manifestTotal = [int]$j.totalRows
    '   清单 snapshotId={0} dt={1} source={2} generatedAt={3} totalRows={4}' -f $j.snapshotId, $j.dt, $j.source, $j.generatedAt, $j.totalRows
    $sum = 0
    foreach ($t in $j.tables) {
      $fn = ($t.exportFile -split '/')[-1]
      $actual = if ($perFile.ContainsKey($fn)) { $perFile[$fn] } else { -1 }
      $sum += [int]$t.rowCount
      $flag = if ($actual -eq [int]$t.rowCount) { 'OK' } else { $mismatch++; 'MISMATCH' }
      '   {0,-26} 清单={1,-4} 实际={2,-4} {3}  暂存路径={4}' -f $t.mysqlTable, $t.rowCount, $actual, $flag, (($t.hivePath -split '/')[-3..-1] -join '/')
    }
    '   清单 8 表 rowCount 合计 = {0}（清单 totalRows={1}）' -f $sum, $j.totalRows
  } catch { '   清单 JSON 解析失败: ' + $_.Exception.Message; $mismatch = -1 }
} else { '   （未找到清单文件）' }

'==== 4) 判定（三项必须同时相等）===='
'   ① 导出数据文件行数合计 = {0}' -f $total
'   ② 清单自报 totalRows      = {0}' -f $manifestTotal
'   ③ 独立 Hive 正式 ADS 合计 = {0}' -f $hiveTotal
$ok = ($total -eq $manifestTotal) -and ($total -eq $hiveTotal) -and ($mismatch -eq 0)
'   逐表对账不一致数 = {0}' -f $mismatch
if ($ok) { '   ⇒ 三者相等且逐表一致：mxp 导出覆盖完整、清单可信' }
else { '   ⇒ 存在不一致：需查 mxp 的 includeTables/过滤口径或清单生成逻辑' }
