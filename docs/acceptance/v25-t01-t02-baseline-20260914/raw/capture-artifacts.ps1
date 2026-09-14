# ① 红证的可复现性：LegacyProbe 原文留档 + ② 等长字节改写负例（sha256 依赖证明）
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
chcp.com 65001 | Out-Null
Set-Location 'D:\Develop_code\GraduationProject'
$raw = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-t01-t02-baseline-20260914\raw'
$gateRel = 'analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java'

# ── ① 用与 12:16 完全相同的构造方式重建探针原文，并核对 sha256 必须等于当时的 CF8ADD42... ──
New-Item -ItemType Directory -Force -Path (Join-Path $raw 'legacy-gate') | Out-Null
$header = @'
// 【临时探针，非交付物】本文件由 V25-T01 证据脚本从 HEAD 逐字取出并仅改类名：
//   git show HEAD:analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java
// 用途：把"修改前的门禁实现"原样跑一遍，作为 T01 的**红证**（证明旧口径确实判红这两行）。
// 取证后立即删除，不留在工作树里（删除记录见 README §2.2）。
'@
$head = git show "HEAD:$gateRel"
$body = ($head -join "`n").Replace('class WarehouseNameLiteralGateTest', 'class WarehouseNameLiteralGateLegacyProbeTest')
$rebuilt = ($header + "`n" + $body)
$rebuiltPath = Join-Path $raw 'legacy-gate\LegacyProbe.java.txt'
# 用与当时相同的 Set-Content -Encoding UTF8 + Add-Content 两步拼接结果做字节级重建
$tmp = Join-Path $env:TEMP 'LegacyProbe.rebuild.java'
$header | Set-Content -Encoding UTF8 $tmp
$body | Add-Content -Encoding UTF8 $tmp
Copy-Item $tmp $rebuiltPath -Force
$rebuiltHash = (Get-FileHash $rebuiltPath -Algorithm SHA256).Hash
$head | Set-Content -Encoding UTF8 (Join-Path $raw 'legacy-gate\head-original-WarehouseNameLiteralGateTest.java.txt')
$headHash = (Get-FileHash (Join-Path $raw 'legacy-gate\head-original-WarehouseNameLiteralGateTest.java.txt') -Algorithm SHA256).Hash

$note = Join-Path $raw 'legacy-gate\README-legacy-probe.txt'
@"
T01 红证原文留档（可复现性证据）
================================
生成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
当前 HEAD: $(git rev-parse HEAD)

本目录两个文件：
1) head-original-WarehouseNameLiteralGateTest.java.txt
   = git show HEAD:analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java
     （HEAD 仍是**修复前**的版本，本泳道的修改尚未提交，故可随时重建红证）
   sha256 = $headHash
2) LegacyProbe.java.txt
   = 上面这份原文 + 4 行来源说明头注释 + 类名 WarehouseNameLiteralGateTest → WarehouseNameLiteralGateLegacyProbeTest
     的**唯一**改动；构造方式与 12:16 实跑时逐字相同（Set-Content 写头 + Add-Content 追加主体）。
   sha256 = $rebuiltHash
   12:16 实跑时记录的探针 sha256 = CF8ADD425F9CB2625B90C267EF63726535746D50AE5E00FC9B2DD8ABC7D9B9B0
   重建是否与实跑字节一致: $($rebuiltHash -eq 'CF8ADD425F9CB2625B90C267EF63726535746D50AE5E00FC9B2DD8ABC7D9B9B0')

红证日志: final-01-t01-red-legacy-probe.log（Tests run: 3, Failures: 1, EXIT=1）
重建命令（任选其一，均**不**需要在源码树里留文件）:
  # 方式 A：临时放回源码树再跑再删（12:16 用的方式）
  Copy-Item "raw\legacy-gate\LegacyProbe.java.txt" "analytics-server\platform-common\src\test\java\com\graduation\analytics\warehouse\WarehouseNameLiteralGateLegacyProbeTest.java"
  mvn.cmd -o "-Dmaven.repo.local=D:\maven_repository" -f analytics-server/pom.xml -pl platform-common -am -Dtest=WarehouseNameLiteralGateLegacyProbeTest -Dsurefire.failIfNoSpecifiedTests=false test   # 期望 FAILURE：2 处注释误命中
  Remove-Item "analytics-server\platform-common\src\test\java\com\graduation\analytics\warehouse\WarehouseNameLiteralGateLegacyProbeTest.java"
  # 方式 B：不进源码树，用 javac 直接编译并运行 raw\probe\T01Probe.java（独立复算，不改源码树）
  #   注意口径差异：T01Probe 打印的 RAW 行是"旧门禁规则"的独立复算（同一正则、不剥离注释），
  #   它能复现"旧口径命中 2 处"，但不是 12:16 那个 JUnit 用例本身；要逐字复现 JUnit 红证请用方式 A。

为何不长期保留为 @Disabled 用例:
  本仓已有 discipline「不留死代码/死用例」；且平台内已有更严格的 test-isolation 守卫（其他泳道新建）
  会扫描测试源码。故选择"留全文于证据目录 + 源码树内不残留"，红证可由上面两条命令随时重建。
"@ | Set-Content -Encoding UTF8 $note
"① 重建探针 sha256=$rebuiltHash  与实跑一致=$($rebuiltHash -eq 'CF8ADD425F9CB2625B90C267EF63726535746D50AE5E00FC9B2DD8ABC7D9B9B0')"
"① HEAD 原版 sha256=$headHash"
