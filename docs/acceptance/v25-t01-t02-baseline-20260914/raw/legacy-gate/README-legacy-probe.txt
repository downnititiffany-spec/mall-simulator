T01 红证原文留档（可复现性证据）
================================
生成时间: 2026-09-14 12:22:54
当前 HEAD: 3fcf90e30962ae528b2079dc5c40070f1f6b7257

本目录两个文件：
1) head-original-WarehouseNameLiteralGateTest.java.txt
   = git show HEAD:analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java
     （HEAD 仍是**修复前**的版本，本泳道的修改尚未提交，故可随时重建红证）
   sha256 = DCAC104D4F578D1E8BB2679BC279071DF64BEFB04B6E9D074613E3F0EF1568F2
2) LegacyProbe.java.txt
   = 上面这份原文 + 4 行来源说明头注释 + 类名 WarehouseNameLiteralGateTest → WarehouseNameLiteralGateLegacyProbeTest
     的**唯一**改动；构造方式与 12:16 实跑时逐字相同（Set-Content 写头 + Add-Content 追加主体）。
   sha256 = CF8ADD425F9CB2625B90C267EF63726535746D50AE5E00FC9B2DD8ABC7D9B9B0
   12:16 实跑时记录的探针 sha256 = CF8ADD425F9CB2625B90C267EF63726535746D50AE5E00FC9B2DD8ABC7D9B9B0
   重建是否与实跑字节一致: True

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
