# N31-02 WSL 恢复与隔离 MySQL / HDFS 专项验证补记

日期：2026-09-30（Asia/Shanghai）  
工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `feature/v3-development`，基线 HEAD `300c4c9b7bfc25b8a058c62ad89f349bb15121b8`。  
状态：补充运行证据；N31-02 腿③仍为 `PASS_WITH_LIMITATION`，本文件不构成总控签收。

## 范围与安全边界

- 用户批准仅重启 Ubuntu WSL，并批准通过已配置的安全环境凭据运行隔离 MySQL 写 IT。执行只使用 WSL MySQL `127.0.0.1:3307`；Windows MySQL `3306` 未连接、未执行 SQL。
- HDFS 使用既有 Hadoop 3.3.4 单节点 NameNode/DataNode 和既有元数据目录 `/home/asus/.cache/graduation-hdfs-smoke-20260924`。未格式化、未清空或替换 NameNode/DataNode 目录。
- NameNode 冷启动按 Hadoop 正常恢复流程读取既有 edits、生成 checkpoint，并按保留策略清理过期 fsimage；日志显示最终 txid 3947。此为服务启动产生的正常元数据维护，不是格式化或本次手工删除。
- MySQL RunId 隔离库及账号保留；不执行测试库清理。HDFS 唯一临时测试目录已在确认空目录后用非递归 `rmdir` 删除。
- 真实远程集群、共享 HMS、Flume→HDFS→平台→Spark→ADS→MySQL→API→页面同一 runId 的完整链路，均不由本补记宣称通过。

## WSL 与 HDFS 运行时

Ubuntu WSL 经用户批准的 `wsl --terminate Ubuntu` 后恢复，`/bin/echo` 探针成功。首轮直接从短命 WSL shell 调用 Hadoop daemon 后，NameNode 与 DataNode 均在约两秒后收到 `SIGHUP` 并正常退出；两份首轮日志见 `target/v25-it/n3102wsl_20260930_1722/hdfs-logs/hdfs-session-hup-*.log`。因此并非 HDFS 元数据损坏或端口冲突。

根因是 daemon 子进程仍受该次 WSL 命令会话生命周期影响。改以 `setsid --fork` 建立独立 Linux session 后，NameNode/DataNode 在 WSL 命令结束后仍持续运行，DataNode 注册并完成 block report，NameNode 约 35 秒后退出 safe mode。后续跨调用的 `jps`、端口检查与 `hdfs dfs -ls /` 均成功。当前服务端口为 NameNode RPC 19000、Web 19870、DataNode 19010；Hadoop 客户端提示未发现 `log4j.properties`，但不影响读写测试。

## HDFS LandingStorage 集成测试

- 扩展 `HdfsLandingStorageIT` 的真实 HDFS 断言：事件文件 URI 与 manifest URI 必须完整保留运行命名空间前缀（包含 `hdfs://authority`），而不只是包含相对路径片段。
- 使用显式测试命令运行 opt-in `HdfsLandingStorageIT`，结果 **1/1 PASS**，无失败、错误或跳过。测试实际执行 HDFS health check、目录/文件操作、定位读取、文件身份和 manifest 幂等冲突检查。
- Maven/Surefire 报告：`target/v25-it/n3102wsl_20260930_1722/HdfsLandingStorageIT.txt` 与 `TEST-HdfsLandingStorageIT.xml`。NameNode/DataNode 两轮日志亦复制到同目录 `hdfs-logs/`。
- 测试客户端在 Windows 上以 Hadoop SIMPLE 身份 `ASUS` 访问；原有 HDFS namespace 的目录属 `asus`，所以测试只在全新 `/tmp/n3102-hdfs-uri-20260930-1800` 下建立权限为 700、所有者为 `ASUS` 的临时根目录。IT 自身删除唯一 `it-4961bd20-8f56-47dc-bd82-936fd642048c` 子目录；随后核实临时根目录为 0 子目录、0 文件、0 字节，再执行非递归 `rmdir`。既有 `/landing` 等目录权限未改变。

## 隔离 MySQL 写 IT

- 第一轮 RunId `n3102mysqlit_20260930_1646_a1` 暴露测试断言与隔离脚本契约不一致：MySQL 用户名超过 32 字符时，`New-IsolationUserName` 会截断并追加稳定哈希；`IsolationGuardMySqlIT` 仍错误地期待未截断的 `${RunId}_mallapp`。测试断言已改为与安全脚本实际导出的 `ENV_USER` 比较，并保留长 RunId 说明。
- 第二个全新 RunId `n3102mysqlit_20260930_1703_b2` 通过仓库官方隔离 runner 执行，四个必需类合计 **13/13 PASS**：`AnalyticsIsolationFlywayIT` 2/2、`IsolationGuardMySqlIT` 6/6、`MetricAdsMySqlIT` 2/2、`MetricPublisherMySqlIT` 3/3。包含正式库拒绝、scope 外数据库拒绝、发布激活及失败补偿路径。
- 隔离输出位于 `target/v25-it/n3102wsl_20260930_1722/isolated-analytics.log` 与 `isolated-analytics-schema.log`；第二 RunId 的 4 个隔离 schema 均使用 RunId 前缀。输出与运行日志对本次临时口令扫描命中数为 0，口令未回显、未写入文档。
- 首轮 RunId 测试对象仍保留，以便审计；本补记未删除任何数据库对象。该证据限于 RunId 隔离 schema，不代表共享或正式业务库迁移状态。

## 相关测试与代码变更

- 定向 Maven 常规测试：`platform-common` 117 项，`connection-ingestion` 383 项，合计 500 项；失败 0、错误 0、跳过 2，构建成功。此为两个相关模块测试，不是整个仓库全档基线。
- 本轮测试代码变化：`IsolationGuardMySqlIT` 以脚本生成的规范账号为断言口径；`HdfsLandingStorageIT` 增加完整 HDFS URI 前缀断言。生产 Java / Spark / Vue 业务实现未修改。
- HDFS 守护进程首次退出日志、Windows SIMPLE 用户名不匹配、PowerShell/Maven 参数转义错误均如实保留在本次过程结论中；最终成功轮次单独留有 Surefire 报告与 HDFS 日志。

## 尚未闭合的验收边界

本补记证实 `HdfsLandingStorage.uri()` 在真实 HDFS 运行时返回包含 `hdfs://` 的完整路径，并证实当前代码使用该接口构造 `WAIT_LANDING.acceptedStorageUri`。但本补记没有在平台实际 HDFS profile 下创建并读取 `pipeline_stage_run.evidence` 行，因此“真实 pipeline 阶段记录中落库的 `acceptedStorageUri` 值”仍未直接实测。N31-02 腿③据此继续保持 `PASS_WITH_LIMITATION`；下一步若要消除此单项限制，应在 RunId 隔离 MySQL + 现有 HDFS 单节点上运行最小 WAIT_LANDING 流水线，回读阶段证据并确认 scheme、authority、无 userinfo/query/fragment。

测试证据根：`target/v25-it/n3102wsl_20260930_1722/`。不 commit、不 push；冻结 V3.0 正文未改。
