# 阶段 4：隔离验收路径的指标发布/只读账号分离

日期：2026-10-07

## 发现与修改

指导书 V3.0 阶段 4 第 4 项要求发布账号与只读查询账号分离。复核 `scripts/run-isolated-tests.ps1 -IncludeAnalyticsWriteIts` 发现该入口虽然显示两个配置键，但实际把同一个 metricapp 用户名和密码注入 `V25_IT_METRIC_PUBLISH_*` 与 `V25_IT_METRIC_READ_*`，未覆盖独立身份。

现 `run-isolated-tests.ps1` 分别派生隔离 `metricapp`、`metricread` 用户；缺少 `V25_IT_METRIC_READ_PASSWORD` 时，真实运行前门禁拒绝继续；门禁 6 分别探测两个账号对本次隔离指标库的连接；输出和进程环境注入使用相应账号及密码。密码只从当前进程环境读取并保持掩码输出。

同时，`it-prepare-isolation.ps1 -IncludeAnalytics` 现在也要求 `V25_IT_METRIC_READ_PASSWORD`；准备 SQL 创建独立 metricread 用户并只授予本次隔离指标库的 SELECT 权限。缺少该变量时，真实执行在写入凭据文件或连接数据库之前退出。纯预演仍可展示要求与账号名。

继续审查后发现平台运行时和 Stage 7 HTTP 验收入口仍可能绕过账号分离：`PlatformDataSources.metricReadDataSource()` 以前未核对 read 与 publish 用户名，`stage7-http-isolated.ps1` 以前把 metricapp 凭据注入两侧。现平台在 read URL 配置时 fail-closed 拒绝空/相同用户名；Stage 7 启动器要求独立只读口令并注入 metricread。

## 验证范围

- PowerShell `Language.Parser.ParseFile`：无语法错误。
- 两个脚本均通过 PowerShell Parser，无语法错误。
- `git diff --check`（本次涉及文件）：通过。
- JDK 17 离线 `mvn -o -f analytics-server/pom.xml -pl platform-app -am -DskipTests compile`：七模块 BUILD SUCCESS；未执行测试阶段。
- 未运行写入型 IT、账号授权负向探测或 MySQL 连接。
- 当前任务环境中未发现 `GRADUATION_TEST_ADMIN_PASSWORD`；WSL 3307、8091、8090、8092、10015、19083 当前无监听。未启动/停止服务、未连接数据库、未触碰持久数据，3306 未连接。

## 证据边界

编译和静态解析不证明运行态账号授权。尚未实际运行准备脚本、证明 SELECT-only 授权已经创建，也未证明 MySQL 拒绝该用户的写操作或 Stage 7 HTTP 链成功。需在受控隔离库运行准备脚本与写入型 IT，并补充只读账号权限证据。阶段 4 仍部分完成。

编辑前动态文档备份：

- `docs/PROJECT_STATUS.md.bak-20261007-metric-read-account-isolation`
- `docs/PROJECT_COMPLETION_CHECKLIST.md.bak-20261007-metric-read-account-isolation`
- `docs/verification/CURRENT_BATCH.md.bak-20261007-metric-read-account-isolation`
- 四份文档在补齐准备脚本要求前另存 `*.bak-20261007-require-metric-read-prep`。
- 本次补齐应用启动门与 Stage 7 接线前，四份文档另存 `*.bak-20261007-runtime-account-startup-guard`。
