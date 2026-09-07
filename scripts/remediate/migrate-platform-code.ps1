# R1 平台代码迁移（整改书 §5.2）：mall-simulator → analytics-server 模块，包名 com.graduation.mall.* → com.graduation.analytics.*
$ErrorActionPreference = 'Stop'
$src = 'D:\Develop_code\GraduationProject\mall-simulator\src\main\java\com\graduation\mall'
$modules = 'D:\Develop_code\GraduationProject\analytics-server'

# 映射：源子包 → 目标模块
$map = @(
  @{ src = 'common';       mod = 'platform-common' },
  @{ src = 'metric';       mod = 'metric-analysis' },
  @{ src = 'analysis';     mod = 'metric-analysis' },
  @{ src = 'ingestion';    mod = 'connection-ingestion' },
  @{ src = 'auth';         mod = 'connection-ingestion' },
  @{ src = 'pipeline';     mod = 'warehouse-pipeline' },
  @{ src = 'ai';           mod = 'ai-decision' },
  @{ src = 'decision';     mod = 'ai-decision' }
)

# 平台 Controller（商城 Controller 保留在 mall-simulator）
$platformControllers = @('AnalysisController','MetricController','PipelineController','AiController',
  'DecisionController','AuthController','UserAdminController','IngestionController','SpaFallbackController')

$count = 0
foreach ($m in $map) {
  $srcDir = Join-Path $src $m.src
  if (-not (Test-Path $srcDir)) { Write-Warning "缺少源包: $($m.src)"; continue }
  $target = Join-Path $modules "$($m.mod)\src\main\java\com\graduation\analytics\$($m.src)"
  New-Item -ItemType Directory -Force -Path $target | Out-Null
  Get-ChildItem $srcDir -Recurse -File -Filter '*.java' | ForEach-Object {
    $rel = $_.FullName.Substring($srcDir.Length).TrimStart('\')
    $isController = $rel -match 'controller\\' -or $rel -match 'controller/'
    $name = $_.BaseName
    # 平台控制器只迁白名单；其余（如 mall 相关 Controller）不迁
    if ($isController -and $platformControllers -notcontains $name) { return }
    $dest = Join-Path $target $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    $content = Get-Content $_.FullName -Raw -Encoding UTF8
    # 包名与 import 全局替换（保留其余结构）
    $content = $content -replace 'com\.graduation\.mall\.', 'com.graduation.analytics.'
    Set-Content $dest $content -Encoding UTF8 -NoNewline
    $count++
  }
  Write-Host "  $($m.src) -> $($m.mod): 完成"
}

# TraceContext 通用件：mall-simulator/outbox 中抽取到 platform-common（平台不宜依赖商城 outbox）
$traceFile = Join-Path $src 'outbox\TraceContext.java'
if (Test-Path $traceFile) {
  $dest = "$modules\platform-common\src\main\java\com\graduation\analytics\common\TraceContext.java"
  New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
  $c = Get-Content $traceFile -Raw -Encoding UTF8
  $c = $c -replace 'package com\.graduation\.mall\.outbox;', 'package com.graduation.analytics.common;'
  $c = $c -replace 'com\.graduation\.mall\.', 'com.graduation.analytics.'
  Set-Content $dest $c -Encoding UTF8 -NoNewline
  Write-Host '  outbox/TraceContext -> platform-common (common.TraceContext)'
  $count++
}
Write-Host "迁移完成，共 $count 个文件"