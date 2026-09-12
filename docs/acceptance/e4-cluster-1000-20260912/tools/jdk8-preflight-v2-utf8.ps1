# JDK 8 本地 pre-flight：用本机 D:\Develop\JDK1.8 跑指定 jar，判定「Java 9+ API 在 JDK 8 上炸」这一类缺陷
# 用法: pwsh -File .verify/jdk8-preflight.ps1 -Jar <jar> -Tag <old|new> -Jobs mxp
# 关键点：
#  ① 必须清掉 HADOOP_CONF_DIR/YARN_CONF_DIR（否则 hive-site.xml 会把本地 Spark 指向集群 thrift metastore 9083，
#     本地建表就会污染集群证据！）——这是本脚本最重要的一条安全措施；
#  ② 判据只看「JDK 8 兼容性错误」（NoSuchMethodError/NoSuchFieldError/AbstractMethodError/UnsupportedClassVersionError），
#     数据问题（表不存在、行数为 0）不算兼容性失败，避免把无关红当成兼容红；
#  ③ 仓库指向 gitignored 的 tests/r6-smoke-warehouse（只读使用，mxp 对 Hive 只读）。
param(
  [Parameter(Mandatory=$true)][string]$Jar,
  [string]$Jobs = 'mxp',
  [string]$Tag = 'p1',
  [string]$Warehouse = 'D:\Develop_code\GraduationProject\tests\r6-smoke-warehouse\warehouse',
  [string]$Derby     = 'D:\Develop_code\GraduationProject\tests\r6-smoke-warehouse\derby-metastore',
  [string]$ExportRoot = ''
)

# 证据保真（实测陷阱 #24）：Java 子进程按 -Dfile.encoding=UTF-8 输出 **UTF-8 字节**，
# 若不在捕获端显式设控制台解码，PowerShell 会按 OEM 码页(GBK/936)解码 ⇒ 中文 JobResult/message 全成乱码。
# 本脚本同时做两件事：让子 JVM 一定写 UTF-8；让捕获端一定按 UTF-8 解。
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

$repo = 'D:\Develop_code\GraduationProject'
Set-Location $repo
$out = Join-Path $repo ".verify\jdk8-preflight\$Tag"
New-Item -ItemType Directory -Force -Path $out | Out-Null
if (-not $ExportRoot) { $ExportRoot = "file:///D:/Develop_code/GraduationProject/.verify/jdk8-preflight/$Tag/export" }

# 安全措施①：本地模式绝不能继承集群 conf / 身份
Remove-Item Env:HADOOP_CONF_DIR -ErrorAction SilentlyContinue
Remove-Item Env:YARN_CONF_DIR  -ErrorAction SilentlyContinue
Remove-Item Env:HADOOP_USER_NAME -ErrorAction SilentlyContinue
$env:JAVA_HOME = 'D:\Develop\JDK1.8'
$env:SPARK_HOME = 'D:\Develop\spark-3.5.1-bin-hadoop3'
$env:PATH = 'D:\Develop\JDK1.8\bin;' + $env:PATH

$jarAbs = (Resolve-Path $Jar).Path
$sparkSubmit = 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd'
$whUri = 'file:///' + ($Warehouse -replace '\\','/')
$derbyUrl = "jdbc:derby:$Derby"

'   JAVA_HOME=' + $env:JAVA_HOME + '（' + (& 'D:\Develop\JDK1.8\bin\java.exe' -version 2>&1 | Select-Object -First 1) + '）'
'   jar=' + $jarAbs
'   warehouse=' + $whUri

$compat = 'NoSuchMethodError|NoSuchFieldError|AbstractMethodError|UnsupportedClassVersionError'
$lines = @()
foreach ($job in ($Jobs -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
  $extra = @()
  switch ($job) {
    'odl' { $extra = @("--landingDir=file:///$($repo -replace '\\','/')/tests/golden-dataset/events",'--sourceSystem=mock-mall','--batchId=0') }
    'mxp' { $extra = @("--exportDir=$ExportRoot") }
  }
  $a = @('--master','local[2]','--class','com.graduation.analytics.job.JobRunner',
         '--conf',"spark.hadoop.javax.jdo.option.ConnectionURL=$derbyUrl",
         '--conf','spark.hadoop.javax.jdo.option.ConnectionDriverName=org.apache.derby.jdbc.EmbeddedDriver',
         '--conf','spark.sql.hive.metastore.jars=builtin',
         '--conf','spark.hadoop.datanucleus.schema.autoCreateTables=true',
         '--conf',"spark.sql.warehouse.dir=$whUri",
         '--conf','spark.sql.shuffle.partitions=4',
         '--driver-memory','1g',
         $jarAbs,
         '--runtimeProfileId=7',"--jobCode=$job",'--businessDate=20260901','--attemptNo=1',
         '--hiveDatabasePrefix=dw','--outputSnapshotId=S20260901J8','--shufflePartitions=4') + $extra
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $o = & $sparkSubmit @a 2>&1
  $code = $LASTEXITCODE
  $sw.Stop()
  $log = Join-Path $out "$job.log"
  [IO.File]::WriteAllLines($log, [string[]]$o, [Text.UTF8Encoding]::new($false))

  $hit = @($o | Where-Object { $_ -match $compat })
  $hitLine = if ($hit.Count -gt 0) { ([string]$hit[0]).Trim() } else { '' }
  $res = $null
  foreach ($l in $o) { $s = ([string]$l).Trim(); if ($s.StartsWith('{') -and $s.Contains('"jobCode"')) { $res = $s } }
  $verdict = if ($hit.Count -gt 0) { '兼容性错误 ✗' } else { '无兼容性错误 ✓' }
  '   [{0,-4}] exit={1}  {2}  {3}s' -f $job, $code, $verdict, [math]::Round($sw.Elapsed.TotalSeconds,1)
  if ($hitLine) { '          命中: ' + $hitLine.Substring(0,[Math]::Min(160,$hitLine.Length)) }
  if ($res) { '          JobResult: ' + $res.Substring(0,[Math]::Min(230,$res.Length)) }
  $lines += [pscustomobject]@{ job=$job; exit=$code; compatHit=$hit.Count; compatFirst=$hitLine; jobResult=$res; log=$log; sec=[math]::Round($sw.Elapsed.TotalSeconds,1) }
}
$sumFile = Join-Path $out 'summary.jsonl'
foreach ($l in $lines) { ($l | ConvertTo-Json -Compress -Depth 3) | Add-Content -LiteralPath $sumFile -Encoding utf8 }
'   ---- 汇总: ' + $sumFile
$lines | ForEach-Object { '   {0,-4} exit={1,-3} 兼容命中={2}' -f $_.job, $_.exit, $_.compatHit }
