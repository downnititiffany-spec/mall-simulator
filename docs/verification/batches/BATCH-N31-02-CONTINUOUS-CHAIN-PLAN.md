# BATCH-N31-02 — 单节点连续数仓链与维度专题（连续小链 ＋ 同源第二批/重放/失败保旧 ＋ 分类/城市等级供数 ＋ 共享 HMS 单列）

- 决策：**D-054**（N31-02 开工授权登记，2026-09-27）；范围审定 **D-053**；开工裁定原文备份 `docs/decisions/rulings/MASTER-RULING-20260927-N3102-START.md`。
- 腿③裁决：**D-058（2026-09-29）已批准按推荐方案整体实现**；裁决原文备份 `docs/decisions/rulings/MASTER-RULING-20260929-N3102-LEGC-APPROVAL.md`；实现契约见 `docs/contracts/n31-02-category-region-sales-contract-draft.md`。
- 性质：工作批次开工授权，**不是发布 V3.1**；正式权威仍为 V3.0；普通技术步骤不再逐步确认。
- 基线（run-tests.ps1，本批只跑受影响定向测试）：default=**1295**（analytics-server 1170 / mall-simulator 14 / synthetic-data-generator 111）；spark=**329**；isolated=**62**（mall 30 / generator 19 / analytics 13）。

## §0 授权与范围

- 总控 2026-09-27 批准启动 N31-02，按 D-053 所列范围执行；「不必为每个普通技术步骤再等一次确认」。
- 全程只用隔离环境；**3306 永久冻结零接触**；不格式化既有 HDFS；不覆盖历史证据；不 push；V25_IT_* 口令零落盘、3307 root 仅 credref 通道；`*.bak-*` 永不入库；不修改已冻结的 V3.0 正文；不将待审 V3.1 写成已发布。
- 完成连续链后继续推进下一切片；只有触及未定的表结构或业务口径时停下该切片提交具体差异（腿③即此类切片），其余工作照常。

## §1 四腿判据总表

| 腿 | 判据 | 通过标准 | 状态 |
| --- | --- | --- | --- |
| ① | 连续小链一次对账 | 受控输入→Flume→HDFS→平台摄取→Spark 分层→ADS→隔离 3307 ACTIVE→API→页面，对账容差 0.0005；血缘四元组齐全 | **PASS**（LEG A；WSL 单节点限定） |
| ① | oracle 发布前钉档 | 独立 oracle（方法论+期望值）在发布/对账前写死并 sha256 钉档 | **PASS**（LEG A；14/14 四方核对） |
| ② | 同源第二批 | 第二批摄取→第二 snapshot，血缘不混 | **PASS**（LEG B；batch2/run2/S20260918_2） |
| ② | 重放 no-op | 同 manifest 重放零新快照（ALREADY_CONSUMED 类） | **PASS**（LEG B；run3；ACTIVE 不变） |
| ② | 失败保旧快照 | 发布腿故障→FAILED，旧 ACTIVE 不变、旧快照可读 | **PASS_WITH_HARNESS_CORRECTION**（行为 PASS；原驱动断言误报，原 FAIL 原貌保留，独立只读证据复核通过） |
| ③ | 契约冻结 | 分类/城市等级实现契约（粒度/字段/unknown/表/映射/API/页面/oracle） | **PASS（D-058 批准）**；进入实现 |
| ③ | 设计差异门控 | 新增表或改口径前交设计差异，切片停止 | **PASS（D-058 已裁决推荐语义）**；仅加性迁移，不改历史迁移/V3.0 |
| ③ | Spark ADS 与服务表 | Hive 叶子分类/城市等级 ADS producer、MetricAdsCatalog、MySQL ADS 镜像与发布 | **PASS**（LEG C；10 张 ADS 导出、33 行；分类/城市等级表各 3 行） |
| ③ | API/UI/AI evidence | 销售 API 窗口聚合；销售页分类/城市等级可视化；结构化 EvidencePackage 来源正确 | **PASS_WITH_LIMITATION**（API/UI/AI 证据来源定向测试通过；真实 LLM 未测，分类名称缺失） |
| ③ | 独立 oracle 与小链 | 独立 oracle→Spark/Hive→服务库→API→页面对账；未知项、比例和失败保旧覆盖 | **PASS_WITH_LIMITATION**（LEG C；单日 oracle 与服务库/API 一致；样本/未知项/故障注入边界见结果） |
| ④ | 共享 HMS | Spark+Hive 共用同一 Metastore（非 Derby）；资源不足如实记未通过 | **PASS_WITH_CLEANUP_CORRECTION**（结果见 `docs/verification/results/BATCH-N31-02-LEGD-RESULT.md`；WSL 单节点限定） |

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

