# WP-F5b 规约：3307 白名单清理机制（F-93-08 实施包）

## 背景与总控裁决
- F-93-08：3307 隔离实例遗留库/账号清理。总控既有裁决：走 `-AllowedCleanDbs` 白名单清理，禁止手写 DROP（`docs/PROJECT_STATUS.md` L2106/L2109）。
- WP-F5 盘点已证：`scripts/run-isolated-tests.ps1` 无此参数、全脚本零 DROP，属真实缺口。本包落地它。
- **总控裁决（本包定案）**：不改 `run-isolated-tests.ps1`（避免动运行器契约），新建独立脚本 `scripts/it-clean-isolation.ps1`，参数名沿用裁决原名 `-AllowedCleanDbs`。清理执行（连库真跑）由总控亲跑，你只写文件、不运行任何命令、不连接任何数据库。

## 交付物（仅此 1 个新文件，必要时 +1 处契约测试最小修正）
`scripts/it-clean-isolation.ps1` —— PowerShell 7 脚本，风格/门禁/退出码与 `scripts/run-isolated-tests.ps1`、`scripts/it-prepare-isolation.ps1` 对齐。动手前必须通读这两个脚本与 `scripts/isolation-naming.ps1`，凭据获取方式、mysql 客户端调用方式、探针 SQL 写法一律照抄既有模式，不自创。

## 功能与硬门禁（逐条实现，缺一不可）
1. 参数：`-AllowedCleanDbs [string[]]`（必填，精确库名数组，不接受通配符——含 `%`/`*`/`_` 转义绕过尝试的字符串一律拒绝；注：库名本身含下划线属正常，只拒 `%` 与 `*`）、`-Port`（默认 3307）、`-InstanceUuid`（必填，无默认值）、`-DbHost`（默认 127.0.0.1）、`-MysqlExe`（默认与 run-isolated-tests.ps1 相同）、`-Confirm`（开关）、`-LogDir`（可选）。
2. **端口门禁**：Port 必须严格等于 3307，否则立即以退出码 5 拒绝；任何参数（含 DbHost 解析结果以外的任何字段）出现 3306 字样亦拒绝。正式库端口常量对照写法照抄 run-isolated-tests.ps1 的 `$FormalPort` 模式。
3. **指纹探针先行**：执行任何 DROP 前，先以既有脚本同款探针（`SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@hostname)`）核对 @@port=3307 且 @@server_uuid 等于 `-InstanceUuid`，不符以退出码 5 拒绝。凭据只从环境变量读（照抄 it-prepare-isolation.ps1 的变量名与用法），脚本内不得出现任何口令字面量，日志不得打印口令。
4. **库名形状门禁**：每个待清理库名必须全匹配 `^[A-Za-z][A-Za-z0-9_]*_(mall|generator|analytics_meta|analytics_metric)$`，且其 runId 前缀（去掉上述后缀后的部分）必须匹配 `^[a-z][a-z0-9]*_\d{8}_\d{4}(_[a-z0-9]+)?$`（即必须是带日期_时间段的隔离 runId 形状；`live20261016_analytics_meta` 这类无日期段的在跑平台库形状上即被拒绝）。系统库（mysql/sys/information_schema/performance_schema）显式拒绝。
5. **存在性核对**：探针阶段先 `SELECT SCHEMA_NAME FROM information_schema.SCHEMATA` 核对每个白名单库真实存在；不存在的报错退出（码 7），不得静默跳过后声称成功。
6. **账号联动**：对每个通过门禁的库，由其 runId 前缀 + `isolation-naming.ps1` 的 `New-IsolationUserName` 推导四种角色账号（mallapp/genapp/metaapp/metricapp），仅对 `mysql.user` 中真实存在的推导账号执行 `DROP USER IF EXISTS`；不接受任何自由传入的账号名参数。
7. **DryRun 默认**：无 `-Confirm` 时只打印拟执行的完整 DROP 清单（库 + 账号）并以码 0 退出，绝不执行任何 DROP。有 `-Confirm` 时在执行前重跑一次指纹探针，通过后逐项执行并逐项打印结果，末尾打印清理计数汇总。
8. **SQL 构造**：库名/账号名进入 SQL 前必须经上述正则校验且以反引号包裹标识符；禁止把任何未校验字符串拼进 SQL。
9. 退出码：0=成功（含 DryRun 完成）；5=门禁拒绝（端口/指纹/形状/系统库/通配符）；7=执行期失败（探针连不上、白名单库不存在、DROP 失败）。

## 契约测试核对
- 找到 `AnalyticsIsolationScriptsContractTest`（在 analytics-server 测试树内自行定位），核对它是否对 `scripts/` 目录清单或某脚本内容有精确断言。若新脚本使其断言失效，做最小限度的对应更新并在交付说明中写明改了哪一行、为什么；若无影响，不动任何测试文件并明确声明无影响。

## 约束
- 你只有文件读写工具：不运行命令、不连库、不碰 3306/3307、不改 PROJECT_STATUS.md、不改 run-isolated-tests.ps1 / it-prepare-isolation.ps1 / isolation-naming.ps1。
- 交付说明中列出：新脚本的参数与门禁实现对照（逐条对应上面 9 条）、契约测试核对结论。
- 完成后在回复末尾输出一行：WPF5B-DONE
