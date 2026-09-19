# BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN — Controller Review

> Overall: **PASS**——SpoolDir → Flume 1.11.0（File Channel）→ HDFS（1NN/1DN，run-scoped）实链全程真实执行：主链 1011 行 event_id 逐项对账 **零丢失/零新增/零重复**；kill -9 恢复子例两次 attempt 均 **LOST=0**（重复如实登记：attempt-1 恰 500、attempt-2 零净超额）；全程零 MySQL、零 platform/Spark、`/opt` 与 3306 未触碰。
> Exact tested SHA: `104db41117ea251b5b8e6c1f32c9f61ca4acd659`（计划钉定基线；执行 HEAD `f136af5` 为 docs-only 前移，按计划不触发重钉；本批零仓内代码/配置变更）
> RunId: `stage7u_20260919_170729`（恢复子例 `<RunId>-rec`）
> 执行窗口: 2026-09-19 17:07–17:55 (+0800)，WSL run root `/home/asus/stage7u_20260919_170729/`
> 执行授权: 总控 2026-09-19 裁决二「BATCH-U 同时批准执行，不需要再回来等第二次确认。推送完成并确认远端 HEAD 为 f136af5 后，直接按计划 Phase 0–6 连续执行，直到得到 PASS、明确的 FAIL/BLOCKED_ENV，或遇到新的 HARD DECISION。」

## 1. Phase 0 预检与执行环境

- RunId `stage7u_20260919_170729` 执行时新生成；目标 HDFS 父路径执行前不存在（首条 `hdfs dfs -ls` 报 No such file）。
- 输入 = S-R1 pinned 1011 行完成文件只读副本：SHA256 `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`（与 Batch T/S-R1 钉死值逐字节一致），drop 采用 copy，源文件只读。
- WSL 实测：JDK11（`/usr/lib/jvm/java-11-openjdk-amd64`）、`/opt/hadoop-3.3.4` 出厂空配置（二进制复用、配置零触碰）、NN RPC 9100 预检空闲。

## 2. Phase 1：run-scoped HDFS（1NN/1DN）

- conf 全部 run-scoped 副本（`$R/hadoop-conf`）：core-site `fs.defaultFS=hdfs://127.0.0.1:9100`；hdfs-site `dfs.replication=1`、`dfs.namenode.name.dir=file://$R/hdfs-nn`、`dfs.datanode.data.dir=file://$R/hdfs-dn`、`dfs.permissions.enabled=false`、NN http 127.0.0.1:9870；hadoop-env.sh 副本追加 `HADOOP_LOG_DIR=$R/logs/hadoop`、`HADOOP_PID_DIR=$R/logs/pids`。**`/opt/hadoop-3.3.4/etc/hadoop` 原文件零修改，无系统服务注册。**
- FORMAT_EXIT=0；NN/DN 起动；`jps` NameNode 2840 + DataNode 2943；`ss` 证实 9100 LISTEN（另 9870/9864/9866/9867）；`hdfs dfsadmin -report` **Live datanodes (1)**。实际 RPC 端口由运行时证实与钉定 9100 一致。证据：`evidence/phase1-hdfs-runtime.txt`。

## 3. Phase 2：Flume 下载、run conf 与 agent 启动

