# BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN — Verification Plan

> 状态：**DRAFT（T-R1 PASS 前不得置 READY，不得执行）**
> Exact source/test SHA：待定（T-R1 通过后的开发基线 + 本批 harness 改动提交）
> Branch：feature/v3-development
> RunId：待定（新建 runId，不复用 stage7q1_20260918_152245）
> Predecessor：BATCH-T-R1（预期 PASS，关闭 platform-exit 事件）
> 依据：Batch T PLAN §8 将 Flume 列为首要未证项；PROJECT_STATUS L26 要求 Flume/HDFS 单独后置不与 LocalFile 三程序链混跑、L482 登记「Flume 从未运行」；设计 V3 §8.2 六条可判定规则。

## 1. Purpose 【DRAFT】

- 验证设计 V3 §8.2 Flume 首版链路六条规则的真实运行（当前只有静态门禁证据）：
  Spooling Directory Source + File Channel + HDFS Sink → HDFS 源级 raw 区 → 平台 FLUME_RAW 布局采集 → manifest 对账。
- 明确本批与 LocalFile 三程序链分离：producer/商城 Outbox 不在本批重跑。
- 关闭「Flume 从未运行」登记，或以 BLOCKED_ENV/FAIL_ENV 分类登记真实环境缺口。

## 2. Pinned input 【DRAFT】

- 复用 S-R1 pinned producer 完成文件（attempt-20260918_195657_077，1011 行，SHA256 f32906…bbcc）只读副本作为唯一投放输入；
- 投放前后各记录一次 SHA256，写入本批 evidence；
- 不使用 CANONICAL_EVENT_FILE 手工夹具，不重跑 generator/mall。

## 3. Safety and exact runtime 【DRAFT】

- 3307 only；不回退 3306；run-scoped analytics meta/metric 双库（沿用 it-prepare-isolation.ps1 派生）；
- 口令只从 PowerShell Process env / WSL 受保护 env（设计 §17.3 L755），不进命令行与日志；
- WSL 单节点 HDFS（1NN/1DN、replication=1），不要求 YARN/SSH；HDFS RPC 端口从 core-site/hdfs-site 实读并记录，不得猜测 8020/9000（设计 L136）；
- Windows→WSL spool 的文件交接方式必须显式记录（§8.2 规则 6）；
- 8091 若本批启动 platform 必须空闲；fresh attempt / 新幂等键；
- 不改 ingestion 契约：FLUME_RAW 清单 files[].file 语义开放问题只登记不擅改（PROJECT_STATUS L483）；
- 不触碰遗留 flume-taildir.conf（PROJECT_STATUS L484）；
- Flume 不删 spool 源文件；File Channel checkpoint/data 目录分离（§8.2 规则 2）。

## 4. Exact execution 【DRAFT】

- 形态：新增 harness（建议 scripts/stage7-flume-hdfs-e2e.ps1，沿用 -RunId / -Confirm / -DryRun 参数惯例；DryRun 必须零 I/O 先行验证，参照 Batch T PLAN §6）；
- 步骤骨架：
  1. git switch --detach <exact SHA>；重建 analytics 与 spark JAR；
  2. 启动 WSL 单节点 HDFS（+按需 HMS），预检记录真实端口/版本/JDK；
  3. 在 3307 run-scoped 双库登记 runtime profile（landing_layout=FLUME_RAW，V22 列）；
  4. 启动 flume agent -f ingestion/flume/flume-spooldir.conf；
  5. 原子改名投放 pinned 文件进 spool（规则 1）；等待 .COMPLETED 与 sink 落盘；
  6. 恢复子例：kill -9 flume agent 一次后重启，验证 File Channel checkpoint 续传（规则 2）；
  7. 触发平台 FLUME_RAW 采集，产出 ingestion manifest 与对账 evidence；
  8. 记录 exit code、日志路径、HDFS 目录树与校验和。

## 5. PASS criteria 【DRAFT】

- spool：源文件名全局不复用、原文件保留；in-use tmp 与隐藏/`_`前缀文件不入采集（规则 1/4，LandingInputScanner 已有规则）；
- HDFS sink：只写源级 raw 区、按真实采集日期/小时分区、无 %{eventType}、.COMPLETED 才可枚举（规则 3/4）；
- File Channel：kill 后重启不丢不重（at-least-once 语义如实记录，规则 5）；
- 平台采集：recordCount（去重后）= 1011、quarantineCount = 0、manifest SHA/offset/文件计数与输入一致（§8.4 L286）；
- 重复投递子例：同内容第二次改名投放，平台去重/记录，不产生第二份业务事实；
- default fresh 回归：analytics **1036 MATCH** / mall **14 MATCH** / generator **111 MATCH**（或当期基线），唯一红仍为既有 manifest patrol；
- （可选下游冒烟，若包含则单列）LOAD_ODS stage SUCCESS。

## 6. If Flume/HDFS environment fails 【DRAFT】

- 不得从控制台一行日志分类；区分 FAIL_ENV（缺 Flume/Hadoop 安装、WSL 环境缺口→BLOCKED_ENV）与 FAIL_CHAIN（规则违规/数据不符）与 harness 缺陷；
- 全部以 evidence（agent 日志、HDFS 校验和、manifest、exitCode）登记，遵守 CURRENT_BATCH「不再猜测」纪律。

## 7. PASS boundary（本批仍不证明）【DRAFT】

- REMOTE_CLUSTER 正式集群验收（指导书 L183/L233）；
- 浏览器 E2E；真实 LLM provider；第二来源异构夹具；
- 端到端 exactly-once（§8.2 规则 5 明确不承诺）；
- 正式集群吞吐/物理高可用（§5.1 L112「不能宣称」列）。
