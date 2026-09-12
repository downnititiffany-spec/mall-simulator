# E4 修复轮（F-80 修复后 10 作业重跑）**独立复核**：全部读数都不经作业自报
#   ① yarn application -status 逐条独立终态（与 spark-submit 的 exit code 相互独立）
#   ② 本地 spark-sql 只读客户端 → 集群 thrift://node01:9083 → HDFS 真读：ODS/DWD/DIM/DWS/ADS 行数
#   ③ ODS 的 ingest_batch_id 集合 + 行数 ⇒ 判定重跑是「覆盖」还是「追加」（防重复计数被当成功）
#   ④ mxp 导出产物的 HDFS 独立回读（列目录 + 逐文件行数 + 清单 + 与 ADS 正式分区独立合计比对）
# 内存纪律（实测陷阱 #26）：本机分页文件上限仅 4.2 GB ⇒ 与集群提交链/ Maven 三者不得并发；
#   spark-sql 一律用 lean 参数（local[1] / 512m / 单分区 / 限 metaspace / SerialGC）。
param(
  [string]$RawDir = 'docs\acceptance\e4-cluster-1000-20260912\raw-fix',
  [string]$AppPrefix = 'application_1789195359269_',
  [int]$AppBase = 15
)
$repo = 'D:\Develop_code\GraduationProject'
Set-Location $repo
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
$out = Join-Path $repo $RawDir
New-Item -ItemType Directory -Force -Path $out | Out-Null

$env:HADOOP_CONF_DIR = Join-Path $repo 'docs\acceptance\m3-cluster-probe-20260912\conf'
$env:YARN_CONF_DIR   = $env:HADOOP_CONF_DIR
$env:HADOOP_USER_NAME = 'root'
$yarn = 'D:\soft\hadoop\hadoop-3.3.4\bin\yarn.cmd'
$sparkSql = 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-sql.cmd'
$jobs = @('sci','odl','bdw','dim','tdw','usw','fna','dqc','pub','mxp')

# ---------------- ① 独立终态 ----------------
'==== ① yarn application -status（独立于 spark-submit 的记录）===='
$appLines = @()
for ($i = 0; $i -lt $jobs.Count; $i++) {
  $id = $AppPrefix + ('{0:d4}' -f ($AppBase + $i))
  $o = & $yarn application -status $id 2>&1
  $txt = ($o | ForEach-Object { [string]$_ }) -join "`n"
  $st = if ($txt -match '(?m)^\s*State\s*:\s*(\S+)')       { $Matches[1] } else { 'NA' }
  $fs = if ($txt -match '(?m)^\s*Final-State\s*:\s*(\S+)') { $Matches[1] } else { 'NA' }
  $line = '{0,-4} {1} State={2} Final={3}' -f $jobs[$i], $id, $st, $fs
  $appLines += $line
  '   ' + $line
}
[IO.File]::WriteAllLines((Join-Path $out 'e4-appstatus-fix.txt'), [string[]]$appLines, [Text.UTF8Encoding]::new($false))

# ---------------- ② / ③ Hive 独立回读 ----------------
$lean = @('--master','local[1]','--driver-memory','512m',
          '--conf','spark.sql.shuffle.partitions=1',
          '--conf','spark.driver.extraJavaOptions=-XX:MaxMetaspaceSize=192m -XX:+UseSerialGC')
$sqlSets = @(
  @{ name='e4-readback-fix.txt';        file='docs\acceptance\e4-cluster-1000-20260912\tools\e4-readback.sql' },
  @{ name='e4-readback2-fix.txt';       file='.verify\e4-readback2.sql' },
  @{ name='e4-batchid-check-fix.txt';   file='.verify\e4-batchid-check.sql' }
)
foreach ($s in $sqlSets) {
  '==== ②/③ spark-sql -f ' + $s.file + ' ===='
  $t0 = Get-Date
  $o = & $sparkSql @lean '-f' (Join-Path $repo $s.file) 2>&1
  $txt = @($o | ForEach-Object { [string]$_ })
  [IO.File]::WriteAllLines((Join-Path $out $s.name), [string[]]$txt, [Text.UTF8Encoding]::new($false))
  $secs = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
  $keep = $txt | Where-Object { $_ -notmatch '^\d\d/\d\d/\d\d \d\d:\d\d:\d\d (INFO|WARN)' -and $_ -notmatch '^Missing Python|^Setting default log|^To adjust logging|^Spark Web UI|^Spark master' }
  '   耗时 {0}s，输出 {1} 行；关键行：' -f $secs, $txt.Count
  $keep | Where-Object { $_.Trim() -ne '' } | Select-Object -Last 40 | ForEach-Object { '      ' + $_.Trim() }
}

# ---------------- ④ mxp 导出产物 HDFS 独立回读 ----------------
'==== ④ mxp 导出 HDFS 回读 ===='
$mxpOut = Join-Path $out 'e4-mxp-readback-out.txt'
& pwsh -NoProfile -File (Join-Path $repo '.verify\e4-mxp-readback.ps1') 2>&1 | Tee-Object -FilePath $mxpOut | ForEach-Object { '   ' + [string]$_ }
'==== 完成。产物目录：' + $out + ' ===='
Get-ChildItem $out -File | Sort-Object Name | ForEach-Object { '   {0,-34} {1,9} B' -f $_.Name, $_.Length }