- Flume 1.11.0 官方 SHA512 前置校验通过（official = computed = `a825020fa6f95332911a294fca098813d2f2ca371d2321b0ffd7297b6a4fcc11b1b819258364d84071ee8baa591cc12982c39c0e6c7793fecfaae4893d99a268`），解压至 **run-scoped** `$R/tools/`（总控边界钉死 run-scoped，覆盖计划 L50 的 /opt 表述——如实登记）。hadoop client jars 经 `HADOOP_HOME=/opt/hadoop-3.3.4` 注入 classpath（CNFE=0）。
- run conf（`flume/flume-spooldir-run.conf` 及 `-rec` 变体）由仓内模板 `ingestion/flume/flume-spooldir.conf`（104 行 LF，零修改）派生，**diff 恰 8 行（4−/4+）= 恰 4 处路径替换**：spoolDir→`$R/spool[-rec]`、checkpointDir→`$R/channel[-rec]/checkpoint`、dataDirs→`$R/channel[-rec]/data`、hdfs.path→`hdfs://127.0.0.1:9100/stage7u_stage7u_20260919_170729[-rec]/landing/raw/dt=%Y%m%d/hour=%H`。无第 5 处差异（FAIL_CONF 不适用）。证据：`evidence/conf-diff.txt`、`evidence/conf-diff-rec.txt`。
- **HDFS 路径字面量推导登记**：计划 L45/L88 钉死路径含 `stage7u_<RunId>` 字面量；RunId 本身以 `stage7u_` 为前缀，机械展开即得双重前缀 `/stage7u_stage7u_20260919_170729/...`——同时满足「含 RunId 全字面量子串」两种文义，未做任何超出模板的改写。
- **事件 1（launch env fix，非钉定属性变更）**：agent 首次起动即死（`Exception in thread "main"` 空体，jps 无 Application）；classpath 正确（CNFE=0），根因 = flume-ng 脚本第 229 行硬编码 `-Xmx20m` 堆 OOM。export JAVA_OPTS 被 229 行覆写无效；修正 = `-c` conf 目录内 run-scoped `flume-env.sh` `export JAVA_OPTS="-Xmx512m"`（flume-ng 326–331 行在默认值之后 source，官方覆写点）+ `log4j2.xml` 拷入同目录。**`-f` run conf 未动、diff 判据不受影响。** 首次失败日志留证：`evidence/flume-agent.first-fail-xmx20m.log`。
- 修正后 agent 三组件全部就绪（`evidence/flume-main-agent.log`）：`FileChannel.start:289 - Queue Size after replay: 0`、`Component type: SINK, name: landingSink started`、`SpoolDirectorySource source starting with directory: /home/asus/stage7u_20260919_170729/spool`、`Component type: SOURCE, name: spool started`。进程存活由优雅停止时 agent-shutdown-hook 完整收尾（36 条 Shutdown Metric、`put.error==0/take.error==0`）及 Phase 4 kill 前进程确认共同证实。良性 INFO `ClassNotFoundException: sun.misc.VM`（Flume DirectMemoryUtils 的 Java-11 回退路径）与真实 classpath 失败区分，未误判。

## 4. Phase 3：主链（1011 行真实落 HDFS）

- 投入 pinned 副本（drop 时刻 `evidence/phase3-drop-time.txt`）→ agent 消费 → `.COMPLETED` 改名（`deletePolicy=never` 留档，SHA 复核 == pinned，`evidence/phase6-spool-sha.txt`）→ HDFS data file **`events-.1789810142459`，456,825 B / 1011 行**，checksum `0000020000000000000000002446c8f7ee4f392fb03af2e0a93ec13b`。
- 双快照不变（`evidence/phase3-snap1.txt`/`phase3-snap2.txt`，SNAPSHOT_STABLE）：HDFS 行数恰 1011（若二次摄取将为 2022）；残留 `.tmp`=0；优雅停止。
- **事件 2（环境级 SIGHUP，非链路失败）**：Phase 1 会话内起动的 NN/DN 在 `wsl -e bash -c` 会话拆除时收到 SIGHUP 死亡（NN/DN 日志各唯一 1 条 ERROR = `RECEIVED SIGNAL 1: SIGHUP`，17:12:33；`evidence/hdfs-nn.log`/`hdfs-dn.log`）；Flume 因显式 nohup 存活，1011 事件安全滞留 File Channel。恢复 = `setsid nohup hdfs --daemon start namenode|datanode`（**不 reformat**，run-scoped conf 原样），sink 随即冲刷完成。`evidence/flume-main-agent.log` 关闭指标 `put.error==0/take.error==0`。
- **零字节工件登记**：NN 中断期间 sink 重试留下 3 个 0 字节 `events-.` 文件（1789809725183 / 1789810132340 / 1789810137393，`evidence/phase6-final-main-listing.txt`）——sink 打开-重试残迹，从未承载数据；PASS 判据只认 456,825 B 数据文件。DataStream fileType 无 `_SUCCESS` 产物，按计划 §5.4 登记。

## 5. Phase 4：恢复子例（隔离 `-rec` 资源；at-least-once 如实登记）

隔离资源：独立 `spool-rec`、HDFS `/stage7u_stage7u_20260919_170729-rec/...`、独立 `channel-rec/{checkpoint,data}`。对账口径 = closed 文件 + 残留孤儿 `.tmp` 一并提取 event_id。

**Attempt 1（输入 ×20 = 20,220 行，SHA `34353a87275be3c77b10113827b5b246883fa02e31a19fc268c328b734aac1bd`）**：drop ~3s 后 `kill -9`，进程消失确认后同 run conf 重启。File Channel 重放：`put: 20220, take: 500` → **`Queue Size after replay: 20220`**（kill 落在 source→channel 提交后、sink 在途事务中）。排空后交付 **20,720 = 20,220 + 500 重投**（kill 时在途 sink 事务；孤儿 `.tmp` `events-.1789810525103` 224,282 B = 500 行）；distinct 1,011；**LOST=0**（`evidence/rec-lost.txt` 为空）。本轮 `.COMPLETED` 赢得竞速（改名先于 kill 完成）→ 按计划以更大倍率重试。

