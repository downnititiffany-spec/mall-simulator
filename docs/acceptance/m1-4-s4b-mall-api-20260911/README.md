# M1-4 S4b 验收记录：MALL_API 生成引擎（真实商城适配）

- **泳道**：M1-4 S4b（`MallTargetAdapter` SPI + `MALL_API` 生成模式）
- **分支**：`remediation/r1-boundary`
- **取证日期**：2026-09-11 19:4x – 2026-09-11 22:03（+08:00）；README 补写于 2026-09-12 08:4x（E3 制品副本于 08:4x 补拷入 `verify/`）
- **证据目录**：`docs/acceptance/m1-4-s4b-mall-api-20260911/`
- **证据等级**：**E1（编译）+ E2（模块测试）+ E3（真实本机链路）**；**E4（集群）/ E5（页面路径）未取**
- **结论一句话**：MALL_API 引擎、`MallTargetAdapter` 全九项 SPI、HTTP/CLI 入口、单元测试 + 真实 MySQL 集成测试 + 真实 HTTP 夹具全部落地并通过；真实 8090 商城链路上跑通了"成功路径"与"库存不足响亮失败路径"，**但最后一次 400 事件真机运行是 FAILED**（原因见 §5），**且未取到"订单全成功"的大规模真机 SUCCESS**。

---

## 1. 交付物清单

### 1.1 新增（`synthetic-data-generator/src/main/java/com/graduation/generator/`）

| 文件 | 一句话说明 |
| --- | --- |
| `adapter/TargetCapabilities.java` | 能力声明容器：`Map<MallCapability,CapabilityVerdict>` + `declared` 标志，提供 `declared/none/verdict/isSupported` |
| `adapter/MallOperationException.java` | 商城操作失败的唯一异常出口，message 形如 `[operation] 说明`，**绝不携带凭据值** |
| `adapter/TargetCheckResult.java` | `test()` 的返回：`id/reachable/detail/capabilities` + `verdict(cap)` |
| `adapter/ProductQuery.java` | 目录查询 DTO（`categoryId/keyword/offset/limit`，`MAX_LIMIT=500`，`firstPage(int)`） |
| `adapter/ExternalProduct.java` | 商城侧商品的最小投影（真实 `productId` + 名称/类目/品牌/价格/状态） |
| `adapter/ProductPage.java` | 目录分页结果（`items/total/hasMore`） |
| `adapter/UserCommand.java` / `adapter/ExternalUser.java` | 建合成用户的入参 / 出参（出参带商城真实 `userId`） |
| `adapter/BehaviorCommand.java` | 行为埋点入参（B-04：只走商城公开埋点接口） |
| `adapter/OrderCommand.java` / `adapter/ExternalOrder.java` | 下单入参（含 `items[].productId/quantity`）/ 出参（真实订单号 + 状态） |
| `adapter/PayCommand.java` / `adapter/CancelCommand.java` | 支付 / 取消入参 |
| `adapter/RefundCommand.java` / `adapter/ExternalRefund.java` | 退款入参 / 出参 |
| `engine/MallDispatchPlan.java` | 事件类型 → 商城操作的路由表（`OP_*` 常量、`BY_EVENT_TYPE`、`of(eventType)`、`isMallBacked()`） |
| `engine/OperationJournalEntry.java` | 单条操作流水记录（`supported` 区分"真调用"与"能力缺口占位"） |
| `engine/OperationJournal.java` | 操作流水收集器，落盘为 `operation-journal.jsonl` |
| `engine/MallApiDispatchSink.java` | 事件 → 商城调用的派发器：能力闸门 → 调用 → 改写真实 ID → 记账（`succeeded/failed/skipped`） |
| `engine/MallApiGenerationEngine.java` | MALL_API 生成引擎：预检 → 复用 FILE 模式可复现计划 → 派发 → 一致性收尾 |

### 1.2 新增（测试侧）

| 文件 | 一句话说明 |
| --- | --- |
| `src/test/.../fixture/FakeMallServer.java` | 基于 JDK `com.sun.net.httpserver` 的**真实 HTTP 夹具**（真监听端口、真收发 JSON，不是 mock 对象），可注入订单容量以复现库存不足 |
| `src/test/.../adapter/MallTargetAdapterOperationsTest.java` | 七项业务 SPI 的契约测试（10 例） |
| `src/test/.../engine/MallApiGenerationEngineTest.java` | 引擎级测试（13 例），含"商城真实拒绝绝不算成功"回归 |
| `src/test/.../web/MallApiGenerationSmokeTest.java` | 端到端 HTTP 冒烟（8 例），含"真机拒绝被记为失败且计数诚实"回归 |

### 1.3 修改

