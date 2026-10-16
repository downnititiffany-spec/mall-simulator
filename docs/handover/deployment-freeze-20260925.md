# 部署交接冻结（V3.1 G31-07 / 07.1）

- **冻结日期**：2026-09-25（机器证据时钟）
- **冻结依据**：V3.0 指导书阶段 8 第 ① 项「冻结实际部署拓扑、版本、端口、数据目录、服务账号、启动/停止及恢复步骤」（L187）；G31-07 批次计划 `docs/verification/batches/BATCH-G31-07-HANDOVER-ACCEPTANCE-PLAN.md`
- **冻结范围声明**：本文档冻结的是**实际已验证部署形态**（LOCAL 真实链路版 + WSL 单节点 HDFS 档）。REMOTE_CLUSTER 档、YARN/多节点、真实共享 Hive Metastore、真实 LLM provider **未交付**，见 §8 与 07.3 验收报告；不得表述为已具备。
- **口令纪律**：本文档零明文口令。所有凭据以下列指称方式登记：见 §5。

## 1. 代码与构建物版本（SHA 台账）

| 项 | 值 |
|---|---|
| git HEAD | `b25b47f`（2026-09-24 08:17 +0800，`chore(backup): checkpoint v3 development workspace`） |
| 工作树状态 | feature/v3-development；**16 个文件未提交改动原样保留**（未 commit 未 push）——冻结版本=HEAD+该未提交改动集合，交接时按现状整树移交 |
| platform jar | `analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar`，SHA256 `f6d0c3a407f55dc50b3e984e7114601a5a361aadd0d9c6ed8d2f28521a7ce6ce` |
| spark-jobs jar | `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar`，SHA256 `b5554d7e93426a4d8d2658e2a07c9cde898f3f5cac0a20512155ab9e356c1ee8` |
| 前端 | mall-simulator（8090），构建入口 `scripts/build-web-and-package.ps1`（`docs/reference/deployment.md`） |
| 数据库迁移 | platform-app 内嵌 Flyway（meta/metric 两库），当前至 `V29__quality_rule_ads_staging_present_v2.sql`（未提交改动中的追加式新迁移） |

双 jar SHA 与 G31-04/G31-05 批次钉死值一致（05.1 预检复核记录）。

> 【2026-09-25 更正（D-044）】上表「工作树状态」行的 **16 个文件**为本会话初始快照的过时计数：该组质量规则 v2 改动（含 V29）实际已提交入库（V29 为已跟踪文件，非未提交改动）。冻结时点工作树实际未提交改动 = **34 个已跟踪文件**（30 个产品/夹具/脚本 + 4 个登记文档，+1412/−172）+ 一批新增未跟踪文件（9 个 Java 源/测试、交接/计划/结果文档与备份）。该在途改动集按交接指令原样保留、G31-04~07 零触碰；完整归属清单见 `docs/verification/results/G31-07-DEPLOY-HANDOVER-ACCEPTANCE-RESULT.md` §9。冻结的 HEAD SHA 与双 jar SHA 摘要不受本更正影响。

## 2. 拓扑与端口（冻结实况，2026-09-25 14:36 复核）

```
Windows 宿主（开发/验收机）
├─ 平台后端 platform-app      :8091  java.exe PID 50980（JDK 17.0.12，D:\Develop\JAVA17）
│    └─ wrapper cmd.exe PID 41292 —— 身份链见 target/v25-it/g3103_20260920_052106/platform.identity.json
│       （marker=g3103_20260920_052106, driver=item3-restore-g3103, jarSha256=f6d0c3a4…）
├─ stub LLM（设计内替身）      :18080 python.exe stub_llm.py PID 42848（D:\Develop\Python314）
├─ 商城模拟器 mall-simulator   :8090  （未常驻；按 §6 启停步可拉起）
└─ V3 前端（platform web）    :8091 静态资源随 platform-app 分发
WSL2（Ubuntu 26.04）
├─ MySQL 8.0.41（隔离档）      :3307  mysqld PID 422，--datadir=/data/mysql-isolated/data，bind 127.0.0.1
├─ Hadoop 3.3.4 单节点 HDFS    :19000 (fs.defaultFS) / :9870 (NN UI) —— **当前停机**（G31-05 收口终态）
│    数据目录 /home/asus/.cache/graduation-hdfs-smoke-20260924/（零格式化，保留）
├─ Spark 3.5.1（local[1]，JDK 8u351，/opt）
└─ Flume 1.11.0（/home/asus/stage7u_20260919_170729/tools/apache-flume-1.11.0-bin）
⚠ :3306 —— 永久冻结，**零接触红线（含只读探测，5.1.5）**；本拓扑任何组件不得连接/探测该端口。
```

