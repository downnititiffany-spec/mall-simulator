# P2-07 A0：改动前备份 + before 指纹（施工单 A0）
# 用法：pwsh -NoProfile -ExecutionPolicy Bypass -File docs/acceptance/p2-07-source-prefix-20260912/raw/e0-backup.ps1
# 备份目录：%TEMP%\p2-07-backup-<yyyyMMdd-HHmmss>\（保持仓库相对路径）
# 输出：before 指纹表（相对路径 + 字节数 + sha256）到 stdout，由调用方重定向到 raw/e0-backup-<ts>.log
$ErrorActionPreference = 'Stop'

$Repo = 'D:\Develop_code\GraduationProject'
$Stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$BackupRoot = Join-Path $env:TEMP "p2-07-backup-$Stamp"

# 本泳道将修改的文件（施工单 A1-A7、A10 的目标；契约 contract-specs/** 只读，不在内）
$Files = @(
    'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/entity/SourceRegistry.java',
    'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/mapper/SourceRegistryMapper.java',
    'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/dto/SourceRegistryCreateReq.java',
    'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/dto/SourceRegistryUpdateReq.java',
    'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/dto/SourceRegistryView.java',
    'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/SourceRegistryServiceImpl.java',
    'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/runtime/ActiveProfileWarehouseNamespaceProvider.java',
    'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/runtime/RuntimeProfileSnapshot.java',
    'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/runtime/RuntimeProfileServiceImpl.java',
    'analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/source/SourceRegistryTestSupport.java',
    'analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/source/SourceRegistryServiceTest.java',
    'analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/source/SourceRegistryConcurrencyTest.java',
    'analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java',
    'analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespaceProvider.java',
    'analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNamespaceContractTest.java',
    'analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java',
    'analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/spark/JobCommandBuilder.java',
    'analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/spark/SparkStageExecutor.java',
    'analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/spark/SparkStageExecutorFactory.java',
    'analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/spark/JobCommandBuilderTest.java',
    'analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/spark/SparkStageExecutorTest.java',
    'analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/spark/SparkStageExecutorSmokeTest.java',
    'analytics-server/platform-app/src/main/java/com/graduation/analytics/config/PlatformBeans.java',
    'analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/SourceRegistryControllerAuditTest.java',
    'analytics-server/platform-app/src/test/java/com/graduation/analytics/source/SourceRegistryMigrationMySqlIT.java',
    'analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidenceBuilder.java',
    'docs/reference/deployment.md',
    'docs/thesis-materials/thesis-outline.md',
    'docs/status-history/开发过程事实与决策记录.md'
)

New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null
"=== P2-07 A0 备份 ==="
"repo        : $Repo"
"backupRoot  : $BackupRoot"
"stamp       : $Stamp"
"fileCount   : $($Files.Count)"
""
"--- before 指纹表（相对路径 | 字节 | sha256）---"
foreach ($rel in $Files) {
    $src = Join-Path $Repo $rel
    if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { throw "缺文件（不静默跳过）: $rel" }
    $dst = Join-Path $BackupRoot $rel
    New-Item -ItemType Directory -Path (Split-Path -Parent $dst) -Force | Out-Null
    Copy-Item -LiteralPath $src -Destination $dst -Force
    $hash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash
    $len = (Get-Item -LiteralPath $src).Length
    "{0} | {1} | {2}" -f $rel, $len, $hash
}
""
"backupVerified(byte-equal): " + (@($Files | Where-Object {
    $a = Join-Path $Repo $_
    $b = Join-Path $BackupRoot $_
    (Get-FileHash -LiteralPath $a -Algorithm SHA256).Hash -eq (Get-FileHash -LiteralPath $b -Algorithm SHA256).Hash
}).Count) + "/$($Files.Count)"
"=== A0 完成 ==="
