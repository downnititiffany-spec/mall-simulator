# Test Execution Protocol

> 状态：CURRENT
> 目标：测试计划与测试结果都必须落盘，避免关键验证信息只存在于聊天窗口。
> 角色：ChatGPT = 总控/唯一远端写作者；Code Agent = Execution Tester；Codex Work = 仅重要节点的对抗验证者。

## 1. 固定文件

- `docs/verification/CURRENT_BATCH.md`：当前唯一批量测试任务入口。ChatGPT 在需要验证时写入精确 SHA、测试范围、命令、预期与判定规则。
- `.verify/CURRENT_BATCH_RESULT.md`：Code Agent 本地执行结果文件。Code Agent 允许创建/覆盖这个文件，但不得改源码、测试、docs、脚本或 Git 历史。
- `docs/verification/results/`：已由 ChatGPT 复核并提交到 GitHub 的永久测试结果归档。
- `docs/verification/DEFERRED_TEST_PLAN.md`：工作项状态总账，不再承担每次测试的完整命令说明。

## 2. 标准流程

1. ChatGPT 连续开发多个彼此独立、接口稳定的工作项。
2. 达到批量测试点后，ChatGPT 把**一次完整批次**写入 `CURRENT_BATCH.md` 并提交。
3. 用户只需告诉 Code Agent：读取 `docs/verification/CURRENT_BATCH.md` 并严格执行。
4. Code Agent：
   - checkout 文件中指定的精确 SHA；
   - 一次执行文件内全部测试，不自行拆成多轮；
   - 不修改任何 tracked 文件；
   - 将完整报告写入 `.verify/CURRENT_BATCH_RESULT.md`；
   - 最终只返回结果文件路径 + 简短 Overall，不在聊天里重复整份报告也可以。
5. 用户把 `.verify/CURRENT_BATCH_RESULT.md` 上传/粘贴给 ChatGPT。
6. ChatGPT 复核后：
   - 把结果永久归档到 `docs/verification/results/<batch-id>-RESULT.md`；
   - 更新 `DEFERRED_TEST_PLAN.md`；
   - 若通过则继续开发；若失败则只修真正阻塞的缺陷。

## 3. 为什么采用批量而不是每个小改动都停一次

默认策略是**批量延迟验证**，不是“改一项就停一项”。只有以下情况才提前测试：

1. 后续实现直接依赖某项运行结果，错误会沿依赖链放大；
2. 数据库迁移、状态机、权限/认证、AI SQL 安全、正式契约等高风险公共边界；
3. 基础测试已经无法建立基本正确性；
4. 后续修改会破坏当前故障现场或让失败难以定位；
5. 即将进入真实 MySQL/Spark/Hive/Flume/HTTP E2E、合并 main 或阶段验收。

除此之外，前端展示、独立 helper、加性 DTO、互不依赖的常规 bugfix 等都应先累计成一个功能簇，再一次性测试。

## 4. 每批测试范围怎么选

不是每次都无差别跑仓库所有测试，而是“定向测试 + 受影响域完整门禁”：

- 仅 Web 改动：相关定向测试 + `cd web && npm run verify`。
- analytics Java 改动：相关定向 Maven 测试 + `pwsh scripts/run-tests.ps1 -Suite default`。
- Spark 改动：相关定向测试 + `-Suite spark`。
- 隔离数据库/真实链路：对应 `isolated`/真库命令 + 必要 default 回归。
- 跨域或阶段收口：把多个相关门禁组合进同一个 `CURRENT_BATCH.md`，由 Code Agent 一次执行完。

这样既避免每个小改动反复停工，也避免每次都运行与当前改动完全无关、成本很高的环境测试。

## 5. Code Agent 写结果文件规则

`.verify/CURRENT_BATCH_RESULT.md` 至少包含：

```text
Batch ID:
Tested commit:
Git status:
Environment:
Commands executed:
Per-suite counts and exit codes:
Per-item result:
Known environmental failures:
New failures:
Unverified runtime areas:
Evidence/log paths:
Overall: PASS | PASS_EXCEPT_KNOWN_ENV | FAIL_NEW_REGRESSION | PARTIAL
```

要求：

- 所有数量必须来自实际执行，不用推导值替代；
- 必须区分 NEW REGRESSION 与已登记环境红；
- 不能因为总套件绿就把没有被实际覆盖的 E2E 项写成 PASS；
- 不修代码、不 commit、不 push、不 merge；
- `.verify/CURRENT_BATCH_RESULT.md` 是唯一允许新增/覆盖的本地报告文件。

## 6. Codex Work 调用原则

普通批次不调用。仅在核心安全、重大 Flyway/数据模型、权限认证、AI SQL 安全、关键状态机/幂等恢复、正式阶段验收、合并 main 前的高价值节点按需调用。