**Attempt 2（输入 ×100 = 101,100 行，新文件名 `-0002`，SHA `e20d8469100d5d267c82460e341087add15462ca11d592ec65f26c7b56038f2c`）**：drop 17:39:14，~3s 后 `kill -9`。竞速判定（脚本 echo 行位于截断输出段，由留证日志诚实重构）：pre-kill agent 末行（`evidence/flume-rec-agent-3.log` 17:39:17,437）为 source 读完整个 `-0002` 后 `Preparing to move file ... to ....COMPLETED`——全部 101,100 事件已 put+commit 进 File Channel（重放 `put: 101100` 佐证），kill 落在 sink 排空中（snap1 显示在途 `events-.1789810762591.tmp` 224,357 B）。重启重放：**`read: 124151, put: 101100, take: 2083, rollback: 0, commit: 207, skip: 20761, eventCount: 103100` → `Queue Size after replay: 99100`**（`evidence/phase4-rec2-replay-lines.txt`）。排空后交付 **恰 101,100 行 = 输入行数（零净超额）**：闭合文件 `events-.1789810762591` 44,779,028 B + 孤儿 `.tmp` `events-.1789810757236` 224,282 B（500 行）；**agent-4 未二次摄取**（若重读源文件交付将翻倍，实际恰等）。REC2_SNAPSHOT_STABLE；优雅停止干净。

**合并 multiset 对账（attempt1+attempt2 交付 121,820 行 vs 合并输入 121,320 行）**：`evidence/rec-multiset-diff-fixed.txt` —— **INPUT_ONLY=0（零丢失：121,320 个输入副本逐一在位）、DELIVERED_ONLY=500（超额恰 500，全部归属 attempt-1 在途重投）**；distinct 两侧均 1,011；rec2-lost.txt 为空。**File Channel kill -9 后未损坏（重放日志干净）→ FAIL_CHAIN 不适用**；持久性证据 = `evidence/phase6-local-dirs.txt`（checkpoint 8,008,232 B + inflightputs/inflighttakes + log-1..4 均带 .meta，两侧 channel 目录齐全）。

## 6. Phase 5：主链对账（PASS 判据采集）

- 主链 event_id 集合比对（`evidence/main-input-ids-uniq.txt` vs `main-delivered-ids-uniq.txt`）：**1011 = 1011，`MAIN_SET_EQUAL`（diff 为空），`comm -23`= 0** —— 不丢、不增、不重复（×1 each）。
- **ERROR/FATAL 扫描**：flume-main-agent.log + 4 个 rec agent 日志 ` ERROR `=0、FATAL=0；NN/DN 日志各唯一 1 条 ERROR = 已登记 SIGHUP 事件。无其他 ERROR 级异常。
- checksum 补采（短暂重启后复采、随即再停机，`evidence/phase6-checksums-main.txt`/`-rec.txt`）：主链数据文件 `2446c8f7…`（与 Phase 3 一致）、rec 闭合文件 `d6c487e2…`（attempt-1）/ `1ecda1a3…`（attempt-2）；0 字节与 `.tmp` 文件为 under construction 无 checksum（HDFS 语义，如实登记）。
- 终态登记：`evidence/phase6-final-tree.txt`（完整 HDFS 树逐文件字节数）、`phase6-final-hdfs-count.txt`（主目录 FILE_COUNT=4 / CONTENT_SIZE=456,825）、`phase6-dfsadmin-final.txt`（Live datanodes (1)）、`phase6-local-dirs.txt`、`phase6-spool-sha.txt`（三个 `.COMPLETED` SHA 复核全一致）。

## 7. 计划 §5 十一项 PASS 判据逐条核对

1. ✅ **Flume 真实启动、三组件就绪**：`evidence/flume-main-agent.log` FileChannel/landingSink/spool started + 优雅停止 shutdown-hook 36 条指标收尾（进程存活佐证）。
2. ✅ **输入只消费一次**：`.COMPLETED` 改名 + `deletePolicy=never` + 60s 双快照不变（`phase3-snap1/2.txt`）+ HDFS 行数恰 1011（非 2022）。
3. ✅ **RunId 隔离**：RunId 新生成；目标父路径执行前不存在；路径含 `stage7u_<RunId>` 字面量（双重前缀推导见 §3，`phase6-final-tree.txt`）。
4. ✅ **真实 data file**：`events-.1789810142459` 456,825 B > 0，checksum `2446c8f7…`；DataStream 无 `_SUCCESS` 属 fileType 语义，已登记。
5. ✅ **HDFS 行数 = 输入行数 = 1011**（Phase 3 wc -l + Phase 5 对账）。
6. ✅ **event_id 不丢/不增/不重复**：集合双向相等 1011 unique ×1（`MAIN_SET_EQUAL`）。
7. ✅ **SpoolDir 钉死**：Spooling Directory Source（taildir 排除）；run conf 与模板 diff 恰 4 处路径替换（`conf-diff.txt`/`conf-diff-rec.txt` 各恰 8 行）。
8. ✅ **状态/position/complete 语义一致**：`.COMPLETED` + includePattern/`decodeErrorPolicy=FAIL`/`deletePolicy=never` + File Channel checkpoint/data 分离持久（`phase6-local-dirs.txt`）+ 恢复子例两次 attempt **LOST=0**、重复如实登记（500/0），未宣称 exactly-once。
9. ✅ **失败可按 §6 分类**：本批无 FAIL_CONF/PORT_NET/HDFS_UNREACHABLE/PERM/RECON/CHAIN 触发；两起环境级事件（-Xmx20m launch fix、SIGHUP 恢复）均分类登记且未触犯任何分类判据。
10. ✅ **不碰 3306**：全程零 MySQL 访问（3306/3307 均零连接）、零 platform/Spark 进程启动。
11. ✅ **PASS 只证明 Flume→HDFS**：§9 边界随本文重申，不外推。