| 文件 | 改动一句话 |
| --- | --- |
| `adapter/MallTargetAdapter.java` | 从"只有 `listProducts` 的骨架"补齐为**逐字对齐 §4.1 的九项接口**，八项业务方法默认 `unsupported(...)` 响亮失败 |
| `adapter/ReferenceMallHttpAdapter.java` | 落地参考商城 HTTP 实现（全九项 + 真实探测 + 凭据引用解析 + 目录缓存） |
| `config/GeneratorBeans.java` | 注册适配器 Bean 与 `MallTargetAdapterRegistry` |
| `contract/Artifact.java` | 新增 `OPERATION_JOURNAL` 制品种类（17 字符 ≤ `VARCHAR(32)`，**无 DDL 变更**） |
| `contract/JsonlEventSink.java` | 新增 `writtenRecords` 累计 + `eventRecords()`，用于失败路径也拿到**诚实的入流条数** |
| `engine/FileModeGenerationEngine.java` | 暴露可复用的可复现计划（供 MALL_API 复用同一份 plan），并抽取 `eventFilter` 联合 |
| `engine/GenerationRequest.java` | 新增 `eventFilter` 谓词与 `withEventFilter(Set<String>)`，使"无商城操作支撑的事件类型"在**计划层**被裁掉 |
| `service/GenerationRunService.java` | 接入 MALL_API 模式：预检先于 `insertRun`、`dirty_profile!=none` 响亮拒绝、注入 `MallApiGenerationEngine` + 注册表；**修正失败路径 `success_count` 语义** |
| `src/test/.../web/GeneratorApiSmokeTest.java` | 适配新增引擎 Bean 的注入（`@Qualifier("generationEngine")`） |

### 1.4 交付物自检

- `synthetic-data-generator/**` 下：**9 个 modified + 23 个 untracked**（`git status --short` 实测；23 个 untracked 中 22 个是新增源文件/测试文件，1 个是 `src/test/java/com/graduation/generator/fixture/` 目录条目）。
- 全部为我本泳道交付物，**无残留源码**。
- 交付时的唯一残留是 `verify/tmp-*`，**已由总控处置**（见 §2：只删活 token，其余改名保留）。
- `docs/acceptance/m1-4-s4b-mall-api-20260911/` 整目录为新增（`git check-ignore` 退出码 1，未被忽略）。

---

## 2. 临时文件处置（**总控已于 2026-09-12 08:44 执行，非泳道自评**）

泳道交付时 `verify/` 下有 9 个 `tmp-*` 调试残留。总控的实际处置原则是**只删除活凭据，其余一律改名保留（不销毁任何证据）**：

| 原文件 | 处置 | 现名 / 说明 |
| --- | --- | --- |
| `tmp-mall-token.txt` | **删除**（唯一删除项） | 32 字符 8090 活 token；实测确认为单一 token 串，**不得入库**。删除前另行全目录扫描 `eyJ…`/`"token":"…"` 形态，确认其余证据无凭据残留 |
| `tmp-engine-test-failures.txt` | 改名 | `e2-s4b-RED-engine-failures-2103.log`（红灯对照，改名后不会被误读为现状） |
| `tmp-wrong-cred.log` | 改名 | `e2-s4b-RED-wrong-credential.log` |
| `tmp-e2-engine-unit.log` | 改名 | `e2-s4b-single-engine-13-green.log` |
| `tmp-e2-smoke.log` | 改名 | `e2-s4b-single-smoke-8-green.log` |
| `tmp-e3-run-id.txt` / `tmp-e3-run-id2.txt` / `tmp-e3-run-small.txt` | 改名 | `e3-s4b-run-id-note-live.txt` / `…-live2.txt` / `…-small.txt` |
| `tmp-e3-target-id.txt` | 改名 | `e3-s4b-target-id-note.txt` |

**未采纳泳道的"建议删除红灯日志"**：红-绿对照是"测试真的会失败"的唯一证据，删掉它就把"永远绿"的怀疑留给了读者。改名而不删除是这里的正确取舍。

清理后 `verify/` 下 `tmp-*` 文件数为 **0**（实测）。另新增总控独立重跑日志 `verify/e2-s4b-module-tests-TOTAL-CONTROL-rerun-20260912.log`（见 §13）。

---

## 3. E1 编译取证

| 项 | 值 |
| --- | --- |
| 命令 | `& mvn.cmd -q -o -f synthetic-data-generator/pom.xml test-compile`（`MAVEN_OPTS=-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`） |
| 原始日志 | `verify/e1-s4b-test-compile.log`（1466 B，mtime `2026-09-11 20:47:35`） |
| 结果 | `BUILD SUCCESS`，`Finished at: 2026-09-11T20:47:35+08:00` |

红灯对照（证明测试真的在测东西，不是永远绿）：

- `verify/e2-s4b-red-spi-missing-compile.log`（33386 B，`2026-09-11 19:54:49`）：SPI 未补齐时的编译失败原始输出。
- `verify/tmp-engine-test-failures.txt`：`MallApiGenerationEngineTest` 在修好之前的 `Tests run: 10, Failures: 3, Errors: 1`。

---

## 4. E2 模块测试取证（**最终数字**）

| 项 | 值 |
| --- | --- |
| 命令 | `& mvn.cmd -f synthetic-data-generator/pom.xml test` |
| 原始日志 | `verify/e2-s4b-module-tests.log`（21388 B，现场时间戳 `2026-09-11 21:03:45`） |
| 总计 | **`Tests run: 96, Failures: 0, Errors: 0, Skipped: 0`** → `BUILD SUCCESS` |
| 测试类数 | 17 |