## §4 腿③ 分类/城市等级专题（契约已批准，实现与小链验证结果）

- **C1/C2 已完成，C3 已由 D-058 批准**：契约规定叶子分类与城市等级、保留 unknown、跨日不伪造 distinct、两个新增 ADS/服务镜像表、独立 oracle 与端到端验收。
- **实现顺序**：① 锁定 Hive DDL/分区与 Spark `AdsSql` producer；② 接入现有 `fna` 编排、导出 manifest/catalog、增加新的加性 Flyway migration 和 MetricPublisher 列映射；③ 实现服务层按窗口聚合分类/城市等级金额与件数并重算占比；④ 更新 Vue 销售页；⑤ 修正结构化 AI EvidencePackage 的 ADS 来源标签，不扩展 Text2SQL 语义白名单；⑥ 增加 Spark/Java/Web 定向测试和独立 oracle。
- **实现约束**：不编辑已发布 Flyway V1–V33、不修改冻结 V3.0；查询按 `snapshotId` pin，日期端点含首尾；category `-1`/缺维表只进入唯一 unknown；多日 `buyer_count/order_count` 不返回或不得命名为窗口 distinct；金额比例基于窗口聚合后计算；Hive/服务表缺失与合法空结果必须可区分；任一 ADS 发布失败时旧 ACTIVE 保持可读。
- **最小端到端验收**：使用新 runId、隔离 schema/证据目录和 30–50 行正常样本 + 5–10 行坏样本；独立 oracle 在发布前钉档；逐层核对 Spark/Hive 分区、导出制品、MySQL ADS、API 与浏览器数值，金额容差 `<0.01`，比率容差 `<0.0005`；保留未知分类、缺失城市等级、跨日重复买家/订单、全额退款与未支付退款金额等边界案例。

### LEG C 执行结果摘要（2026-09-29）

- D-058 批准的代码路径已接通：Hive `dw_ads.ads_category_sale` / `dw_ads.ads_region_sale` → MetricAdsCatalog/导出与 MySQL V13 镜像 → `AnalysisService.sales` 窗口聚合 → Vue 销售页；EvidenceBuilder 测试引用正确 ADS 来源及 snapshot/window。实现边界与未验收项见 `docs/verification/results/BATCH-N31-02-LEGC-RESULT.md`。
- 新隔离运行 `n3102e_20260929_173000_7c11` 完成 99 行/34,192 B Flume→HDFS→摄取→Spark→ADS→MySQL 3307→API→Chromium；batch=1、run=1、snapshot=`S20260918_1`，14 项 overview 指标，ACTIVE 唯一，ADS 导出 10 表/33 行。独立分类/城市等级 oracle 的金额和件数与 MySQL/API 一致，窗口比例按窗口 GMV 重算。
- 修复的根因：当日无新增注册事件时，`DimensionBuildJob` 原先跳过用户 as-of 快照，造成历史用户 `city_level` 在 DWD 关联中变成 unknown。现在无论当日用户输入数是否为 0，均按业务日回看 ODS 历史生成用户快照；回归断言 `user=0->1` 且 `city_level=tier2`。
- 定向测试：`ProductDimensionAsOfSpec` 3/3、`CategoryRegionAdsSpec` 1/1、`AnalysisServiceTest` 41/41、EvidenceBuilder/ExplanationEvidence 合计 16/16、`MetricPublisherBuildFailureCompensationTest` 3/3、web `chartOptions.test.js` + `aiEvidenceWindow.test.js` 29/29。
- **状态为 PASS_WITH_LIMITATION**，不降格成无条件 PASS：本次复用 99 行全有效样本，未包含计划规定的坏行；本次真实业务日浏览器数据未出现 unknown 桶（其规则由 Spark 回归覆盖）；夹具无分类名称，API 如实返回 `UNKNOWN`；分类图没有完整进入 viewport 截图；新增 V13 表上的失败注入没有作为本腿真实链路重跑。另 pipeline `WAIT_LANDING` stage evidence 存相对 accepted URI、没有完整 `hdfs://` scheme（摄取结果与 A3 证据有完整 HDFS URI）。这些差异均登记在 LEG C 结果，不改变实测事实。
- 持久证据切片 `v3-archive/n3102/legc-20260929-7c11/`；其 manifest 对 14 项文件做 SHA-256 核验。此目录不是 N31-02 全批最终归档；全批归档仍须汇总 A–D 并按 D-048 规则收口。

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
