# R1 迁移拆分：把 mall-simulator 的 V1-V7 按整改书 §7.3 拆为三套 Flyway 集合
# 输出到 analytics-server/src/main/resources/db/{business,meta,metric}
param(
  [string]$Src = 'D:\Develop_code\GraduationProject\mall-simulator\src\main\resources\db\migration',
  [string]$Out = 'D:\Develop_code\GraduationProject\analytics-server\src\main\resources\db'
)
$ErrorActionPreference = 'Stop'

function Split-Tables($file, [string[]]$wanted) {
  $text = Get-Content (Join-Path $Src $file) -Raw -Encoding UTF8
  $blocks = @{}
  # 以 CREATE TABLE [(IF NOT EXISTS)] <name> 为锚切块（兼容两种写法）
  $pattern = '(?s)(CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?(\w+)`?\s*\(.*?)(?=CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?\w+`?\s*\(|\Z)'
  foreach ($m in [regex]::Matches($text, $pattern)) {
    $name = $m.Groups[2].Value
    if ($wanted -contains $name) { $blocks[$name] = $m.Groups[1].Value.Trim() }
  }
  return $blocks
}

function Split-Inserts($file, [string[]]$wanted) {
  $text = Get-Content (Join-Path $Src $file) -Raw -Encoding UTF8
  $result = @{}
  $pattern = '(?s)(INSERT INTO\s+`?\w+`?.*?)(?=INSERT INTO\s+`?\w+`?|\Z)'
  foreach ($m in [regex]::Matches($text, $pattern)) {
    $insert = $m.Groups[1].Value.Trim()
    foreach ($w in $wanted) {
      if ($insert -match "INSERT INTO\s+`?$w`?") { $result[$w] = $insert }
    }
  }
  return $result
}

New-Item -ItemType Directory -Force -Path "$Out\business", "$Out\meta", "$Out\metric" | Out-Null

# ── business：V1 商城全量 ──
Copy-Item (Join-Path $Src 'V1__init_mall.sql') (Join-Path $Out 'business\V1__init_mall.sql') -Force

# ── meta 集合 ──
# V1 meta：采集（V2 全文件）
Copy-Item (Join-Path $Src 'V2__ingestion.sql') (Join-Path $Out 'meta\V1__platform_ingestion.sql') -Force
# V2 meta：流水线/质量/指标字典（V3 中表级拆分）
$v3Tables = Split-Tables 'V3__metrics_pipeline.sql' @('pipeline_run','pipeline_stage_run','data_quality_result','metric_definition')
$v3Seed = Split-Inserts 'V3__metrics_pipeline.sql' @('metric_definition')
$metaPipeline = @('# R1 平台元数据：流水线/质量/指标字典（拆分自 V3__metrics_pipeline.sql，整改书 §7.3）', '') +
  @($v3Tables['pipeline_run'], $v3Tables['pipeline_stage_run'], $v3Tables['data_quality_result'], $v3Tables['metric_definition'], ($v3Seed['metric_definition'] -replace '\s*$', ''))
$metaPipeline | Set-Content (Join-Path $Out 'meta\V2__platform_pipeline_quality.sql') -Encoding UTF8
# V3 meta：AI 审计（V4 中两表）
$v4 = Split-Tables 'V4__ai_audit.sql' @('ai_query_history','ai_call_log')
@('# R1 平台元数据：AI 审计（拆分自 V4__ai_audit.sql，整改书 §7.3）', '', $v4['ai_query_history'], $v4['ai_call_log']) |
  Set-Content (Join-Path $Out 'meta\V3__platform_ai_audit.sql') -Encoding UTF8
# V4 meta：决策
Copy-Item (Join-Path $Src 'V5__decisions.sql') (Join-Path $Out 'meta\V4__platform_decisions.sql') -Force
# V5 meta：平台用户
Copy-Item (Join-Path $Src 'V7__security_auth.sql') (Join-Path $Out 'meta\V5__platform_users.sql') -Force

# ── metric 集合 ──
$metricTables = Split-Tables 'V3__metrics_pipeline.sql' @('metric_snapshot','metric_value')
@('# R1 指标服务：快照与指标值（拆分自 V3__metrics_pipeline.sql，整改书 §7.3/§15）', '', $metricTables['metric_snapshot'], $metricTables['metric_value']) |
  Set-Content (Join-Path $Out 'metric\V1__metric_store.sql') -Encoding UTF8
$adsTables = Split-Tables 'V4__ai_audit.sql' @('ads_operation_overview_m','ads_sale_trend_m','ads_behavior_funnel_m')
@('# R1 指标服务：ADS 物化宽表（拆分自 V4__ai_audit.sql，AI 白名单，整改书 §8）', '',
  $adsTables['ads_operation_overview_m'], $adsTables['ads_sale_trend_m'], $adsTables['ads_behavior_funnel_m']) |
  Set-Content (Join-Path $Out 'metric\V2__metric_ads_materialized.sql') -Encoding UTF8

# ── 只读账号（平台侧，§7.2 metric_read）──
@('-- R1 指标查询/AI 只读账号：仅 analytics_metric SELECT（整改书 §7.2/17.3）',
  'CREATE USER IF NOT EXISTS ''metric_read''@''localhost'' IDENTIFIED BY ''metric_read_pw_2026'';',
  'GRANT SELECT ON analytics_metric.* TO ''metric_read''@''localhost'';',
  'FLUSH PRIVILEGES;') | Set-Content (Join-Path $Out 'meta\V6__platform_reader_account.sql') -Encoding UTF8

Write-Host '拆分完成：'
Get-ChildItem $Out -Recurse -Filter '*.sql' | ForEach-Object { "  $($_.FullName.Replace($Out, '').TrimStart('\'))  ($([Math]::Round($_.Length/1KB,1)) KB)" }