逐类明细（原始日志逐行摘录）：

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.adapter.MallTargetAdapterOperationsTest
Tests run:  4, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.adapter.MallTargetAdapterRegistryTest
Tests run:  8, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.adapter.ReferenceMallHttpAdapterTest
Tests run:  4, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.boundary.GeneratorBoundarySourcePolicyTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.contract.GeneratorContractParityTest
Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.contract.JsonlEventSinkTest
Tests run:  4, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.core.BehaviorChainTest
Tests run:  4, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.core.DistributionKitTest
Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.core.GeneratorDeterminismTest
Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.core.ScenarioEffectTest
Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.core.ScenarioRegistryTest
Tests run:  9, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.engine.FileModeGenerationEngineTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.engine.MallApiGenerationEngineTest
Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.meta.GeneratorMetaStoreTest
Tests run:  4, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.meta.RunStatusTest
Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.web.GeneratorApiSmokeTest
Tests run:  8, Failures: 0, Errors: 0, Skipped: 0 -- com.graduation.generator.web.MallApiGenerationSmokeTest
```

**这些测试没有任何 mock**：HTTP 走 `FakeMallServer`（`com.sun.net.httpserver` 真监听端口、真 JSON 收发）；DB 走真实 MySQL 8.0.41（`generator_meta`）。

覆盖的必测场景（指导书要求的五类）：

1. **能力缺失响亮失败** → `MallApiGenerationEngineTest`（`IllegalStateException`，不降级成文件模式）+ 冒烟"缺 `order` 能力必须 409"。
2. **凭据缺失响亮失败** → 测试要求错误文本**点名缺哪个 `credential_ref`**，且异常/日志里**不得出现凭据值**。
3. **正常路径** → 夹具上全绿跑通，事件入流 + 真实外部 ID 回填。
4. **未知 `adapter_type`** → 注册表抛错、HTTP 400，**无静默回退**。
5. **MALL_API 与 FILE 计划/统计一致性** → 同一 `GenerationRequest` 下两者的计划事件类型集合与逐类统计可对齐比对。

---

## 5. E3 真实本机链路取证（8090 真商城 + 8092 真生成器）

**跑了，真跑了。** 不是夹具、不是单测：生成器是 8092 上真实启动的 Spring Boot jar，目标是 8090 上真实运行的 `mall-simulator`，全部走 HTTP。

### 5.1 现场条件

| 项 | 实测值 |
| --- | --- |
| 生成器 | `synthetic-data-generator-0.1.0-SNAPSHOT.jar`，26216592 B，构建于 `2026-09-11 21:04:06`（`verify/e3-s4b-package.log`），PID 40996 监听 8092 |
| 目标 | `generator_target.id=118`，`adapter_type=REFERENCE_MALL_HTTP`，`base_url=http://127.0.0.1:8090`，`credential_ref=GENERATOR_TARGET_TOKEN` |
| 探针结果 | `reachable=true`；`product/user/order/refund/admin = SUPPORTED`；`behavior/reset_state = UNDETERMINED` |
| 商城真实登录 | `POST /api/v1/auth/login {"username":"admin","password":"admin123"}` → `code=OK`，32 字符 token |
| 商城真实目录 | `GET /api/v1/mall/products?page=1&size=100` → 43 件商品（1001…4xxx） |
| 商城行为端点 | `POST /api/v1/mall/behaviors` → **404**（参考商城**没有**埋点接口，B-04 能力缺口） |

### 5.2 最终一次真机运行（**权威证据**）

| 项 | 值 |
| --- | --- |
| 起始命令 | `POST http://127.0.0.1:8092/api/v1/generation-runs`，body `{"plan_id":"s4b-e3-live-20260911","version":1}` |
| `run_id` | `s4b-e3-live-20260911-v1-20260911-220249-fc47` |
| 计划 | `s4b-e3-live-20260911` v1，`mode=MALL_API`，`target_id=118`，`scenario=normal`，`seed=20260911`，`event_count=400`，`start=2026-09-01`，`end=2026-09-08`，`dirty_profile=none` |
| 起止 | `2026-09-11T22:02:49+08:00` → `2026-09-11T22:02:52+08:00`（约 3 秒） |
| 终态 | **`FAILED` / `RUN_FAILED`** |
| `success_count` | **195** |
| `failed_count` | 0 |
| 错误文本 | `MallOperationException: [runForTarget] 运行中有 204 次商城操作被真实拒绝（已写入规范流 195 条）：createOrder → [createOrder] 商城拒绝：http://127.0.0.1:8090 → HTTP 400 code=INSUFFICIENT_STOCK message=库存不足: 1002。商城侧不回滚，已成功的写操作真实存在；请清理后再重跑，或调小 event_count 让订单量落在库存可承受范围内` |
| 制品落盘 | `synthetic-data-generator/generator-output/s4b-e3-live-20260911-v1-20260911-220249-fc47/` |
| 证据副本 | `verify/e3-s4b-s4b-e3-live-20260911-v1-20260911-220249-fc47/`（`run-report.json` / `operation-journal.jsonl` / `events-0001.jsonl` / `events-0001.manifest.json`） |

**计数交叉核对（独立数字互相对得上，说明记账没有互相糊弄）**：

