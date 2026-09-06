# 本地 Spark 作业链验证脚本（阶段 9/10）
# 依赖：D:\Develop\spark-3.5.1-bin-hadoop3（环境变量 SPARK_HOME）；mall-simulator 的 Landing 事件；
# 用 embedded Derby Hive（spark.sql.warehouse.dir）在无 Hadoop/Hive 服务的本机跑通
#   sci(建表) → odl(JSON→ODS) → bdw(ODS→DWD) → usw(DWD→DWS) → fna(DWS→ADS)
param(
  [string]$Landing = 'D:\Develop_code\GraduationProject\mall-simulator\landing\events',
  [string]$Warehouse = 'D:\Develop\tmp\spark-warehouse',
  [string]$Jar = 'D:\Develop_code\GraduationProject\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar'
)
$ErrorActionPreference = 'Stop'
$env:SPARK_HOME = if ($env:SPARK_HOME) { $env:SPARK_HOME } else { 'D:\Develop\spark-3.5.1-bin-hadoop3' }
if (-not (Test-Path $Jar)) { Write-Host "jar 不存在，请先: cd spark-jobs && mvn package"; exit 1 }
New-Item -ItemType Directory -Force -Path $Warehouse | Out-Null

# 自动探测最新业务日（Landing 文件名 yyyyMMddHH.jsonl 的最大日期）
$latestFile = Get-ChildItem $Landing -Filter '*.jsonl' | Sort-Object Name -Descending | Select-Object -First 1
if (-not $latestFile) { Write-Host "Landing 无事件文件: $Landing"; exit 1 }
$businessDate = $latestFile.BaseName.Substring(0, 8)
Write-Host "业务日: $businessDate（来源文件 $($latestFile.Name)）"

function Run-Job([string]$code, [string]$extra) {
  $t0 = Get-Date
  $log = "$env:TEMP\spark-$code.log"
  # spark-submit 层参数（--conf）必须在 --class 之前；应用参数在后
  $submitArgs = @(
    '--master', 'local[2]',
    '--conf', "spark.sql.warehouse.dir=$($Warehouse -replace '\\','/')",
    '--conf', 'spark.sql.session.timeZone=Asia/Shanghai',
    '--class', 'com.graduation.analytics.job.JobRunner',
    $Jar,
    "--runtimeProfileId=1", "--jobCode=$code", "--businessDate=$businessDate", '--attemptNo=1'
  )
  if ($extra) { $submitArgs += $extra.Split(' ') }
  & "$env:SPARK_HOME\bin\spark-submit.cmd" $submitArgs *> $log
  $result = Get-Content $log | Where-Object { $_ -match '"jobCode":"' -and $_ -match '"status":"' } | Select-Object -Last 1
  if (-not $result) {
    Write-Host "[$code] FAILED（无 JobResult，见 $log 尾部）"
    Get-Content $log -Tail 6 | Select-Object -First 5
    exit 1
  }
  $j = $result | ConvertFrom-Json
  Write-Host ("[{0}] {1} input={2} output={3} elapsed={4}ms ({5}s)" -f $code, $j.status, $j.inputRecords,
    $j.outputRecords, $j.elapsedMs, [Math]::Round(((Get-Date)-$t0).TotalSeconds,1))
  $j
}

Write-Host '=== Spark 作业链（local[2] + Derby Hive）==='
$r1 = Run-Job 'sci' ''
$r2 = Run-Job 'odl' "--landingDir=$Landing"
$r3 = Run-Job 'bdw' ''
$r4 = Run-Job 'usw' ''
$r5 = Run-Job 'fna' '--topN=10'

$summary = @{
  timestamp = (Get-Date).ToString('s')
  spark = '3.5.1 local[2]'
  hive = 'embedded derby (spark.sql.warehouse.dir)'
  landingDir = $Landing
  jobs = @{
    sci = @{ status = $r1.status; elapsedMs = $r1.elapsedMs }
    odl = @{ status = $r2.status; input = $r2.inputRecords; output = $r2.outputRecords; elapsedMs = $r2.elapsedMs }
    bdw = @{ status = $r3.status; input = $r3.inputRecords; output = $r3.outputRecords; elapsedMs = $r3.elapsedMs }
    usw = @{ status = $r4.status; input = $r4.inputRecords; output = $r4.outputRecords; elapsedMs = $r4.elapsedMs }
    fna = @{ status = $r5.status; input = $r5.inputRecords; output = $r5.outputRecords; elapsedMs = $r5.elapsedMs }
  }
}
$out = "D:\Develop_code\GraduationProject\experiments\spark-chain-local-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"
$summary | ConvertTo-Json -Depth 4 | Set-Content $out -Encoding utf8
Write-Host "已归档: $out"