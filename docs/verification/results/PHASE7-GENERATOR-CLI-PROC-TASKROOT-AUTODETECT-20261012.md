# 阶段 7：Generator CLI 自动识别 task root

日期：2026-10-12  
RunId：`cloud_261012_012653_d3b0fc`  
工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`

## 变更

`scripts/cloud-generator-cli.sh` 在未显式设置 `CLOUD_SINGLE_NODE_ROOT` 时，扫描当前 Linux `/proc` 环境变量，仅接受同时具备生成器运行标记、数据库配置且 PID 与 `cloud-e2e/current/pids/generator.pid` 一致的候选。候选根目录必须唯一；零个或多个候选均拒绝执行。显式配置 task root 时，CLI 再校验生成器进程记录的根目录与选定根目录相同。

该行为修复了 Windows/WSL 任务启动根目录为 `/home/asus/graduationproject-runs`、而 CLI 默认工作路径不同导致无法找到活动 RunId 的情况。脚本不会把进程环境变量写入文件或输出；数据库用户名、密码及商城 token 的既有日志脱敏保持不变。

## 验证

- `bash -n scripts/cloud-generator-cli.sh`：通过。
- 当前 RunId 的 `scripts/cloud-generator-cli.sh --help`：退出码 0，自动识别并分派到活动生成器上下文。
- `mvn -o -pl platform-common '-Dtest=AnalyticsIsolationScriptsContractTest' test`：12/12 通过，0 failure、0 error、0 skipped。

## 范围限制

本次没有追加生成计划、启动生成 Run、调用商城写接口或投递 Outbox/HDFS；也没有验证真实 Mall token、平台管理员认证、采集或完整阶段 7 链路。CLI 启动使用的是 task-owned Generator 运行环境，不能据此声称没有连接 Generator 元数据数据库。项目阶段 7 与总验收保持开放。