```
events-0001.jsonl 行数        = 195   （实际写进规范流的事件）
events-0001.manifest.json     = record_count 195，checksum 与 run-report 一致
run-report success_count      = 195   （与制品逐行一致）
run-report artifacts[0].record_count = 195（EVENT_JSONL）
run-report artifacts[1].record_count = 401（OPERATION_JOURNAL）
```

原始流水逐行解析（`operation-journal.jsonl`，401 行）：

```
OK = 197, FAILED = 204

按 operation×status 拆开（实测）：
  listProducts        OK = 21   ← 1 次真预检 HTTP + 20 条 product_created 的"按位对齐"流水（本地，零 HTTP，见 D12）
  createSyntheticUser OK = 33   ← 33 条 user_registered 的真实建用户
  createOrder         OK = 69 / FAILED = 102
  pay                 OK = 26 / FAILED = 39
  cancel              OK = 43 / FAILED = 62
  refund              OK =  5 / FAILED =  1   ← 5 = 2 条 refund_created + 2 条 refund_completed 复用行 + 1 条"未找到已完成退款（记缺口）"

对账等式（严丝合缝）：
  195（入流事件）+ 204（被拒操作）+ 1（真预检） = 400 = event_count   ← 预算正好用完，一条不多一条不少
  canonical_id 为 null 的行恰 1 条 = 那次真预检
```

规范流里真实落盘的事件类型分布（`events-0001.jsonl` 逐行解析，合计 195）：

```
user_registered=33, product_created=20,
order_created=69, order_paid=26, order_cancelled=43,
refund_created=2, refund_completed=2
```

注意 `order_paid=26` 而 `order_created=69`：只有**真实下单成功**的订单才可能被支付；失败订单的支付/取消随后被真实拒绝并如实记为 `FAILED`（其中 62 次取消写的是"订单在商城侧还没有外部 ID，拒绝用假 ID 继续"——生成器**宁可失败也不拿假 ID 去蒙商城**）。**没有任何 `behavior` 事件**，因为参考商城没有埋点接口（B-04），引擎从不退化去伪造。

**失败为什么是"真实的、正确的失败"**：商城侧库存真的被打空（`mall_simulator.inventory` 实测 `available_qty=0` 的有 3 个商品：1001/1002/1003），生成器**没有伪造成功、没有回滚假的计数、没有把失败吞掉**——它把 204 次真实拒绝如实写进流水、把 195 条真实成功的事件留在规范流里、把运行标成 `FAILED` 并把商城原样的 `INSUFFICIENT_STOCK` 文本带进错误消息。这正是 §3.3 A 要的行为。

### 5.3 同链路上的其他场景（均已真实跑过）

| 场景 | run_id | 终态 | 关键数字 |
| --- | --- | --- | --- |
| **成功路径**（小规模，无订单） | `s4b-e3-clean9-v1-20260911-210614-2aaf` | **`SUCCESS`** | `success_count=9`，`failed_count=0`，计划 `event_count=9` 全额完成 |
| 库存耗尽（中规模） | `s4b-e3-small-20260911-v1-20260911-210501-a484` | `FAILED` | `success_count=56`，63 次 `INSUFFICIENT_STOCK`（56+63=119≈120 预算） |
| 库存耗尽（大规模） | `s4b-e3-live-20260911-v1-20260911-220249-fc47` | `FAILED` | 见 §5.2 |
| **修复前的错误记账**（对照） | `s4b-e3-live-20260911-v1-20260911-205506-ca36` | `FAILED` | `success_count=632` —— 实际入流仅 231，因为当时把清单+流水的 `record_count` 也加进了 `success_count`。**这是被本轮修掉的缺陷**，留档作对照 |
| **修复前的假成功**（对照） | `s4b-e3-live-20260911-v1-20260911-205010-6151` | `SUCCESS` | `success_count=400`，但 `failed_count=170` —— 商城拒绝了 170 次却终态 `SUCCESS`。**这也是被本轮修掉的缺陷** |

后两行是**故意保留的红灯对照**：它们证明本轮的两处语义修正（`success_count` = 入流条数；有真实拒绝就必须 `FAILED`）不是空谈，而是真机上证伪过的。

### 5.4 真机上的能力缺口（B-04）

- 参考商城**没有行为埋点接口**（`POST /api/v1/mall/behaviors` → 404），因此 `behavior` 能力在探针里是 `UNDETERMINED`，引擎**从不调用 `emitBehavior`、也从不伪造行为事件**。
- 计划层把无商城操作支撑的事件类型（`product_updated`、`stock_reserved`、`stock_released`、`stock_changed`、`behavior`）**裁掉**，所以 MALL_API 的规范流里根本不会出现这些事件。
- 结论：读参考商城的配置里没有 `behavior_path`/`reset_path`，这两项能力就是 `UNDETERMINED`；要真正打通行为埋点，**需要商城侧补齐公开埋点接口**（已登记为依赖，本泳道不动 `mall-simulator/**`）。

---

## 6. 与指导书的偏离 / 已发现偏差（诚实登记）