- 平台运行档位：runId `g3103_20260920_052106`，数据域 RunId `stage7q1_20260918_152245`（`<dataRunId>_analytics_meta` / `_analytics_metric` 两库，metric_read 与 publish 同库）。
- ACTIVE 指标快照：`S20260921_20`（唯一 ACTIVE，14 指标，qualityStatus=PASS）——G31-07 07.2 终验已复核。
- HDFS 档（G31-05 已验证形态）：`hdfs://127.0.0.1:19000`，clusterID `GP-SMOKE-20260924`，1 NN/1 DN/replication=1/无 YARN；验证完停机，档案保留。

## 3. 数据目录（冻结）

| 目录 | 用途 |
|---|---|
| `target/v25-it/g3103_20260920_052106/` | 常驻平台 run 根：`logs/platform.log`（F2b 守卫对象）、`landing/`、`landing-local/`、`spark-warehouse/`、`derby-metastore-w/`、`metric-staging/`、`samples/`、`platform.identity.json` |
| `target/v25-it/stage7q1_20260918_152245/` | 数据域生产者/producer 证据（钉死 producer result） |
| WSL `/data/mysql-isolated/` | 3307 mysqld datadir + sock/pid/log（**禁止清理**） |
| WSL `/home/asus/.cache/graduation-hdfs-smoke-20260924/` | HDFS NN/DN 存储（零格式化约束） |
| `target/v25-it/g3105_20260925_121316/` | G31-05 WSL HDFS 整链证据（HDFS warehouse 树、metric-export、Flume conf） |
| `target/v25-it/g3104b_20260925_104346/` | G31-04 故障恢复证据（F-G4-1 回归发现链） |
| `target/v25-it/g3107_20260925_142915/` | G31-07 07.2 终验证据（10 份 JSON + drill-state PASS + 驱动脚本） |

旧 runId 档案一律**不清理、不 DROP**（无范围清理禁令）。

## 4. 服务账号（零明文；指称方式冻结）

| 账号 | 凭据来源 | 使用边界 |
|---|---|---|
| 平台 DB `stage7q1_20260918_152245_metaapp/metricapp/metricread` | 进程内生成 secret（V25_IT_* 命名通道），仅经 env 注入 JVM，**零落盘/零 argv/零日志** | 仅 3307 两库 |
| 平台登录（admin/operator/analyst 三角色） | `docs/reference/deployment.md` 登记的演示账号 | 演示/验收用；口令以该文档为准，本文不复写 |
| 3307 MySQL root | 「W03 受控登记的文档化值」 | 仅限 WSL mysql 进程 env（MYSQL_PWD）内使用；不用于应用查询 |
| mall DB | `docs/reference/deployment.md`（root→mall_simulator 模型） | 商城模拟器独立库 |
| LLM API key | stub-local-key（非敏感演示值） | 日志只记后 4 位；真实 provider 凭据未配置（D-039 BLOCKED） |

## 5. 启动 / 停止 / 恢复步骤（冻结）

### 5.1 正式部署形态（干净机器，`docs/reference/deployment.md` 为源）
1. 构建：`pwsh -NoProfile -File scripts/build-web-and-package.ps1`（产出双 jar + 前端）。
2. 起库：MySQL 3307 隔离档（WSL `mysqld --no-defaults --basedir=/opt/mysql-8.0.41 --datadir=/data/mysql-isolated/data --user=asus --port=3307 --bind-address=127.0.0.1 --socket=/data/mysql-isolated/mysql.sock --pid-file=/data/mysql-isolated/mysql.pid --log-error=/data/mysql-isolated/log/mysqld.log --tmpdir=/data/mysql-isolated/tmp --mysqlx=0 --skip-log-bin --daemonize`）。
3. 起平台：`pwsh -NoProfile -File scripts/start-all.ps1`（含商城 8090）；或单起 platform（§5.3 形态）。
4. 健康门：`GET :8091/api/v1/health` → `data.ok=true`；登录冒烟（演示账号）。

### 5.2 常驻验收栈恢复（本冻结对应的 g3103 栈；冷启动全流程）
1. stub LLM：`python stub_llm.py`（18080，`/healthz` 200 为就绪）。
2. 3307 mysqld 按 §5.1 第 2 步拉起。
3. 执行恢复驱动：`pwsh -NoProfile -File target/v25-it/item3/item3-restore-g3103.ps1 -Confirm`（幂等 prep → mini-gate → guard 14 变量 → 起平台 → 身份 JSON → AI 探针）。
4. mini-gate 判据（S20260921_20 锚点）：ACTIVE 快照唯一 =`SNAP|S20260921_20|ACTIVE`；decision_task ≥ 10（记录实际值）；sys_user = 3。
5. F2b 日志守卫（每次启动后必做）：`logs/platform.log` 尾部 200KB 零 `:3306`；必须出现 `jdbc:mysql://127.0.0.1:3307/…_analytics_meta`、`…_analytics_metric` 与 landing root 证据（斜杠两变体均认可）。

