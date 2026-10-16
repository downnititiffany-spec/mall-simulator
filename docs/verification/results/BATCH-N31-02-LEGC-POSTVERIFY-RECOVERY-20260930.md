# N31-02 腿③：发布后校验失败恢复补充记录

日期：2026-09-30  
范围：MetricPublisher 激活后只读对账失败时的 ACTIVE 回退与失败快照补偿。  
结论：**代码与默认测试通过；真实隔离 MySQL IT 未运行；不构成 N31-02 整批签收。**

## 本次修正

复核发布流程时发现，发布指针已切换、随后只读校验失败的补偿路径必须确保旧 ACTIVE 不被遗失。`MySqlMetricStore.failActivationAndRestore` 现使用 `metricPublishTransactionManager` 将以下操作放在同一事务中：

1. 锁定该 runtime profile 当前 ACTIVE 指针。
2. 若失败快照仍为 ACTIVE，将它置为 FAILED 并释放 ACTIVE 标志。
3. 若旧快照仍是 ARCHIVED 且当前没有更新 ACTIVE，将旧快照恢复为 ACTIVE。
4. 删除失败快照的 metric_value；只有上述状态恢复事务成功后，发布器才清理该失败快照的 ADS 行。
5. 若已有更晚的 ACTIVE，则保留它；若旧快照不存在或状态不符合恢复条件，整笔事务回滚。恢复失败时不清理可能仍由当前 ACTIVE 使用的 ADS 行，并返回独立错误码。

本轮补充发现并覆盖了另一边界：失败快照已不再是 ACTIVE、但当前 ACTIVE 指针为空时，也应尝试恢复仍可用的发布前 ARCHIVED 快照；不能因此留下无 ACTIVE 状态。该情形已加入 `MySqlMetricStoreActivationRollbackTest`。

## 验证

- 定向 Maven 测试：`MySqlMetricStoreActivationRollbackTest` 5/5，`MetricPublisherBuildFailureCompensationTest` 4/4，合计 **9/9**。
- 统一默认档 fresh：analytics-server **1193**（F=0、E=0、S=2），mall-simulator **14**，synthetic-data-generator **111**，总计 **1318**，基线 MATCH，PASS。日志目录：`C:\Users\ASUS\AppData\Local\Temp\v25tests-dev003c_20260930_153555_33043c`。
- 默认档不等于写入型 MySQL IT、Spark/Hive 真实链路或 web 页面验收。
- `run-isolated-tests.ps1` 对新 RunId `n3102pv_20260930_1600` 仅执行 `-DryRun`。脚本报告 `IT_GUARD_PASSWORD`、`V25_IT_META_PASSWORD`、`V25_IT_METRIC_PUBLISH_PASSWORD` 未设置，并明确未连库、未运行 Maven、未落盘。对 3307 专用 MySQL socket 的 root 只读身份探针返回 Access denied；没有尝试绕过隔离门禁或从历史文件提取凭据。
- 因当前会话缺少可用的隔离测试凭据，`MetricPublisherMySqlIT` 的真实 MySQL 失败恢复场景**未执行**。测试代码现已准备 V13 分类/地区旧快照行和后置只读校验失败注入，用于后续真库验证，但不能把代码存在或编译通过表述成真库通过。

## 边界与后续

- 未连接或访问 3306；未在 3307 执行 DDL/DML；未重跑 Spark、Hive 或 web 套件。
- N31-02 腿③仍保持 `PASS_WITH_LIMITATION`，整批仍待总控复核/签收；本补充记录不修改原腿③结果，也不提升验收结论。
- 后续门槛：通过受控进程环境提供本批 3307 隔离库所需的管理员及应用测试凭据，执行 `MetricPublisherMySqlIT`；核实失败快照为 FAILED、旧 ACTIVE 与其分类/地区 ADS 行仍可读、失败快照 metric_value/ADS 行为零，再据证据更新批次结论。
- 指导书 V3.0、设计文档 V3.0 均未修改；V3.1 仍为未发布草稿。无 commit / push。

## 追加验证：WAIT_LANDING 存储 URI 证据（2026-09-30）