| # | 偏差 | 说明与理由 |
| --- | --- | --- |
| D1 | §4.1 的七项业务方法**多了一个 `TargetConfig` 首参** | §4.1 只写 `listProducts(ProductQuery)` 这样的单参形态。适配器必须知道"调哪台商城、用哪份凭据引用"，而这些只存在于 `TargetConfig`。若塞进适配器实例，注册表就得维护"每目标一个实例"的生命周期——那才是重复所有者。**方法名、语义、返回类型与 §4.1 完全一致**，属最小必要偏离 |
| D2 | §4.1 未定义 DTO 字段，本包 DTO 是**最小设计** | `ProductQuery/ExternalProduct/ProductPage/UserCommand/ExternalUser/BehaviorCommand/OrderCommand/ExternalOrder/PayCommand/CancelCommand/RefundCommand/ExternalRefund` 的字段由本泳道定义 |
| D3 | `OPERATION_JOURNAL` 是**新增的制品种类** | 属加法扩展（17 字符 ≤ `artifact.kind VARCHAR(32)`），**无 Flyway 变更、无 DDL** |
| D4 | 白名单裁剪**不占用 `event_count` 预算** | `FileModeGenerationEngine.write()` 的 whitelist 分支直接 `return true` 且不 `emitted++`。后果：被过滤的事件流**不是原流的子序列**，而是"重新抽样的另一条流"（例：`E135282f00000054` 在不过滤时是 `behavior`，过滤后落到了 `order_created`），`timeAt(emitted)` 也会跟着漂 |
| D5 | `refund_created` 与 `refund_completed` **共用一条流水** | 参考商城的退款是"申请即完成"（一次调用即终态），故两条规范事件对应一次 `refund` 调用 |
| D6 | 外部商城 ID **跨运行不可复现** | 商城侧 ID 由它自己生成（雪花/自增），B-04 明确 `MALL_API` 只保证**场景分布可追溯**；严格同种子可复现**只属于 FILE 模式** |
| D7 | `failFast=true` 实际**无效**，引擎固定传 `false` | `FileModeGenerationEngine.write()` 在 `try/catch (RuntimeException)` 里吞掉了 sink 异常，`failFast` 永远没机会中断。已在代码里写明理由；闭环放在运行结束的"有真实拒绝即 `FAILED`"收尾判断上 |
| D8 | 失败路径 `success_count` 曾用制品 `record_count` 求和 | 会把清单和流水也算成事件（真机上报出 632 > `event_count` 400）。**本轮已修**：`successCount = outcome == null ? sink.eventRecords() : outcome.successCount()`，`JsonlEventSink` 新增 `eventRecords()` |
| D9 | MALL_API 现在**把派发尝试次数硬封顶在 `event_count`** | 修 D8 时发现"预算被挡下的事件吃满"会导致尝试数无界，故加 `ForwardingSink` 的 `attemptCap` 封顶 |
| D10 | 失败运行的 `generation_event_stat` / `event_stats` 是**空的** | 引擎走到"有真实拒绝"分支时抛异常，`EngineOutcome` 没返回，逐类统计没写库（`run-report.json` 里 `event_stats: []`）。**这是已知缺口，未修**；`success_count` 仍然诚实（走 `sink.eventRecords()`） |
| D11 | MALL_API 下 `dirty_profile != none` **响亮拒绝** | 脏数据注入的行为语义在真实商城上没有对应操作，宁可不做也不伪造 |
| D12 | **流水里 `listProducts` 混淆了"真 HTTP 调用"与"本地对齐记账"** | 实测本次运行 `listProducts OK = 21`，但**真正发出去的 HTTP 只有 1 次**（预检）；另外 20 条是 `product_created` 的"按位对齐"记录（`dispatchProduct` 只从内存里的预检目录 `pollFirst()`，**零 HTTP**），却写成了同一种 `operation/method/route`。后果：只看流水条数会**高估**对商城的读请求量。本轮**未修**；判据是 `canonical_id`——`null` 的才是真预检，带 `P000xx` 的是本地对齐。建议下一轮给流水加一个显式字段区分"真调用 / 本地记账" |

> **补记（2026-09-12，由 M1-9 契约先行轮登记；此处只放指针，不重复正文）**
>
> 本表 D1/D2 **覆盖不全**：后续只读审计又查出 4 项此前未登记的偏离，已统一登记为 **D13–D16（J1–J4）**，正文与 `path` 级证据见
> `docs/acceptance/m1-9-contract-first-20260912/README.md` §4；F-25（状态词映射缺失）见同文 §14；契约文本见 `docs/项目完整实施指导书 V2.3.md` §4.1.1。
>
> - **D13 / J1** `capabilities()` 的语义被改写：三态 `CapabilityVerdict` ＋ `TargetCapabilities.declared`，且**声明**与**实测**分离（声明纯本地推导、不联网）。
> - **D14 / J2** 新增 §4.1 九方法之外的 `adapterType()`（注册表的查找键）。
> - **D15 / J3** `EventSink` 多继承 `AutoCloseable` 且有 `default close()`（§4.1 未写）。
> - **D16 / J4** `generator_target` 十列里只有五列进 `TargetConfig`（`name`/`config_version`/`status`/`test_environment`/`capabilities` 不进）。
> - **R1**（升格为契约硬约束 6「路由所有权」）流水/报告的 `http_method`/`route` 原是引擎内置的参考商城字面量，与 D12 同属**证据真实性**类；实现排在 M1-9 ②。
> - **D1 口径更正**：多出 `TargetConfig` 首参的是**八**个方法（D1 只写"七项业务方法"，漏了 `capabilities`）。
>
> 以上均为**只读代码取证**，不是运行证据；本表历史正文一字未改。
---

