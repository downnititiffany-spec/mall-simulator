# WP-F2b 运行器 schema 供给步（规约）

## 背景（总控 WP-F2 亲跑结论，2026-10-16）
分析侧三类隔离写 IT（MetricPublisherMySqlIT 3、MetricAdsMySqlIT 2、AdsDataQualityErrorRateNullableMySqlIT 2）以完整 V25_IT 契约亲跑 **7/7 PASS**，但前提是总控先手工用 Flyway 把隔离双库预迁移到位（meta 37 步、metric 13 步）。这批 IT 自身不建 schema，`scripts/run-isolated-tests.ps1` 的 analytics 预收编腿（`-IncludeAnalyticsWriteIts`）也不做 schema 供给——这是收编一直未落地的真实缺口。本包补齐供给步，使收编腿开箱即跑。

## 必读
- `scripts/run-isolated-tests.ps1`：analytics 目标定义（requireClasses 在 -IncludeAnalyticsWriteIts 时追加两类…以文件现状为准）、`$analyticsWrite` 预收编段（metaDb/metricDb/账号派生规则、hiveNamespace、scopeRoot）、V25_IT_* 环境变量注入与探针段。
- `scripts/it-prepare-isolation.ps1` 的 `-IncludeAnalytics` 路径（双库与三账号的创建/授权口径；analytics 账号对各自库是 ALL PRIVILEGES——供给步以应用账号跑 Flyway 即可，不需要 root）。
- 迁移脚本位置：`analytics-server/platform-app/src/main/resources/db/meta`（V1..V38）、`analytics-server/platform-app/src/main/resources/db/metric`（V1..V13）。

## 交付
1. 新增单文件 Java 供给器 `scripts/flyway/FlyMigrate.java`：JDK17 单文件源码启动（`java FlyMigrate.java <jdbcUrl> <user> <password> <fsLocation>`），内部用 Flyway API 对指定库 migrate 并打印实际执行步数；无其他自创文件。
2. 修改 `scripts/run-isolated-tests.ps1`（仅此一个既有文件）：当且仅当 `-IncludeAnalyticsWriteIts` 生效时，在跑 analytics 腿之前，对 `${RunId}_analytics_meta` 与 `${RunId}_analytics_metric` 依次执行 Flyway 预迁移：
   - 凭据只用既有环境变量 V25_IT_META_PASSWORD / V25_IT_METRIC_PUBLISH_PASSWORD 与派生账号名（metaapp/metricapp），不落盘、不回显；
   - classpath 从本地 Maven 仓库（沿用脚本既有参数/派生方式定位，如无则新增 `-MavenRepoLocal` 参数并给与 run-tests.ps1 一致的默认值风格）按件名 glob 定位：flyway-core、flyway-mysql、mysql-connector-j、gson、jackson-databind/core/annotations、protobuf-java；任一缺失即 Fail 5 并点名缺哪个，不得静默跳过；
   - JDBC 指向 127.0.0.1:3307（脚本既有 DbHost/Port 参数），且迁移前必须先通过脚本既有的指纹探针（htch-runtime:3307），探针不过不迁移；
   - 迁移失败（非 0 退出或步数异常）即整腿 Fail，退出码与既有契约一致；迁移步数写入该腿日志与控制台摘要一行（meta=N1 metric=N2）。
3. 不改三类 IT 本身、不改 prepare 脚本、不改基线数字；未带 `-IncludeAnalyticsWriteIts` 时行为与现版逐字一致（供给步零介入）。

## 环境约束
- 你只有文件读写权限，不要尝试运行任何命令（构建与实跑由总控亲跑验收：总控将以新 RunId 跑 `-Module analytics -IncludeAnalyticsWriteIts` 收编腿，要求三类写 IT 真跑全绿且 analytics 计数口径与登记一致）。
- Flyway 版本以本地仓库现存件为准（9.22.3 系），不要写死版本号，用 glob 取最高版本目录。

## 完成标准
上述 1–2 落码且与 3 不冲突；完成后在回复末尾输出一行：WPF2B-DONE
