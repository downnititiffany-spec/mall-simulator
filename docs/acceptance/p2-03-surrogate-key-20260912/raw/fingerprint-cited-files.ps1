# fingerprint-cited-files.ps1 -- P2-03 只读取证：为本报告引用的每个文件记录 mtime/size/sha256，并判定是否落在读取窗口内
# 读取窗口 = 12:57:06（本目录首个 raw 制品落盘） … 13:01:47（晚期 git 快照）
# 输出：raw/cited-file-fingerprints.tsv + raw/cited-file-fingerprints-console.txt
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = 'D:\Develop_code\GraduationProject'
$outDir = $PSScriptRoot
$windowStart = [datetime]::Parse('2026-09-12 12:57:06')
$windowEnd = [datetime]::Parse('2026-09-12 13:01:48')

$paths = @(
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/IdCodec.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/DimSql.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/JsonObjectSlicer.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/DwsSql.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala',
  'spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala',
  'spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala',
  'spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala',
  'spark-jobs/src/test/scala/com/graduation/analytics/P2ProbeSpec.scala',
  'spark-jobs/src/test/scala/com/graduation/analytics/P2Probe2Spec.scala',
  'analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java',
  'analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNamespaceContractTest.java',
  'analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java',
  'analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidenceBuilder.java',
  'analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/sql/SqlSafetyValidator.java',
  'analytics-server/platform-app/src/main/java/com/graduation/analytics/controller/AiController.java',
  'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/ingestion/IngestionService.java',
  'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/SourceRegistryServiceImpl.java',
  'analytics-server/source-profiles/p1-03-probe-1.v1.json',
  'analytics-server/source-profiles/p1-03-probe-2.v1.json',
  'analytics-server/platform-app/src/main/resources/db/business/V1__init_mall.sql',
  'mall-simulator/src/main/resources/db/migration/V1__init_mall.sql',
  'mall-simulator/src/main/java/com/graduation/mall/controller/MallController.java',
  'mall-simulator/src/main/java/com/graduation/mall/domain/service/MallBusinessService.java',
  'mall-simulator/src/main/java/com/graduation/mall/domain/entity/MallUser.java',
  'mall-simulator/src/main/java/com/graduation/mall/domain/entity/Product.java',
  'mall-simulator/src/main/java/com/graduation/mall/domain/entity/MallOrder.java',
  'mall-simulator/src/main/java/com/graduation/mall/domain/entity/Payment.java',
  'mall-simulator/src/main/java/com/graduation/mall/domain/entity/Refund.java',
  'mall-simulator/src/main/java/com/graduation/mall/outbox/EventPayloadFactory.java',
  'synthetic-data-generator/src/main/java/com/graduation/generator/contract/JsonlEventSink.java',
  'synthetic-data-generator/src/main/java/com/graduation/generator/service/GenerationRunService.java',
  'synthetic-data-generator/src/main/java/com/graduation/generator/engine/FileModeGenerationEngine.java',
  'synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiDispatchSink.java',
  'synthetic-data-generator/src/main/java/com/graduation/generator/engine/OperationJournalEntry.java',
  'warehouse/ddl/00-ods.sql',
  'warehouse/ddl/01-dwd.sql',
  'warehouse/ddl/02-dims.sql',
  'warehouse/ddl/03-dws.sql',
  'warehouse/ddl/04-ads.sql',
  'contract-specs/specs/warehouse-namespace.v1.json',
  'contract-specs/README.md',
  'contract-specs/VERSION',
  'tests/golden-dataset/events/golden-20260901.jsonl',
  'landing/events/2026091211.jsonl',
  'landing/events/2026091210.jsonl',
  'docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md',
  'docs/项目实施进度与任务看板 V2.2.md',
  'docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md',
  'docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md'
)

$rows = New-Object System.Collections.Generic.List[object]
foreach ($rel in $paths) {
  $full = Join-Path $root ($rel -replace '/', '\')
  if (-not (Test-Path $full)) {
    $rows.Add([pscustomobject]@{ path = $rel; exists = 'NO'; size = ''; mtime = ''; sha256 = ''; in_window = ''; class = 'MISSING' })
    continue
  }
  $fi = Get-Item $full
  $inWin = ($fi.LastWriteTime -ge $windowStart) -and ($fi.LastWriteTime -le $windowEnd)
  $rows.Add([pscustomobject]@{
      path = $rel; exists = 'YES'; size = $fi.Length
      mtime = $fi.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
      sha256 = (Get-FileHash $full -Algorithm SHA256).Hash.ToLower()
      in_window = if ($inWin) { 'YES' } else { 'NO' }
      class = if ($inWin) { 'WINDOW_WRITE' } else { 'STABLE' }
    })
}
$rows | ForEach-Object { "{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}" -f $_.path, $_.exists, $_.size, $_.mtime, $_.sha256, $_.in_window, $_.class } |
  Set-Content -Encoding utf8 (Join-Path $outDir 'cited-file-fingerprints.tsv')

Write-Output "window = $windowStart .. $windowEnd"
Write-Output "files_total=$($rows.Count)  missing=$(@($rows | Where-Object { $_.exists -eq 'NO' }).Count)  window_writes=$(@($rows | Where-Object { $_.class -eq 'WINDOW_WRITE' }).Count)"
Write-Output '--- 落在读取窗口内被改动的文件（事实可能已失效）---'
$rows | Where-Object { $_.class -eq 'WINDOW_WRITE' } | Select-Object path, size, mtime | Format-Table -AutoSize
Write-Output '--- 缺失（引用不存在的路径）---'
$rows | Where-Object { $_.exists -eq 'NO' } | Select-Object path | Format-Table -AutoSize
