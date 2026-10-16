# 阶段4 RuntimeProfile Hive 配置缺失激活门禁（2026-10-10）

## 范围

验证 SINGLE_NODE 候选 RuntimeProfile 缺少 Hive JDBC URL 时，服务层激活流程 fail-closed：返回 `ACTIVATE_CHECK_FAILED`，诊断指出 `hive: UNKNOWN: 未配置 hive_jdbc_url`；候选保持 DRAFT；不更新档案、不查询当前 ACTIVE 列表，也不解析凭据。

## 方法与结果

- 新增 `RuntimeProfileHiveActivationGateTest`：1/1 PASS。
- 同时运行 `RuntimeProfileHiveProbeRedactionTest`：5/5 PASS。
- 命令：`mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am '-Dtest=RuntimeProfileHiveProbeRedactionTest,RuntimeProfileHiveActivationGateTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 汇总：6 tests, 0 failures, 0 errors, 0 skipped；三模块 reactor `BUILD SUCCESS`；退出码 0。
- 仅新增测试，无生产代码变更；未启动/停止服务、未连接数据库、未调用真实 API，未访问 MySQL 3306。

## 证据边界

该 Mockito 服务层回归证明缺配置情况下当前代码拒绝激活，不证明真实认证 API、数据库状态、HiveServer2 或实际 profile 切换。阶段4和项目总验收保持开放。