### 5.3 平台进程启停（Windows 侧）
- **停**：先读 `platform.identity.json` 拿 wrapperPid → 核验 PID/CommandLine 台账一致 → `taskkill /PID <wrapper> /T /F`（连子 JVM）；**禁止**按端口盲杀。
- **起**：`cmd.exe /c ""<JDK17>\bin\java.exe" -Dfile.encoding=UTF-8 -Dplatform.metric.publish.export-dir=<run根>/metric-staging -jar platform-app-0.1.0-SNAPSHOT.jar >> platform.log 2>&1"`，env 走 `PLATFORM_META_URL/PLATFORM_METRIC_PUBLISH_URL/…` 14 变量（`scripts/assert-platform-env.ps1` 正例门禁）。
- **核验**：`Get-NetTCPConnection -LocalPort 8091` 属主 java.exe 且 ParentProcessId==wrapperPid。

### 5.4 WSL HDFS 档启停（按需，非常驻）
- 起：`setsid nohup hdfs --daemon start namenode|datanode`（防 SIGHUP）；**不格式化**，JDK8u351，`HADOOP_USER_NAME=asus`。
- 停：必须带与本 run 启动一致的 `HADOOP_PID_DIR`（否则 pid 文件找不到、stop 静默无效——D-042 复证项）；成功判据 `HDFS-PROCS-GONE`（jps 零 NN/DN）。

### 5.5 故障恢复语义（G31-04 已验证）
- 平台进程崩溃重启：同 jar 同 env 重启后 `reconcileOnStartup` 将 RUNNING→RUN_INTERRUPTED、孤儿 Spark SJR→UNKNOWN/JOB_ORANED，证据落库；对 FAILED run 用 mark-failed + resume-from-stage 续跑。
- mysqld(3307) 中断：平台存活但 meta-ds 校验失败刷屏、API 优雅降级（INTERNAL 系统繁忙）；恢复后 API 自愈，中断期被吞的状态写用 mark-failed 补齐。
- Spark 作业失败：retry-from-stage（SJR JobResult 逐作业台账）。

## 6. 运维绑定约束（红线，交接后必须继续遵守）

1. **:3306 永久冻结零接触**——含只读探测（5.1.5）；任何脚本/人工不得连接或探测该端口。
2. **D-042 两条**：WSL 档 MetricExportJob `--exportDir` 必须传 `file:///` 绝对 URI；Derby `create=true` 的 metastore 目录**不得预建**（空目录也触发 XBM0J）。
3. 不执行无范围 DROP；不清理旧 runId 档案与 HDFS 既有数据。
4. 冻结的 V3.0 指导书/设计文档不可原地修改；架构/表/API 级新增先写变更请求交总控（当前待裁决变更请求：F-G4-1 INSERT OVERWRITE static 模式回归 = D-040；file_identity VARCHAR(64) 列宽 = D-041）。
5. V25_IT_* 口令只在进程内生成/读取（零落盘/零 argv/零日志）；平台 secret env 不入日志。
6. Git Bash 环境坑：`.sh` 生成后先 sed LF 修正再 bash；`/mnt/d` 路径禁 `..`；pwsh 需绝对 `D:/` 路径；Maven `-D` 反斜杠路径会被 MSYS 转换（用正斜杠）。
7. Spark local 模式 executor 自下载 job jar 回环挂起（动态端口 1024–15000 异常）已知环境坑，诊断模板见项目记忆。

## 7. 冻结时点核验记录

- 2026-09-25 14:36（07.2 终验，9/9 腿 PASS）：8091 健康/登录冒烟 OK；ACTIVE=S20260921_20 唯一；overview 14 指标 == item4 指纹（容差 0.0005）；F2b 零 ：3306；stub LLM 探针 providerUsed=stub-local；身份链 java(50980)←wrapper(41292) marker=g3103。证据：`target/v25-it/g3107_20260925_142915/`。
- 8090（商城）与 HDFS（19000）冻结时点停机，属「可拉起的交付组件」而非常驻故障。

## 8. 未交付项（交接边界）

- REMOTE_CLUSTER 环境档（另立批次，滚动清单 #5）。
- 真实 LLM provider 接入（D-039 BLOCKED：无受控凭据，不得伪造；stub 本地替身已交付且零外呼零费用）。
- YARN / 多节点 / 共享 Hive Metastore（V3.0 设计 §5.1 明确不要求，但本版未验证）。
- 论文/答辩材料与演示流程：按 V3.0 阶段 8 第 ④ 项在结果冻结后统一编写（post-freeze 工作）。
- 后续 Doris 对比实验：不伪装成本版已实现能力。