- 根因核验：`WAIT_LANDING` 原只把 manifest 内的相对 `acceptedUri` 写入 `pipeline_stage_run.evidence`。实际存储位置由 profile 对应的 `LandingStorage` 唯一解析；Stage evidence 缺少该解析结果，复核时需再查 profile 与摄取证据才能确认 URI。
- 实现：保留原始 `acceptedUri`，新增 `acceptedStorageUri = landingStorage.uri(acceptedUri)`。local/HDFS 行为均经存储适配器解析；manifest 字段与采集格式不变。
- 回归先红后绿：`PipelineServiceTest.waitLandingSuccessThenLoadOdsInStageOrder` 旧实现下断言 `acceptedStorageUri` 为 null；补实现后定向 **1/1 PASS**，预期 local `file:` URI 完整含 `accepted/2026-09-01/`。
- 统一 default fresh RunId `dev003c_20260930_154751_84a9d5`：新鲜 Surefire 报告合计 **1318** 项，analytics 1193（117+383+205+138+163+187；F=0/E=0/S=2）、mall 14、generator 111；全部 BUILD SUCCESS，计数与本轮基线相等。`git diff --check` 通过（仅提示既有 CRLF/LF 工作区转换信息）。
- 边界：没有重跑 HDFS runtime 或 Flume→HDFS 链路，因此 `acceptedStorageUri` 的 HDFS 生产运行值尚未实测；不以 local 单测替代。当前进程/用户/机器范围均未发现 `V25IT_ADMIN_PWD`、`IT_GUARD_PASSWORD`、`V25_IT_META_PASSWORD`、`V25_IT_METRIC_PUBLISH_PASSWORD`；未执行隔离库准备或写入型 IT，3307 无写入、3306 零接触。真实 MySQL 发布失败恢复验证仍待安全凭据注入后执行。
- 状态不变：N31-02 腿③仍 `PASS_WITH_LIMITATION`，整批待总控复核/签收；V3.0 正式正文未改，V3.1 未发布；本轮未 commit/push。

## 后续修订：URI 凭据脱敏与最终默认档复核（2026-09-30）

- 安全复核发现 `acceptedStorageUri` 若含 URI `userinfo`，可能把用户名/口令写入阶段证据。`PipelineService` 现于持久化前剥离 userinfo，保留 scheme、host、port、path；原始相对 `acceptedUri` 仍保留。此规则只处理阶段证据，不改变 Landing URI 解析和采集契约。
- 新增合成凭据回归 `PipelineServiceTest.acceptedStorageUriEvidenceDoesNotPersistUriUserInfo`：未脱敏实现下断言失败，脱敏后通过；与完整路径解析回归合计 **2/2 PASS**。测试凭据为虚构字符串，不含真实凭据。
- 最终统一 default fresh RunId `dev003c_20260930_160419_a035c5`：analytics-server **1194**（117+383+206+138+163+187，F=0/E=0/S=2）、mall **14**、generator **111**，总计 **1319 MATCH/PASS**。先前 1318 项是增加脱敏守卫前的前一轮结果，不再作为当前基线。
- 本轮未重跑 HDFS/Flume 真实链；只读环境核验时 WSL 未运行 HDFS/MySQL 服务。隔离写 IT 所需口令没有通过安全进程环境注入，因此 `MetricPublisherMySqlIT` 未运行，也没有在 3307 写入；3306 零接触。
- 后续门槛不变：安全环境注入隔离测试凭据后，先恢复 3307 隔离服务，再运行 `MetricPublisherMySqlIT`，核对失败快照状态、旧 ACTIVE 与分类/地区 ADS 保留、失败快照指标/ADS 清理。上述步骤未完成前继续维持 `PASS_WITH_LIMITATION`，不申请 N31-02 整批签收。

## 最后加固：拒绝 URI query/fragment 并重验统一基线（2026-09-30）

- 对 sanitizer 再审发现：即使支持的 local/HDFS URI 校验器会拒绝 query/fragment，证据辅助函数仍应独立 fail-closed，避免未来调用路径将 token 写入证据。`safeStorageUriForEvidence` 现对任意 query 或 fragment 抛出不含输入内容的 `IllegalArgumentException`；`userinfo` 继续剥离。
- 新增 `PipelineServiceTest.acceptedStorageUriEvidenceRejectsQueryAndFragment`，分别用虚构 query token 和 fragment 探针；修复前先失败，修复后通过。URI 专项三项（绝对路径记录、userinfo 脱敏、query/fragment 拒绝）**3/3 PASS**。
- 同步 `scripts/run-tests.ps1` 默认档基线 analytics **1195**。量数轮 `dev003c_20260930_161627_0d2a4c` 为 1195+14+111=1320，全项无 F/E；随后正常计数门轮 `dev003c_20260930_161803_e2c0c8` 同样 **1320 MATCH/PASS**（analytics F=0/E=0/S=2）。
- 本轮没有重跑 HDFS/Flume 实链；WSL 只读核查显示无 HDFS/MySQL Java 进程或对应服务监听。必需隔离测试环境变量在进程/用户/机器作用域均未注入，故没有运行 `MetricPublisherMySqlIT`、没有在 3307 写入，3306 零接触。
- 真实 MySQL 发布失败恢复和 HDFS `acceptedStorageUri` 运行态值仍未验收；N31-02 腿③保持 `PASS_WITH_LIMITATION`，整批继续等待后续受控链路证据与总控签收。
