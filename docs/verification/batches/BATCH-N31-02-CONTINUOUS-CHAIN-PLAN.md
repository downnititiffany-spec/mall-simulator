# BATCH-N31-02 — 单节点连续数仓链与维度专题（连续小链 ＋ 同源第二批/重放/失败保旧 ＋ 分类地区契约冻结 ＋ 共享 HMS 单列）

- 决策：**D-054**（N31-02 开工授权登记，2026-09-27）；范围审定 **D-053**；开工裁定原文备份 `docs/decisions/rulings/MASTER-RULING-20260927-N3102-START.md`。
- 性质：工作批次开工授权，**不是发布 V3.1**；正式权威仍为 V3.0；普通技术步骤不再逐步确认。
- 基线（run-tests.ps1，本批只跑受影响定向测试）：default=**1295**（analytics-server 1170 / mall-simulator 14 / synthetic-data-generator 111）；spark=**329**；isolated=**62**（mall 30 / generator 19 / analytics 13）。

## §0 授权与范围

- 总控 2026-09-27 批准启动 N31-02，按 D-053 所列范围执行；「不必为每个普通技术步骤再等一次确认」。
- 全程只用隔离环境；**3306 永久冻结零接触**；不格式化既有 HDFS；不覆盖历史证据；不 push；V25_IT_* 口令零落盘、3307 root 仅 credref 通道；`*.bak-*` 永不入库；不修改已冻结的 V3.0 正文；不将待审 V3.1 写成已发布。
- 完成连续链后继续推进下一切片；只有触及未定的表结构或业务口径时停下该切片提交具体差异（腿③即此类切片），其余工作照常。

## §1 四腿判据总表

| 腿 | 判据 | 通过标准 | 状态 |
| --- | --- | --- | --- |
| ① | 连续小链一次对账 | 受控输入→Flume→HDFS→平台摄取→Spark 分层→ADS→隔离 3307 ACTIVE→API→页面，对账容差 0.0005；血缘四元组齐全 | 待执行 |
| ① | oracle 发布前钉档 | 独立 oracle（方法论+期望值）在发布/对账前写死并 sha256 钉档 | 待执行 |
| ② | 同源第二批 | 第二批摄取→第二 snapshot，血缘不混 | 待执行 |
| ② | 重放 no-op | 同 manifest 重放零新快照（ALREADY_CONSUMED 类） | 待执行 |
| ② | 失败保旧快照 | 发布腿故障→FAILED，旧 ACTIVE 不变、旧快照可读 | 待执行 |
| ③ | 契约冻结 | 分类/地区实现契约文档（粒度/字段/unknown/表/映射/API/页面/oracle） | 待交总控 |
| ③ | 设计差异门控 | 新增表或改口径前交设计差异，切片停止 | 门控 |
| ④ | 共享 HMS | Spark+Hive 共用同一 Metastore（非 Derby）；资源不足如实记未通过 | 待执行 |

## §2 腿① 连续小链（A1–A9）

- **A1 受控输入钉档**：选定/复用一份受控输入样本（优先 `fixtures/source-a-e3` 系或其派生小样本），计算 sha256 并写入本批结果文档；全程不改动输入文件。
- **A2 oracle 钉档（发布前）**：先于任何发布动作，用独立路径（plain Python 复算/手工 SQL 复核）确定期望指标值与方法论（ORACLE.md 式），sha256 钉档；对账时不得回改 oracle。
- **A3 Flume→HDFS**：WSL 单节点既有 HDFS（`hdfs://127.0.0.1:19000`，**不格式化**），Flume 1.11.0（BATCH-U run root，`-Xmx20m` 覆写、setsid 防 SIGHUP）把受控输入落到 landing 目录；记录 HDFS 路径与字节数/校验和。
- **A4 平台摄取**：隔离栈平台（8091）摄取 landing 文件，产出 manifest（batchId）；记录 sourceId、batchId、摄取日志证据。
- **A5 Spark 分层**：经 pipeline run（runId）执行分层（ODS→DWD→DWS→ADS），LocalProcessSparkSubmitter；记录 runId 与各层产物路径/行数。
- **A6 隔离 3307 发布**：ADS 结果发布至隔离 3307（analytics_metric 库 metric_snapshot），snapshotId 置 ACTIVE；记录 snapshotId 与 14/14 指标可读证据。
- **A7 API**：平台 metrics API 返回该 snapshot 数据（envelope body.data.data 两层）；记录响应摘要与 snapshotId 一致性。
- **A8 页面**：Playwright chromium 打开页面，验证 Overview 单元格与 staleness 横幅状态；截图入证据。
- **A9 对账**：oracle 期望值 vs ADS/服务库/API/页面四方对账，容差 **0.0005**；血缘四元组（sourceId、manifest batchId、pipeline runId、snapshotId）写入对账表。**不要求 Flume 与平台共用字面相同 runId。**

