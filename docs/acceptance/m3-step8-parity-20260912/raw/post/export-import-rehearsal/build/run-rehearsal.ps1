# M3 预演驱动包装脚本（**非生产代码**，仅存在于验收报告目录）
# 用途：用真实导入器把 metric-staging 导出物导入隔离库 analytics_verify_m3_parity
# 用法：run-rehearsal.ps1 -Label RUN1 -SnapshotId S20260901_47 -ExportDir <dir> [-BusinessDate 20260901]
param(
  [Parameter(Mandatory=$true)][string]$Label,
  [Parameter(Mandatory=$true)][string]$SnapshotId,
  [Parameter(Mandatory=$true)][string]$ExportDir,
  [string]$BusinessDate = '20260901',
  [string]$BusinessTime = '2026-09-01T00:00',
  [string]$RuntimeProfileId = '1',
  [string]$ProfileVersion = '3',
  [string]$PipelineRunId = '47'
)
$b = 'D:\Develop_code\GraduationProject\docs\acceptance\m3-step8-parity-20260912\raw\post\export-import-rehearsal\build'
$java = 'D:\Develop\JAVA17\bin\java.exe'
$grad = @('metric-analysis-0.1.0-SNAPSHOT.jar','platform-common-0.1.0-SNAPSHOT.jar','warehouse-pipeline-0.1.0-SNAPSHOT.jar','ai-decision-0.1.0-SNAPSHOT.jar','connection-ingestion-0.1.0-SNAPSHOT.jar')
$libs = Get-ChildItem "$b\lib\BOOT-INF\lib" -Filter *.jar | Where-Object { $grad -notcontains $_.Name } | ForEach-Object { $_.FullName }
$cp = (@(
 'D:\Develop_code\GraduationProject\analytics-server\metric-analysis\target\classes',
 'D:\Develop_code\GraduationProject\analytics-server\platform-common\target\classes',
 "$b\classes"
) + $libs) -join ';'

$props = @(
  '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
  '-Drehearsal.publish.url=jdbc:mysql://127.0.0.1:3306/analytics_verify_m3_parity?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true',
  '-Drehearsal.publish.user=metric_pub',
  '-Drehearsal.publish.password=metric_pub_pw_2026',
  '-Drehearsal.read.url=jdbc:mysql://127.0.0.1:3306/analytics_verify_m3_parity?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true',
  '-Drehearsal.read.user=metric_read',
  '-Drehearsal.read.password=metric_read_pw_2026',
  '-Drehearsal.meta.url=jdbc:mysql://127.0.0.1:3306/analytics_meta?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true',
  '-Drehearsal.meta.user=meta_app',
  '-Drehearsal.meta.password=meta_app_pw_2026'
)

Write-Output "### COMMAND LINE (reproducible) ###"
Write-Output ("`"$java`" " + ($props -join ' ') + " -cp <classes;libs> RehearsalRunner $Label $SnapshotId $BusinessDate $BusinessTime $RuntimeProfileId $ProfileVersion $PipelineRunId `"$ExportDir`"")
Write-Output "### EXECUTION ###"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
& $java @props -cp $cp RehearsalRunner $Label $SnapshotId $BusinessDate $BusinessTime $RuntimeProfileId $ProfileVersion $PipelineRunId $ExportDir
$code = $LASTEXITCODE
$sw.Stop()
Write-Output "### EXIT CODE = $code ; WALL CLOCK = $($sw.Elapsed.TotalSeconds.ToString('0.000')) s ###"
exit $code
