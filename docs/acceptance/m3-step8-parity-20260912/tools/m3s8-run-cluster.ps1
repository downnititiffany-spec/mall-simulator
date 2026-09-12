# M3 §6.4 第 6 步（集群侧）· 55 行金标同输入集群小链 · 逐作业提交器
# 【来源】逐字复制自权威脚本 docs\acceptance\e4-cluster-1000-20260912\tools\e4-run-chain-fix.ps1
#          （依据 PLAN.md §6.2「以该脚本为蓝本逐字复制（含全部 --conf）并原样落盘」）
# 【相对蓝本仅改三处，其余参数（--master yarn --deploy-mode cluster、spark.yarn.jars、
#   eventLog/warehouse/metastore、executor 1×1g、driver 1g、shuffle 4、--shufflePartitions=4、
#   --businessDate=20260901、--attemptNo=1、--hiveDatabasePrefix=dw、odl 的
#   --sourceSystem=mock-mall --batchId=0）一律照旧】
#   ① $LAND  : e4c1000 → hdfs://node01:8020/graduation/landing/m3s8-55
#   ② mxp --exportDir : .../export/e4c1000 → hdfs://node01:8020/graduation/export/m3s8-55
#   ③ $SnapshotId 默认值 : S20260901E4 → S20260901M3S8
# 【禁止】写 e4c1000 或 /graduation/export/e4c1000 —— 那会毁掉 E4 证据
# 用法: pwsh -File m3s8-run-cluster.ps1 -Jobs odl,tdw,usw,bdw,dim,dqc,fna,sci,mxp,pub `
#            -Jar <repo>\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar -RawDir <raw>
param(
  [string]$Jobs = 'sci',   # 注意：pwsh -File 传参只会给字符串，故这里收逗号串再切分
  [switch]$KeepGoing,
  [string]$Jar = '',       # 空 = 用首次 E4 的 p2-01-e3.jar（本任务必须显式传新 jar）
  [string]$RawDir = '',    # 空 = 用首次 E4 的 raw/
  [string]$SnapshotId = 'S20260901M3S8'
)
$jobList = @($Jobs -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$ErrorActionPreference = 'Continue'
$repo = 'D:\Develop_code\GraduationProject'
Set-Location $repo

# F-52 教训：Windows 客户端提交 YARN 必须先设配置目录；身份必须是 root（/graduation 为 root 所有）
$env:HADOOP_USER_NAME = 'root'
$env:HADOOP_CONF_DIR = Join-Path $repo 'docs\acceptance\m3-cluster-probe-20260912\conf'
$env:YARN_CONF_DIR   = $env:HADOOP_CONF_DIR

$sparkSubmit = 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd'
$yarnCli     = 'D:\soft\hadoop\hadoop-3.3.4\bin\yarn.cmd'
if (-not $Jar) { $Jar = Join-Path $repo 'spark-jobs\target\p2-01-built\spark-jobs-0.1.0-SNAPSHOT-p2-01-e3.jar' }
if (-not $RawDir) { $RawDir = Join-Path $repo 'docs\acceptance\e4-cluster-1000-20260912\raw' }
$jar = (Resolve-Path $Jar).Path
$raw = $RawDir
New-Item -ItemType Directory -Force -Path $raw | Out-Null
"### jar = $jar"
"### raw = $raw"
"### snapshot = $SnapshotId"
"### jar sha256 = " + (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash

$DT   = '20260901'
$SID  = $SnapshotId
$NS   = '--hiveDatabasePrefix=dw'
$LAND = 'hdfs://node01:8020/graduation/landing/m3s8-55'

# 通用提交参数：jar 走 HDFS 上的 Spark 组装（探针已验证）；事件日志与数仓根都在 /graduation 下
$common = @(
  '--master','yarn','--deploy-mode','cluster',
  '--conf','spark.yarn.jars=hdfs://node01:8020/graduation/jars/spark-3.5.1/*.jar',
  '--conf','spark.eventLog.enabled=true',
  '--conf','spark.eventLog.dir=hdfs://node01:8020/graduation/eventlog',
  '--conf','spark.sql.warehouse.dir=hdfs://node01:8020/graduation/warehouse',
  '--conf','spark.hadoop.hive.metastore.uris=thrift://node01:9083',
  '--conf','spark.executor.instances=1',
  '--conf','spark.executor.cores=1',
  '--conf','spark.executor.memory=1g',
  '--conf','spark.driver.memory=1g',
  '--conf','spark.sql.shuffle.partitions=4'
)

$base = @('--runtimeProfileId=1',"--businessDate=$DT",'--attemptNo=1',$NS,
          "--outputSnapshotId=$SID",'--shufflePartitions=4')

$perJob = @{
  'sci' = @()
  'odl' = @("--landingDir=$LAND",'--sourceSystem=mock-mall','--batchId=0')
  'bdw' = @()
  'dim' = @()
  'tdw' = @()
  'usw' = @()
  'fna' = @()
  'dqc' = @()
  'pub' = @()
  'mxp' = @('--exportDir=hdfs://node01:8020/graduation/export/m3s8-55')
}

$summary = @()
foreach ($job in $jobList) {
  if (-not $perJob.ContainsKey($job)) { "!! 未知作业 $job"; continue }
  $sw = [Diagnostics.Stopwatch]::StartNew()
  "=== [$job] 提交 $(Get-Date -Format 'HH:mm:ss') ==="
  $cmdArgs = @($common) + @('--name',"e4-$job",'--class','com.graduation.analytics.job.JobRunner') + $jar +
          @("--jobCode=$job") + $base + $perJob[$job]
  $submitLog = Join-Path $raw "$job-submit.log"
  $out = & $sparkSubmit @cmdArgs 2>&1
  $code = $LASTEXITCODE
  $sw.Stop()
  [IO.File]::WriteAllLines($submitLog, [string[]]$out, [Text.UTF8Encoding]::new($false))

  $appId = $null
  foreach ($line in $out) {
    if (-not $appId -and ([string]$line) -match '(application_\d+_\d{4})') { $appId = $Matches[1] }
  }
  "    exit=$code  appId=$appId  耗时=$([math]::Round($sw.Elapsed.TotalSeconds,1))s"

  # JobResult JSON 是 stdout 机器契约；集群模式下只能经 yarn logs 回收（F-53）
  $resultLine = $null
  if ($appId) {
    $logs = & $yarnCli logs -applicationId $appId 2>&1
    [IO.File]::WriteAllLines((Join-Path $raw "$job-yarn-logs.txt"), [string[]]$logs, [Text.UTF8Encoding]::new($false))
    foreach ($line in $logs) {
      $s = ([string]$line).Trim()
      if ($s.StartsWith('{') -and $s.Contains('"jobCode"')) { $resultLine = $s }
    }
  }
  if ($resultLine) {
    $status = 'UNKNOWN'
    try { $status = ($resultLine | ConvertFrom-Json).status } catch {}
    "    JobResult.status=$status"
    "    $resultLine"
    $summary += [pscustomobject]@{ job=$job; exit=$code; appId=$appId; status=$status; result=$resultLine; sec=[math]::Round($sw.Elapsed.TotalSeconds,1) }
  } else {
    "    !! 未取到 JobResult（appId=$appId）"
    $summary += [pscustomobject]@{ job=$job; exit=$code; appId=$appId; status='NO_RESULT'; result=$null; sec=[math]::Round($sw.Elapsed.TotalSeconds,1) }
  }
  if ($code -ne 0 -and -not $KeepGoing) { "=== 链路在 [$job] 停止（exit=$code）==="; break }
}

$outFile = Join-Path $raw 'chain-summary.jsonl'
foreach ($s in $summary) { ($s | ConvertTo-Json -Compress -Depth 3) | Add-Content -LiteralPath $outFile -Encoding utf8 }
"=== 完成，共 $($summary.Count) 个作业；汇总 $outFile ==="
$summary | ForEach-Object { "   $($_.job)  exit=$($_.exit)  status=$($_.status)  app=$($_.appId)  $($_.sec)s" }
