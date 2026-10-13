# 阶段 4 分页离线回归与隔离栈启动（有限现场证据）

日期：2026-10-06（Asia/Shanghai）

## 仓库基线

- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`
- 分支 / HEAD：`codex/cloud-v3-development` / `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f`；fetch 后 HEAD 与 origin 分支 `0/0`。
- 启动前工作树已有 276 条 status 短项（含目录折叠的未跟踪路径）。本轮未改业务源码、测试源码或冻结文档；新增验证报告与动态状态记录，target 下截图/脚本为本地运行材料。既有改动未覆盖。

## 运行环境

- WSL Ubuntu 已有工具链路径不符合脚本默认 Maven 路径 `/workspace/toolchains/maven/mvn/bin/mvn`。显式设定 `CLOUD_MAVEN_BIN=/usr/bin/mvn` 后，`bash -n scripts/cloud-single-node.sh` 与 `preflight` 均通过。此前启动尝试遇到 5174 响应超时后再核查无 Windows/WSL 监听；未接管未知 PID。
- 统一脚本启动新隔离 RunId：`cloud_261006_074130_535d3b`，目录 `/home/asus/graduationproject-runs/cloud-e2e/cloud_261006_074130_535d3b`。`status` 报告 3307、HDFS 19000/19010/19870/19864/19866、Metastore 19083、平台/商城/生成器 8091/8090/8092、Vite 5173/5174 均 LISTEN。
- 统一脚本未启动 HiveServer2 10015；该端口的 RuntimeProfile 实服探测已有另一 RunId 的方法级证据。本次不把 Metastore 19083 当作 HiveServer2。
- 使用 Playwright Chromium 打开 `http://127.0.0.1:8091/login`：HTTP 200，页面异常和 console error 均为 0。没有登录，也没有调用受保护列表 API；非空用户/AI审计浏览器验证仍待可用的隔离管理员认证。
- 任务脚本仅建立本 RunId 的隔离服务、schema 与 HDFS 前缀；本轮未手动查询/写入数据库，未访问 3306，未格式化或删除历史数据。任务栈目前仍运行以供后续接续。

## 当前工作树定向回归

- Java：JDK 17、Maven 离线七模块 reactor，定向执行 `AuthServiceUserPaginationTest,UserAdminPaginationHttpTest,AiControllerAuditPaginationTest,MetricControllerPaginationHttpTest,MySqlMetricStorePaginationTest,PipelineControllerPaginationTest,DecisionServicePaginationTest`，共 **15/15 PASS**，0 failure/error/skipped，BUILD SUCCESS。测试使用 mock/HTTP harness，不连接本次真实数据库。
- Web：`node --test tests/opsPipelinePagination.test.js tests/pipelineAsyncFeedback.test.js`，**9/9 PASS**，0 failed/skipped。
- 边界：这两组为离线回归，不证明新运行库中的真实非空用户/AI审计数据、平台异步流水线持久化轮询、快照非空翻页或阶段 4 整体验收。
- 编辑动态文档前已分别备份 `docs/PROJECT_STATUS.md.bak-20261006-phase4-pagination-live-retest` 与 `docs/verification/CURRENT_BATCH.md.bak-20261006-phase4-pagination-live-retest`。
