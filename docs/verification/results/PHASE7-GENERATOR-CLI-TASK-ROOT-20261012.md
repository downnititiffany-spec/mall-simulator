# 阶段 7 Generator CLI 任务根解析修复（2026-10-12）

## 发现与修复

- 当前 task-owned RunId 位于 `CLOUD_SINGLE_NODE_ROOT=/home/asus/graduationproject-runs`，但 `scripts/cloud-generator-cli.sh` 原先固定读取 `/workspace/single-node/cloud-e2e/current`，与 `cloud-single-node.sh` 的可配置根不一致，导致 CLI 无法识别当前登记的 generator。
- 脚本现与单节点启动器一致，优先读取 `CLOUD_SINGLE_NODE_ROOT`，未设置时仍回退 `/workspace/single-node`。
- 首次 `bash -n` 检查同时暴露脚本 CRLF 使 Python heredoc 终止符失配；文件改为 LF，并在 `.gitattributes` 固定该脚本为 LF，避免 Windows checkout 再次破坏脚本解析。
- `AnalyticsIsolationScriptsContractTest.cloudGeneratorCliUsesTheConfiguredTaskRoot` 固化任务根契约。

## 验证

- `bash -n scripts/cloud-generator-cli.sh`：PASS。
- 配置根分派探针：用临时 task-root 和当前 shell PID 构造最小登记；脚本从配置根找到登记后在 generator 环境完整性检查处按预期 exit 3。探针未启动 JVM、未访问数据库，并清除临时目录。
- `mvn -o -pl platform-common -Dtest=AnalyticsIsolationScriptsContractTest test`：12/12 PASS，0 failure/error/skipped，BUILD SUCCESS。
- `git diff --check`：无空白错误。

## 边界

该修复只恢复 CLI 对已登记 task root 的识别；没有执行 CLI 的 plan/run 命令、没有创建业务数据或登录商城，也不证明 Stage 7 商城→Outbox→Flume/HDFS→平台→Spark 当前 RunId 链路。3306未访问；当前服务与数据库未改动。完整业务链仍待后续完成。