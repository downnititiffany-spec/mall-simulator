# WP-F2b 修复轮 2（总控亲跑验收不合格，2026-10-16 22:3x）

## 不合格事实（总控亲跑，RunId wpf2bfix20261016；FIX1 的 @() 修复已生效）
- prepare exit 0、门禁 6 四探针全过、供给步启动，但 FlyMigrate 对 meta 库 exit=1：`Packet for query is too large (7,566,701 > 65,535)`（裸 JDBC 最小复现同错、包长恒定；服务端 @@max_allowed_packet=64MB 正常）。
- 总控根因确证：本 VM 上 JVM 默认以 IPv4-mapped IPv6 拨 127.0.0.1，连接被沙箱拦截后字节流错位（见 TOOLS.md「Java/Maven egress」节；Maven 侧靠 MAVEN_OPTS 的 -Djava.net.preferIPv4Stack=true 规避）。运行器以裸 `java -cp ... FlyMigrate.java` 直调供给器，未带该标志。总控手动以同一 classpath、同一 URL/账号加 `-Djava.net.preferIPv4Stack=true` 复跑：meta 迁移 **37 步成功**（flyway_schema_history success=37）。

## 修复要求（仅限 scripts/run-isolated-tests.ps1 中 WP-F2b 供给步的 java 调用处）
1. 两处 FlyMigrate 调用（meta 与 metric）的 java 参数中加入 `-Djava.net.preferIPv4Stack=true`（置于 -cp 之前即可）。这是本机 JVM 网络栈规避，与 Maven 侧 MAVEN_OPTS 口径一致；不改变任何其他语义。
2. 不改 FlyMigrate.java、不改其他文件、不改步数/失败契约与基线数字。
3. 你只有文件读写权限，不要运行任何命令；验收由总控以全新 RunId 亲跑 `-Module analytics -IncludeAnalyticsWriteIts` 收编腿（要求 analytics-schema meta=37 metric=13、analytics 计数 13 全绿、runner exit 0）。

## 完成标准
上述修复落码；完成后在回复末尾输出一行：WPF2BFIX2-DONE
