# 生成最终关键数字汇总（从日志本体抽取，避免手抄）
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Set-Location 'D:\Develop_code\GraduationProject'
$raw = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-t01-t02-baseline-20260914\raw'
$out = Join-Path $raw 'final-31-key-numbers.txt'
$files = @(
    'final-01-t01-red-legacy-probe.log',
    'final-11-t01-warehouse-gate-green.log',
    'final-12-verbatim-platform-common-test.log',
    'final-13-verbatim-platform-app-am-test.log',
    'final-16-spark-jobs-jdk8-test.log',
    'final-23a-t02-contract-group.log',
    'final-23b-t02-runtime-patrol-group.log',
    'final-23c-t02-tamper-method.log',
    'final-24-full-reactor-collect.log',
    'final-26-verbatim-full-reactor-test.log',
    'final-29-platform-app-test-compile.log',
    'final-30-verbatim-platform-app-am-test.log'
)
$pat = 'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$|Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+ -- in|BUILD SUCCESS|BUILD FAILURE|SUCCESS \[|FAILURE \[|SKIPPED|Total number of tests run|Suites: completed|All tests passed|^### EXIT=|^### 命令|^### 开始|<<< (FAILURE|ERROR)!$|Failed to execute goal'
"# 关键数字（从日志本体抽取）  生成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')   HEAD=$(git rev-parse HEAD)" |
    Set-Content -Encoding UTF8 $out
foreach ($f in $files) {
    $p = Join-Path $raw $f
    "`n===== $f =====" | Add-Content -Encoding UTF8 $out
    if (-not (Test-Path $p)) { "（缺失）" | Add-Content -Encoding UTF8 $out; continue }
    Select-String -Path $p -Pattern $pat | ForEach-Object { $_.Line.Trim() } | Add-Content -Encoding UTF8 $out
}
# 探针
foreach ($f in 't01-probe.log', 't02-probe.log', 't02-tamper-negative.log', 't02-tamper-negative-firstrun.log') {
    $p = Join-Path $raw "probe\$f"
    if (-not (Test-Path $p)) { $p = Join-Path $raw $f }
    "`n===== $f =====" | Add-Content -Encoding UTF8 $out
    if (Test-Path $p) { Get-Content $p | Select-Object -Last 12 | Add-Content -Encoding UTF8 $out } else { "（缺失）" | Add-Content -Encoding UTF8 $out }
}
# landing 复核
"`n===== landing/manifests 复核 =====" | Add-Content -Encoding UTF8 $out
Get-Content (Join-Path $raw 'final-27-landing-untouched-final.txt') | Add-Content -Encoding UTF8 $out
# 工作树脏指纹
"`n===== 工作树（仅本包相关，确认未提交）=====" | Add-Content -Encoding UTF8 $out
(git status --porcelain | Where-Object { $_ -match 'warehouse|ingestion|v25-t01-t02' }) | Add-Content -Encoding UTF8 $out
"`n===== HEAD 序列（证据窗口）=====" | Add-Content -Encoding UTF8 $out
'09d70468d2f5e229e33cc150c2731dd64a750b20  12:19  final-10a（窗口内最早登记）' | Add-Content -Encoding UTF8 $out
'31b4b65e542c1b872925a6f3a5378bce983222c9  12:19  final-11' | Add-Content -Encoding UTF8 $out
'eb08ed6a6e7fbe995ff362a9250fce9dc660743a  12:20  final-14' | Add-Content -Encoding UTF8 $out
'3fcf90e30962ae528b2079dc5c40070f1f6b7257  12:23  final-20a' | Add-Content -Encoding UTF8 $out
'52a0e2ffe8830fd49205e39308335260c2876579  12:30  final-23b（当前）' | Add-Content -Encoding UTF8 $out
Write-Host "written $out"
(Get-Item $out).Length