## 8. 执行边界遵守情况（总控钉死项逐条）

- Flume 下载 SHA512 前置校验 ✅；run-scoped tools、无 /opt 全局安装 ✅（计划 L50 的 /opt 表述被总控边界覆盖，登记于 §3）。
- Hadoop 仅复用二进制；conf/NN/DN 数据目录全 run-scoped；**`/opt/hadoop-3.3.4/etc/hadoop` 零修改**；无系统服务注册 ✅。
- 端口由运行时证实（9100 LISTEN + dfsadmin），非假定 ✅。
- 仅 4 处 conf 替换；pinned 输入只读、drop 用 copy、源文件保持 `.COMPLETED` ✅。
- 恢复语义 = 不丢；重复按 at-least-once 如实登记、不伪造 exactly-once ✅。
- 本批仅证明 spool → Flume → File Channel → HDFS；HDFS 含真实完成数据文件、1011 行逐 event_id 可读 ✅。
- 无字段升级/替换强行变绿：两起事件均为运行环境层修复（JVM 堆参数官方覆写点、setsid 守护起动方式），被测链路组件/配置/语义零改动 ✅。

## 9. §7 PASS boundary（本批不证明）

平台 FLUME_RAW 采集、ingestion manifest 与平台侧对账（后续批次）；Spark/LOAD_ODS 及下游；REMOTE_CLUSTER 正式集群验收（指导书 L183/L233）；浏览器 E2E；真实 LLM provider；第二来源异构夹具；端到端 exactly-once（规则 5 明确不承诺——本批实证为 at-least-once，观察到的重复为 kill 在途重投）；正式集群吞吐/物理高可用（§5.1 L112「不能宣称」列）；**不外推为整个 Stage 7 完成**（总控原话钉死）。

## 10. 证据工件索引（Windows 镜像 `target/v25-it/stage7u_20260919_170729/`）

| 目录 | 内容 |
|---|---|
| `evidence/`（62 件） | phase1/2a/2b/3/4/5/6 全部执行脚本与输出：HDFS runtime、conf-diff（主/rec）、双快照、drop 时刻、重放计数行、ids/lost/multiset 对账文件、checksum、终态树/listing、本地 channel 目录清单、spool SHA、`flume-agent.first-fail-xmx20m.log`、`flume-main-agent.log`、`flume-rec-agent-1..4.log`、`hdfs-nn.log`、`hdfs-dn.log` |
| `flume/`（4 件） | run conf、run-rec conf、flume-env.sh（-Xmx512m 覆写）、log4j2.xml |
| `logs/`（2 件） | hadoop-asus-namenode/datanode-dahaishui.log（原生 NN/DN 日志） |
| `evidence-summary.json` | 关键数字结构化汇总 |

模板 `ingestion/flume/flume-spooldir.conf` 零修改（仓内可证）；run 根 `/home/asus/stage7u_20260919_170729/` 与 HDFS 数据留存于 WSL（本批无仓内产物）。曾落入仓根的主 agent 日志（CWD 路由）已复制进 evidence 后删除，未入库。

## 11. 治理后果

- **BATCH-U 收敛关闭（PASS）**；状态矩阵仅 Flume→HDFS 一格置 PASS/CLOSED。
- 本结果文档与状态文档提交**仅本地**（D-001：push 归 ChatGPT 角色，需总控另行授权）。
- 下一步按总控指示直接推进 Stage 7 剩余面（REMOTE_CLUSTER、浏览器 E2E、真实 LLM——均待总控排程，本批通过不构成其解锁外的额外声明）。
- 边界不变：3306 未触碰、未改已发布迁移、未改质量阈值、未 force push、未自行宣布完整验收。
