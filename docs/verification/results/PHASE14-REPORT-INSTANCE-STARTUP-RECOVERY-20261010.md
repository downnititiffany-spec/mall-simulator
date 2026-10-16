# 阶段14：业务报告实例启动恢复与默认快照回归（2026-10-10）

## 现场与根因

- 当前 task-owned RunId 为 `cloud_261010_061315_23e7b3`。在用户授权的 PID 37185 重启后，平台 PID 44406 的启动日志记录 `analytics_meta` 执行 2 个迁移脚本并到版本 38（V37/V38）；随后 Spring 因缺少 `ReportInstanceMapper` Bean 而退出。日志：`/home/asus/graduationproject-runs/cloud-e2e/cloud_261010_061315_23e7b3/logs/platform.log`。
- 在 `AnalyticsApplication` 现有 `@MapperScan` 列表中补入 `com.graduation.analytics.report.mapper`，修复报告服务无法注入的问题。
- 首轮报告 API 定向测试暴露默认快照读取回归：请求未传 `snapshotId` 时，EvidenceService 按 G-03 返回当前 ACTIVE 快照，但 ReportInstanceService 错误地要求 `null` 等于返回 ID；这同时遮蔽了日期不匹配用例的预期错误。
- 修改为仅当请求明确传入 `snapshotId` 时才比较请求与证据快照 ID。未指定时仍要求返回证据具有非空快照 ID、source、定义版本和匹配业务日；默认快照解析继续由 EvidenceService 执行。

## 验证

- 定向 Maven Reactor 回归（platform-app 及 `-am` 依赖模块）：`ReportInstanceServiceTest` 5/5、`AiFeedbackServiceTest` 4/4、`AiControllerFeedbackAnchorTest` 1/1、`AiFeedbackMigrationScriptTest` 1/1、`BusinessReportMigrationScriptTest` 1/1、`ControllerPermissionCoverageTest` 5/5；合计 17/17 PASS，失败/错误/跳过均为 0，`BUILD SUCCESS`（2026-10-10 15:33:26 +08:00）。
- Maven 7 模块 Reactor `-DskipTests package`：`BUILD SUCCESS`（2026-10-10 15:34:00 +08:00）；本次打包跳过测试，测试结果以上一条为准。
- `scripts/cloud-single-node.sh status`：隔离 MySQL 3307、HDFS/YARN、Hive 服务及 Web 5173/5174 等仍监听；平台 8091 closed，8090/8092 仍监听。当前不是完整平台栈健康状态。
- 当前 Windows Codex Process/User/Machine 环境与 WSL 均未发现平台 Meta、Metric Publish、Metric Read 的 URL/用户名/密码环境变量（共9项缺失）；未读取或输出任何凭据值。没有再次启动平台或访问数据库。

## 边界

- V37/V38 的执行结论限定为本 RunId 平台启动日志所记录的 Flyway 结果；本轮没有直连数据库核对迁移历史。MySQL 3306 未连接，未重置凭据，未改 profile 或清理任何 RunId 数据。
- 当前包已包含报告 Mapper 扫描和默认 ACTIVE 快照请求修复，但因平台凭据在任务/WSL环境中不可见，尚未用该包再次启动服务；真实 HTTP、数据库 owner 隔离、浏览器报告流程仍未验证。
- 阶段14及阶段4、阶段6、阶段8和项目总验收保持开放；本结果不构成最终验收。