## 7. 现场副作用（**必须知道**）

1. **真实 8090 商城的库存被我打掉了**。多轮真机运行（含 4 轮种子探测 + 6 档规模探测 + 一组 12/16/18 事件网格探测，共 **34 次 MALL_API 运行**）真实的创建了用户、订单、支付、取消、退款，并把 `product_id` 1001 / 1002 / 1003 的 `available_qty` 打到 **0**。实测（`mall_simulator.inventory`，2026-09-12 08:4x）：

   ```
   products=38, sold_out(available_qty=0)=3, total_avail=2884
   product_id 1001 / 1002 / 1003 → available_qty=0, reserved_qty=100
   ```

   **商城侧不回滚**——这些是真实成功过的写操作，独立于生成器留在了商城库里。补库存需要经商城管理接口，**我没有动**（不在本泳道边界内，且属"改别人数据"）。

2. **`generator_meta` 里留下了 32 个 `s4b-*` 计划 / 34 条运行记录**（其中 `SUCCESS` 仅 2 条），以及 `generator-output/` 下 **34 个**运行目录。未清理（属"不要做破坏性数据操作"红线内的判断，交总控）。
3. **8092 生成器被我重启过**：`23424` → **`40996`**（详见 §9）。
4. 8090 / 8091 **没有被我停过、也没有被我改过任何代码或配置**。

---

## 8. 复核命令（可原样复跑）

```powershell
# E1 编译
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -q -o -f synthetic-data-generator/pom.xml test-compile

# E2 模块测试（应得 Tests run: 96, Failures: 0, Errors: 0, Skipped: 0）
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -f synthetic-data-generator/pom.xml test

# 查 E3 运行终态（需要 8092 在跑）
Invoke-RestMethod 'http://127.0.0.1:8092/api/v1/generation-runs/s4b-e3-live-20260911-v1-20260911-220249-fc47'

# 直接验货真机制品
Get-Content 'docs\acceptance\m1-4-s4b-mall-api-20260911\verify\e3-s4b-s4b-e3-live-20260911-v1-20260911-220249-fc47\run-report.json' -Raw
(Get-Content 'docs\acceptance\m1-4-s4b-mall-api-20260911\verify\e3-s4b-s4b-e3-live-20260911-v1-20260911-220249-fc47\operation-journal.jsonl').Count
(Get-Content 'docs\acceptance\m1-4-s4b-mall-api-20260911\verify\e3-s4b-s4b-e3-live-20260911-v1-20260911-220249-fc47\events-0001.jsonl').Count

# 查商城库存现状（只读）
& 'mysql.exe' '-h127.0.0.1' '-P3306' '-uroot' '-p123456' '-Dmall_simulator' '-e' 'SELECT product_id, available_qty, reserved_qty FROM inventory ORDER BY available_qty ASC LIMIT 10;'
```

---

## 9. 8092 实例替换历史

| # | PID | 启动时间 | 为什么 | 是否打断别人 |
| --- | --- | --- | --- | --- |
| 1 | 23424 | 2026-09-11 20:47 之前 | 上一轮遗留的旧 jar 实例 | —— |
| 2 | 40996 | `2026-09-11 21:04:10` | 换成含 D8/D9 修正与 `describeCredentialRef` 的**最终 jar**（构建于 21:04:06） | 先停 23424（因为 `mvn clean` 删不掉被占用的 jar），属我自己的实例，**未影响 8090/8091** |

**现状（2026-09-12 08:36 实测）**：8090 / 8091 / 8092 **全部 NOT LISTENING**，无我们的 java 进程存活（仅剩 PID 54404 = DataGrip 的 JDBC server，与本项目无关）。**我没有启动它们**，也没有复跑 E3。

---

## 10. 未取证清单（**这些是"没跑"，不是"通过"**）