## §3 腿② 同源第二批 ＋ 重放 ＋ 失败保旧

- 复用 G31-11/12 隔离栈机制与既有小样本；同一 source 第二批摄取产生第二 snapshot；随后同 manifest 重放应 no-op（零新快照）；再把发布腿故障化（如临时移走 spark jar）确认 run FAILED 且旧 ACTIVE 不变、旧快照 14/14 可读。
- **只跑受影响的定向测试**；不得把不同运行腿拼接成「一次连续链通过」——每条腿独立留痕。

## §4 腿③ 分类/地区专题（契约先行，C1–C3）

- **C1 勘察**：现状 `AnalysisService.sales` 返回两空数组 + `UNKNOWN_DIMENSION_TABLE` 告警；梳理 DWS/ADS 现有分类/地区字段、前端页面与 AI 白名单现状。
- **C2 契约冻结**：产出实现契约文档——分类/地区粒度、来源字段（来源表与列）、`unknown` 归属规则、ADS 与 MySQL（服务库镜像）表结构草案、发布映射、API 契约、页面呈现、独立 oracle 方法。
- **C3 交总控停**：**新增表或改变指标口径前，先交设计差异供总控审定**；本切片停在契约提交，不建表、不跑数据、不阻塞腿①②。

## §5 腿④ 共享 HMS（单列判据）

- WSL 单节点尝试 Spark 与 Hive 共用同一 Metastore 服务（MySQL/PostgreSQL 后端均可，禁止用嵌入式 Derby 冒称）。
- 资源不足（Hive 不可用/内存不足）→ 在结果文档**如实记未通过**，注明资源约束；不因此否定腿①已单独通过的连续链。

## §6 环境红线与机制

- 隔离 MySQL 3307：`daemon3307.sh`（mysqld --daemonize，asus；root 口令仅经 `credref-mysql3307-root.properties` 引用，零回显）；WSL mysql 经 `wsl.exe -d Ubuntu bash -c "export MYSQL_PWD=…; mysql -h127.0.0.1 -P3307 …"`。
- HDFS：既有实例 `hdfs://127.0.0.1:19000`（conf `target/fast-dev-20260924/hdfs-smoke/conf/`）；**不格式化**；stop DN→NN 需 HADOOP_PID_DIR；平台 JVM 缺 HADOOP_USER_NAME 时沿用 server-side chmod 777 变通（先重启平台）。
- Flume 1.11.0：BATCH-U run root；`flume-ng -Xmx20m` 覆写 JVM 内存；setsid 防 SIGHUP。
- 平台 8091 / metric 库 analytics_metric（metric_snapshot 在此，非 analytics_meta）；终验锚 S20260901_23 恢复/维护脚本 mini-gate 硬编码不随本批改动漂移。
- 页面验证：Playwright chromium（`C:/Users/ASUS/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe`）；点击用真实 DOM `.click()`；截图用 viewport。

## §7 提交与归档计划

- 分组本地提交（显式路径、绝不 `-A`、不 push）：登记组（本计划 + D-054 + 状态文档）→ 腿①结果与证据 → 腿② → 腿③契约文档 → 腿④结果 → 收口（PROJECT_STATUS 切片更新）。
- 结果文档：`docs/verification/results/BATCH-N31-02-LEG{A,B,C,D}-RESULT.md` 随腿登记；证据（HDFS 校验和、manifest、run 日志摘要、API 响应摘要、截图、对账表）入 `v3-archive/n3102/`（收口时按 D-048 口径归档，credref/`.zcode`/`.git` 零入档）。
- `*.bak-*` 编辑前备份随编辑创建、永不入库。

## §8 风险与已知坑

- MSYS 路径改写：Git Bash 下 `-D` 反斜杠路径被转换坏，Maven 用正斜杠 `D:/maven_repository`；tar 输出到 `D:/...` 视作远端主机——先 cd 再相对路径。
- WSL 变量必空静默失败：跨 WSL 命令写脚本文件经 stdin/文件传递，不内联 `$var`。
- Spark local 回环 jar 下载挂起：executor 自下载 job jar 永久阻塞；沿用 watcher 诊断模板，避免触发条件。
- 控制台 GBK 乱码仅显示层；比较输出形状再定性，不过度申报异常。
- 平台常驻 JVM 无 HADOOP_USER_NAME：沿用 chmod 777 服务端变通并优先先重启平台。
- `git commit -- pathspec` 不认未跟踪文件：先显式 `git add` 路径。
- 孤儿进程/端口占用：起 Flume/Spark 前 ss 查 19000/8091/3307 占用现状。
