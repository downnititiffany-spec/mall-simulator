<#
V26-F88 | 规则指纹「两路交叉核对」取证
  路 1：应用日志里 PipelineService 冻结规则时打印的 ruleFingerprint/catalog/compatPolicy；
  路 2：随后 S5 从 data_quality_result.rule_fingerprint 列读回的值。
  两路必须相等，且都等于任务书给出的期望常量 —— 只硬编码常量不算取证。
  同时抽取本 run 的 ingest / spark-submit / 收尾证据行，供报告引用。
#>
param(
  [string]$AppLog = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw\20-app-8091-console.log'),
  [string]$OutName = '35-frozen-rules-from-app-log.txt',
  [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw'),
  [string]$ExpectedFingerprint = '6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$out = Join-Path $OutDir $OutName
$lines = New-Object System.Collections.Generic.List[string]
function Emit([string]$s) { $lines.Add($s); Write-Host $s }

Emit "V26-F88 规则指纹两路交叉核对  $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
Emit "来源日志 = $AppLog"
Emit "期望常量 = $ExpectedFingerprint"
if (-not (Test-Path $AppLog)) { throw "找不到应用日志 $AppLog" }

Emit "`n===== 路 1：应用日志中的规则冻结行 ====="
$frozen = @(Select-String -Path $AppLog -Pattern '规则冻结')
if ($frozen.Count -eq 0) { Emit "（未找到『规则冻结』行）" } else { $frozen | ForEach-Object { Emit ("  L{0}: {1}" -f $_.LineNumber, $_.Line.Trim()) } }

$logFp = $null
foreach ($m in $frozen) { if ($m.Line -match 'ruleFingerprint=([0-9a-fA-F]{64})') { $logFp = $Matches[1].ToLower() } }
$logCat = $null; foreach ($m in $frozen) { if ($m.Line -match 'catalog=([^\s]+)') { $logCat = $Matches[1] } }
$logCp  = $null; foreach ($m in $frozen) { if ($m.Line -match 'compatPolicy=([^\s]+)') { $logCp = $Matches[1] } }
Emit "`n[路1 读数] ruleFingerprint(from app log) = $logFp"
Emit "[路1 读数] catalog(from app log)          = $logCat"
Emit "[路1 读数] compatPolicy(from app log)     = $logCp"

Emit "`n===== 本 run 关键日志行（ingest / spark-submit / 质量 / 中断）====="
foreach ($pat in @('ingestion run ', 'spark-submit ', 'QualityChecker|质量|quality', 'PipelineService|pipeline \d+:', 'ERROR|WARN.*FAIL|失败')) {
  Emit "`n---- pattern: $pat ----"
  $hits = @(Select-String -Path $AppLog -Pattern $pat)
  if ($hits.Count -eq 0) { Emit "  （无匹配）" } else { $hits | ForEach-Object { Emit ("  L{0}: {1}" -f $_.LineNumber, $_.Line.Trim()) } }
}

Emit "`n===== 判定 ====="
Emit ("[路1] 日志指纹 == 期望常量 : {0}" -f $(if ($logFp -eq $ExpectedFingerprint.ToLower()) { 'PASS' } else { "FAIL（log=$logFp）" }))
Emit ("[路1] 日志 catalog = 'qrc-1' : {0}" -f $(if ($logCat -eq 'qrc-1') { 'PASS' } else { "INFO/FAIL（log=$logCat）" }))
Emit ("[路1] 日志 compatPolicy = 'compat-v1' : {0}" -f $(if ($logCp -eq 'compat-v1') { 'PASS' } else { "FAIL（log=$logCp）" }))
Emit "（路 2 的读回值见 raw/40-four-column-assertions.txt 的 S5-A2b/读数行；两路相等才构成交叉核对）"

$lines | Set-Content $out -Encoding utf8
Write-Output "[35] 证据已写 $out"