| # | 未取证项 | 为什么没取 |
| --- | --- | --- |
| U1 | **大规模"订单全成功"的真机 SUCCESS** | 参考商城 1001/1002/1003 库存已被真实打空。商品池是**按位置**取的（`productsFor(n)` 取目录前 N 件，目录按 `product_id` 排序），小规模运行必然包含已售罄的 1001/1002，一旦产生订单就被真实拒绝。已实测 6 档规模 × 6 个种子（约 30 次运行）**全部因 `INSUFFICIENT_STOCK` 失败**；唯一的真机 `SUCCESS` 是 `event_count=9`（尚未产生订单，只有 9 次建用户/读目录）。**要取到大规模 SUCCESS，必须先给商城补库存** |
| U2 | 真机 `behavior` / `reset_state` 能力打通 | 参考商城**没有** `POST /api/v1/mall/behaviors`（404）。属商城侧依赖，本泳道不改商城 |
| U3 | 真机 `refund` 的**终态 SUCCESS 运行** | 退款成功过（本次流水 `refund OK = 5`，其中 `status=COMPLETED`＋`external_id` 齐备的 4 条），但它所在的 run 终态是 `FAILED`（因为同一 run 里别的订单被拒）。**没有一次"退款成功且整轮 SUCCESS"的真机证据** |
| U4 | **E4 集群链路** | T4 层已被排除在本轮范围外 |
| U5 | **E5 页面路径** | 本轮不涉及前端 |
| U6 | 失败运行的逐类事件统计（`generation_event_stat`） | 见 D10：引擎在失败分支抛异常，`EngineOutcome` 未返回。**未修** |
| U7 | 同种子跨运行的**逐字节**制品一致性（MALL_API） | B-04 明确不承诺（外部 ID 由商城生成），见 D6。FILE 模式的同种子一致性已由 `GeneratorDeterminismTest` 覆盖 |
| U8 | 压力/并发（多 run 同时打同一台商城） | 不在 S4b 范围 |
| U9 | `dirty_profile != none` 在 MALL_API 下的**成功**路径 | 设计上就是响亮拒绝（D11），只有拒绝路径被覆盖 |
| U10 | 商城侧数据清理/补库存后的复跑 | 需总控裁决是否允许经商城管理接口补库存 |
| U11 | **对商城发起的真实 HTTP 请求条数** | 本轮**没有**抓包/服务端访问日志，只有生成器侧流水。而流水本身把"本地对齐记账"写成了 `listProducts`（见 D12），**不能**据此断言对商城的真实读请求次数。只有 `canonical_id == null` 那 1 条可确认是真 HTTP |
| U12 | `product_created` 的"按位对齐"在**目录中途变化**时的行为 | 目录在预检时被缓存到内存，运行中商城新增/下架商品**不会**被感知。本轮未构造该场景 |

---

## 11. 证据文件清单

```text
docs/acceptance/m1-4-s4b-mall-api-20260911/
├── README.md                                   ← 本文件
└── verify/
    ├── e1-s4b-test-compile.log                 BUILD SUCCESS 2026-09-11T20:47:35
    ├── e1-s4b-wip-compile.log                  69 B，仅 JAVA_TOOL_OPTIONS 回显，无构建结论（保留不删，便于审计）
    ├── e2-s4b-module-tests.log                 96/0/0/0 绿，mtime 2026-09-11 21:03:45（泳道跑）
    ├── e2-s4b-module-tests-TOTAL-CONTROL-rerun-20260912.log
    │                                           96/0/0/0 绿，2026-09-12 08:40（总控独立重跑，见 §13）
    ├── e2-s4b-module-tests-wip.log             20:07 的中间红灯 74/1/4（保留，红-绿对照）
    ├── e2-s4b-RED-engine-failures-2103.log     21:03 前的红灯明细（红-绿对照）
    ├── e2-s4b-RED-wrong-credential.log          "错凭据必须响亮失败"红灯一次
    ├── e2-s4b-red-spi-missing-compile.log      SPI 未补齐时的红灯（对照）
    ├── e2-s4b-adapter-operations.log           adapter 契约测试 10 例
    ├── e2-s4b-single-engine-13-green.log       单跑引擎测试 13/0/0/0
    ├── e2-s4b-single-smoke-8-green.log         单跑端到端冒烟 8/0/0/0
    ├── e3-s4b-package.log                      最终 jar 构建成功 21:04:06
    ├── e3-s4b-run-id-note-live.txt / -live2.txt / -small.txt / e3-s4b-target-id-note.txt
    │                                          真机 runId / 目标 id 便签
    └── e3-s4b-s4b-e3-live-20260911-v1-20260911-220249-fc47/
        ├── run-report.json                     真机终态 FAILED，success_count=195
        ├── operation-journal.jsonl             401 行：197 OK + 204 FAILED
        ├── events-0001.jsonl                   195 条真实入流事件
        └── events-0001.manifest.json           制品清单
```

> 泳道交付时的 9 个 `tmp-*` 已由总控按 §2 处置：**只删除活 token，其余改名保留**。

---

## 13. 总控独立复核与裁决（2026-09-12 08:40–08:50）

复核时现场：`Get-Date` = 2026-09-12 08:40（星期六），**8090/8091/8092 全部无监听**（隔夜中断，见开发过程记录 F-07）。

### 13.1 独立重跑（不采信泳道结论）

