# BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT — Controller Review

> 结论：**BLOCKED_ENV（接受）**  
> 原始执行结果：`verification-results:docs/verification/results/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md`  
> Raw result commit：`2c32a45fedfba65ab7c5510b1662631b2d0adce2`

## 1. 被测基线与总体判定

- Batch Q 指定被测代码：`2f3e79f676e1b614fe9a57e71e7ecad68106a51f`。
- Code Agent 按计划完成 checkout、workspace clean、3307 dry-run 与真实 preparation 尝试。
- dry-run 只列出 `stage7q_20260918_1100_*` 的 3307 隔离库/账号，未把 3306、正式库或正式账号作为目标。
- 真实 preparation 未完成，因此 `scripts/run-tests.ps1 -Suite isolated` **没有执行**；mall 30 / generator 19 / analytics 6 / total 55 均无本批运行结果。
- 按 Batch Q §7，这种外部运行时/管理员准备不可用必须判 `BLOCKED_ENV`，不能记 PASS，也不能降级到 3306。

因此总控接受 **Overall = BLOCKED_ENV**，Stage 7 的真实 HTTP ingestion → pipeline 下一门保持关闭。

## 2. 对原始环境诊断的时点校正

原始报告在其执行时观察到“3307 未监听”，并进一步记录“WSL 未安装 server / 无 datadir”。后续总控在**同一台当前开发机**重新盘点后取得了更晚的事实：

- `/opt/mysql-8.0.41/bin/mysqld` 存在且可执行；
- `/data/mysql-isolated/data/mysql`、`/data/mysql-isolated/run`、`/data/mysql-isolated/log` 均存在；
- 只启动既有 datadir、不执行 SQL 的诊断启动可以让 `mysqld` 监听 `127.0.0.1:3307`；
- 随后再次运行仓库治理入口 `scripts/it-prepare-isolation.ps1 -Port 3307 -Confirm -AllowRootOnIsolated` 时，阻塞点已收敛为管理员认证：当前 MCP 进程环境 `V25IT_ADMIN_PWD` 未设置，root TCP 免密被 MySQL 以 `ERROR 1045` 正确拒绝；
- 诊断用 mysqld 随后已停止，未留下 3307 后台进程；全程未回退或连接 3306 做写入。

所以：**原始报告的 BLOCKED_ENV 判定仍然成立，但“当前机器完全没有 MySQL server/datadir”不再作为当前权威根因。当前权威阻塞是治理脚本所需的 3307 管理员认证未注入 MCP 运行环境。**

## 3. 安全边界复核

- 3306：未作为隔离目标，未降级使用。
- 3307：只允许仓库治理脚本创建 fresh runId 对象；总控没有手写 `CREATE/DROP/GRANT` 绕过脚本。
- 凭据：没有把管理员口令写入源码、Git、测试报告或聊天；当前只记录“环境变量未设置”这一状态事实。
- 工作区：Batch Q 原始验证前后 clean；后续开发改动属于独立并行工作集，不回写 Batch Q 的历史被测 SHA。

## 4. 后续处理

Batch Q 历史计划与原始结果保持不变，不原地篡改成 PASS。

当 3307 管理员认证通过**启动 coding-tools-mcp 的环境**安全提供后，应创建新的 `BATCH-Q-R1`（或等价 rerun）永久计划，并固定当时最新开发基线重新执行：

1. fresh runId 3307 preparation；
2. mall 30/30；
3. generator 19/19；
4. analytics 6/6，且 `IsolationGuardMySqlIT` 必须真实执行；
5. total 55/55、runner exit 0、workspace clean；
6. 只有 R1 PASS 后才开放 Stage 7 真实 HTTP ingestion → pipeline 链。

## 5. Batch Q 之后的并行开发

由于环境阻塞只影响真实 3307 / Stage 7 依赖链，其它不依赖 3307 的 A 类工作允许继续。

总控已在 `0f773220ad6a29d4b839e52d29b5b5fb6124d94b` 增加 Pipeline 纯 Java 恢复路径 developer tests：

- `retryFromStage(BUILD_DWS)`：成功前缀保留、指定阶段起后缀重建、snapshotId 复用、提交后缀精确；
- 连续多次 retry：attempt 1→2→3、成功前缀不重复、失败阶段保留多 attempt 证据、后继阶段只在最终成功后执行一次；
- `PipelineServiceTest` 31/31 PASS；default fresh 计数 1015 MATCH，唯一失败仍是既有环境性 patrol 用例。

这部分是独立工作集，**不改变 Batch Q 的 BLOCKED_ENV 结论，也不替代真库/真实 Spark 验证**。
