# PHASE4-RUNTIMEPROFILE-METRIC-PROBE-20261007

## 发现与修复

`RuntimeProfileServiceImpl` 的契约注释说明运行档案测试/激活要检查 MetricStore 查询，但 `checkMetric()` 原来只调用了 `healthCheck()`，只证明数据源上的 `SELECT 1` 可达，并未走 `metric_snapshot` 的 latest ACTIVE 查询。候选 profile 通常仍是 DRAFT/TESTING，如果调用默认查询还可能错查另一个 ACTIVE profile。

现改为以候选档案持久化 ID 调用 `MetricStore.query(new MetricQuery(null, true, profileId))`，直接覆盖该档案的快照查询路径。缺少有效数据库 ID 时 fail-closed，不使用默认 profile。正常空结果可接受：profile 暂无 ACTIVE 指标快照不代表读查询本身失败。

## 验证

定向命令：

`mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am '-Dtest=RuntimeProfileProbeRedactionTest,RuntimeProfileHiveProbeRedactionTest,RuntimeProfileSnapshotSourceFreezeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

8/8 PASS，0 failure/error/skipped；覆盖候选 profile ID、无持久 ID拒绝、异常正文脱敏、Hive 凭据与 source snapshot 冻结。

模块回归命令：

`mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am test`

connection-ingestion 392 tests，0 failure、0 error、2 skipped；platform-common 与 connection-ingestion reactor `BUILD SUCCESS`。

## 证据边界

均为 JDK 17 离线测试/mock；未启动服务或连接真实数据库，也未部署并执行 RuntimeProfile 浏览器/API 测试。当前 WSL 统一脚本 status 显示旧 RunId `cloud_261006_074130_535d3b` 的所有登记服务端口 closed；preflight 因默认工具根路径缺失 JDK、配置定向后因当前 WSL 没有 Node.js 拒绝继续。旧 RunId 数据目录保留，未停止/删除/复用。管理员变量仍不可见，未读取任何密码。3306 未连接或探测。

## 环境状态更正（2026-10-07 后续复核）

前述“preflight 因没有 Node.js 拒绝”被后续核验更新：Codex Windows Node v24.19.0 可由 WSL interop 执行，且在工作树下运行 Vite 5.4.21 `--version` 成功。将该可执行路径仅作为 `CLOUD_NODE_BIN` 后，脚本 preflight 继续运行并报告 `HDFS RPC address does not match the configured port`。当前 `/opt/hadoop-3.3.4` 默认配置与任务脚本要求的 RPC 19000/指定 HDFS_RUN 身份未对齐；发现的历史 WSL HDFS 配置使用 9100 且目录布局不符合脚本的 name/data 检查。未临时改写旧配置、未启动服务、未碰触 HDFS 数据。真实认证变量仍不可见。此后实际阻塞应记录为“安全可复用 HDFS namespace/config 无匹配项”，不是 Node.js 缺失。