| 项 | 实测 |
| --- | --- |
| 命令 | `mvn -o test -f synthetic-data-generator/pom.xml`（`MAVEN_OPTS=-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`） |
| 结果 | **`Tests run: 96, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / 退出码 0** |
| 时刻 | 起 `08:40:23`，讫 `08:40:44` |
| 日志 | `verify/e2-s4b-module-tests-TOTAL-CONTROL-rerun-20260912.log` |
| 附加结论 | **三个程序全停的情况下仍全绿** ⇒ 该套件自带夹具、**不依赖** 8090/8091/8092（顺带消解"E2 是否偷偷依赖真机"的疑问） |
| 17 个测试类 | 与泳道 §4 逐类数字**完全一致**（含 `MallApiGenerationEngineTest` 13、`MallApiGenerationSmokeTest` 8） |

E1（编译）由本次 `test` 生命周期包含，未单独重跑。

### 13.2 制品复算（从原始 jsonl 重新统计）

| 断言 | 泳道报告 | 总控复算 | 结论 |
| --- | --- | --- | --- |
| `operation-journal.jsonl` 行数 | 401 | **401** | ✅ |
| 其中 OK / FAILED | 197 / 204 | **197 / 204** | ✅ |
| 分类明细 | createOrder 69/102、pay 26/39、cancel 43/62、refund 5/1、createSyntheticUser 33、listProducts 21 | **逐项相同** | ✅ |
| `canonical_id` 为空的行 | 1（唯一真预检 HTTP） | **1** | ✅ |
| `events-0001.jsonl` 行数 | 195 | **195** | ✅ |
| 事件类型分布 | 33/20/69/26/43/2/2 | **33/20/69/26/43/2/2** | ✅ |
| `run-report.json` | FAILED、success 195、failed 0、`RUN_FAILED` | **相同**，且 `error_message` 带商城原文 `INSUFFICIENT_STOCK` | ✅ |
| `event_stats` 为空（D10） | 是 | **是**（`[]`） | ✅ 登记成立 |
| 证据内凭据残留 | —— | 全目录扫描 `eyJ…` / `"token":"…"`：**无** | ✅ |

**未复取项**：§9 的 8092 进程替换历史（`23424 → 40996`）与 §5.3 的四次真机运行 —— 复核时进程已不存在、运行目录在 `generator-output/`（属现场而非证据目录），**凭泳道自述记录，不写成已证**。

### 13.3 裁决（对应 §12）

| 编号 | 事项 | 裁决 |
| --- | --- | --- |
| Q1 | 是否给参考商城补库存以取"大规模真机 SUCCESS" | **批准**（向自建商城追加库存属**追加性**写入，非破坏性）。但**不在本轮执行**：三个程序当前全停，关键路径是 P1-05 → 换血 → P1-06。列为 M1-4 后续项，由总控在 P1-06 之后连同 8090/8092 起停一次做完 |
| Q2 | `generator_meta` 32 计划 / 34 运行 / 34 个输出目录是否清理 | **不清理**（破坏性数据操作，且这 34 次运行正是"真机被真实拒绝"的现场）。转为 P5 现场整理项，届时按范围单独确认 |
| Q3 | 9 个 `tmp-*` 是否删除 | 已按 §2 处置：**只删活 token，其余改名保留**（红灯对照顾虑见 §2） |
| Q4 | D1（`TargetConfig` 首参）、D2（DTO 字段最小设计） | **暂接受，但必须契约先行**：M1-9（第二个 `MallTargetAdapter` / 不同字段夹具）时先把这两处写进指导书 §4.1 契约再实现；若契约不接受首参，则改为适配器实例持有配置以恢复九方法原签名。已登记为 M1-9 必办项 |
| —— | D10（失败运行无逐类事件统计） | **接受本轮不修**，但登记为 M1-4"缺的另一半"（写进看板证据列）⇒ M1-4 S4b 定级 `DONE_LIMITED` |
| —— | D12（流水把"本地对齐记账"写成 `listProducts`，高估对商城的读请求量） | **接受，但列为必改**：下一轮给流水加 `real_http` / `local_accounting` 显式字段。它污染"对商城真实调用量"这类下游统计，属**证据真伪**问题，不是风格问题 |

### 13.4 定级

**M1-4 S4b = `DONE_LIMITED`**（依据看板 L20：部分交付必须写明缺的另一半）。缺的另一半三项：① 大规模真机 SUCCESS（真库存被打空挡住，待补库存）；② D10 失败路径逐类统计；③ D12 流水"真调用 / 本地记账"字段。其余（E1 绿、E2 96/0/0/0 且总控独立复现、E3 真机正向 9 例 + 真实拒绝 204 次均落盘）**已验收**。

---

## 12. 待总控裁决

> **总控裁决见 §13.3（已于 2026-09-12 08:50 给出）。** 下表为泳道提出时的原文，保留不改。
> 注意：泳道最终报告里的 Q5/Q6 与本表 Q5 编号错位（报告 Q5=D12、Q6=D10），**裁决按主题而非编号**对应。

| # | 事项 | 影响 |
| --- | --- | --- |
| Q1 | **是否允许给参考商城补库存**（经 `POST /api/v1/admin/products/{id}/stock`）以便补取"大规模真机 SUCCESS"证据？ | 直接决定 U1/U3 能否消解。若不允许，MALL_API 的真机正向证据就止步于 `event_count=9` |
| Q2 | `generator_meta` 里 32 个 `s4b-*` 计划 + 34 条运行 + `generator-output/` 34 个运行目录是否清理？ | 现场整洁度；清理属破坏性数据操作，我不自行执行 |
| Q3 | `verify/tmp-*` 9 个残留是否按 §2 建议删除？ | 避免"红灯旧日志"被误读为现状 |
| Q4 | D1（`TargetConfig` 首参）与 D2（DTO 字段最小设计）是否接受？ | §4.1 未定义，需契约所有者确认 |
| Q5 | D10（失败运行逐类统计为空）本轮**不修**，是否接受？ | 若要求补，需改 `GenerationRunService` 的失败路径持久化，属新一轮小改 |
