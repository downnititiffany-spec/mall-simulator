# T01/T02 与 S01 交付复核（总控第二版范围 ①）

- 复核时点：2026-09-14 12:16–12:22 采集的日志（本文件于 12:5x 逐条读取），另于 12:38 复核目录与 HEAD
- 复核对象：`docs/acceptance/v25-t01-t02-baseline-20260914/`（48 文件 / 272,937 B / mtime 12:15:29）、`docs/acceptance/v25-s01-it-safety-20260914/`
- 复核方式：**读日志本体**（`raw/final-*.log` 的 `### EXIT=` 与 `Tests run:` 聚合行、surefire 报错行），不采信「目录存在」或 README 自述
- 纪律：只读；未跑任何 Maven；本文件的每条结论都指向具体日志文件与行内容

---

## 1. 逐条退出码（实测，按时间序）

| 时刻 | 日志 | EXIT | 聚合结果 | 说明 |
|---|---|---:|---|---|
| 12:16:37 | `final-01-t01-red-legacy-probe.log` | 1 | 3 tests / 1 failure | **T01 旧口径红**（`WarehouseNameLiteralGateLegacyProbeTest`）——修复前的红证据 |
| 12:16:42 | `final-02-platform-app-am-test.log` | 1 | 81 tests / 3 F / 3 E | 中途态；含红探针 |
| 12:16:49 | `final-03-platform-common-test.log` | 1 | — | 中途态 |
| 12:16:55 | `final-04a-t02-contract-group.log` | 1 | — | **T02 契约组未能执行**（见 §2） |
| 12:17:01 | `final-04b-t02-runtime-patrol-group.log` | 1 | — | **T02 巡检组未能执行**（见 §2） |
| 12:17:06 | `final-05-platform-app-am-test.log` | 1 | 78 / 1 F / 3 E | 中途态 |
| 12:17:12 | `final-06-full-reactor-test.log` | 1 | 78 / 1 F / 3 E | 中途态 |
| 12:17:33 | `final-07-spark-jobs-jdk8-test.log` | 1 | 0 tests | 中途态 |
| 12:19:02 | `final-10a-t02-contract-group.log` | 1 | — | **T02 仍未执行**（见 §2） |
| 12:19:06 | `final-10b-t02-runtime-patrol-group.log` | 1 | — | **T02 仍未执行**（见 §2） |
| **12:19:11** | **`final-11-t01-warehouse-gate-green.log`** | **0** | **33 / 0 F / 0 E** | ✅ **T01 绿**：`WarehouseNameLiteralGateTest` 9 + `WarehouseNamespaceContractTest` 24，`BUILD SUCCESS` |
| **12:19:17** | **`final-12-verbatim-platform-common-test.log`** | **0** | **79 / 0 F / 0 E** | ✅ **platform-common 整模块绿**（含 `RuleSeverityTest` 14 / 0 F）⇒ **E1 恢复** |
| 12:20:41 | `final-13-verbatim-platform-app-am-test.log` | 1 | 131 / 1 F / 1 E | 红在 warehouse-pipeline（`EventContractTest` 1 E、`PipelineServiceTest` 1 F）；**platform-app 未执行（SKIPPED）** |
| 12:20:53 | `final-14-verbatim-full-reactor-test.log` | 1 | platform-common FAILURE | 12:19:19 起**其他泳道**新建的 `TestIsolationGuard*` 打断（§3） |
| 12:21:03 | `final-15-full-reactor-collect.log` | 1 | platform-common FAILURE | 同上 |
| 12:21:38 | `final-16-spark-jobs-jdk8-test.log` | **0** | **0 tests / 0 F / 0 E** | ✅ BUILD SUCCESS，但 **`Tests run: 0`** ⇒ 见 §4 |

---

## 2. 【关键更正】T02 的两个测试类**从未在 Maven 下执行过**

`final-04a/04b`（12:16:55/12:17:01）与 `final-10a/10b`（12:19:02/12:19:06）四条命令都在 **platform-common** 处中止，surefire 原文：

```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.1.2:test
(default-test) on project platform-common: No tests matching pattern
"IngestionManifestSourceSchemaTest" were executed!
(Set -Dsurefire.failIfNoSpecifiedTests=false to ignore this error.) -> [Help 1]
```

- 命令用了 `-pl platform-app -am -Dtest=<类名>`：`-am` 会**先对被依赖模块**（platform-common）执行同一 `-Dtest` 过滤 ⇒ 在 platform-common 找不到该类 ⇒ 直接失败，`platform-app` 被 **SKIPPED**。
- 传的属性名 `-DfailIfNoSpecifiedTests=false` **不是** surefire 认识的键（正确键为 `-Dsurefire.failIfNoSpecifiedTests=false`，surefire 自己的报错信息里就是这么写的）⇒ 该参数未生效。
- `final-13`（`-pl platform-app -am test`，无 `-Dtest`）本应跑到 platform-app，但 **warehouse-pipeline 先 FAILURE** ⇒ 后续模块全部 SKIPPED，platform-app 依旧未执行。

**结论（可核对）**：
- **T01 有 Maven 级红→绿双证据**（12:16:37 EXIT=1 红 / 12:19:11 EXIT=0 绿，同一条扫描树上 `RAW hits=2 → CODE hits=0`，且负例仍报红 ⇒ 非空跑）。
- **T02 没有 Maven 级证据**：其断言只在**独立探针** `raw/probe/t02-probe.log` 下通过（`PROBE-OK`：39+4 冻结副本哈希全中、契约集合差成立、真实目录 0 违规、回填/改写/缺失/目录不在四个负例判红、新到件不判红）。
- ⇒ T02 当前证据强度 = **强推断（探针级已证 + Maven 级未取证）**，**不是**「测试通过」。修法很轻：把 `-pl platform-app -am` 换成 `-pl platform-app`（前置模块已 install 时）或改用 `-Dsurefire.failIfNoSpecifiedTests=false`，然后补一条 `-Dtest=IngestionManifestSourceSchemaTest` 与一条 `...RuntimePatrolTest` 的 EXIT=0 日志即可收口。**本任务只读，未代跑。**

---

## 3. 12:20:53 / 12:21:03 的 platform-common 红**不是** Q01 的回归

- `final-12`（12:19:17）platform-common **绿**；`final-14/15`（12:20:53/12:21:03）同模块 **红**。两次之间无本泳道改动。
- `raw/final-17-foreign-red-attribution.txt`（12:21:38，HEAD=`3fcf90e`）实测：`TestIsolationGuard.java`、`TestIsolationGuardTest.java` 在 HEAD 中**不存在**（`git cat-file -e HEAD` 退出码 **128**），mtime 12:20:03 / 12:20:09，属其他泳道**运行期间新建**；`TestIsolationGuardTest` 在 `final-05/06/13/14/15` 里正是那个 1 F / 3 E 的来源。
- ⇒ **「编译红不得记作他人测试失败」这条新纪律在此得到实证**；同时说明：本工作树在 12:19–12:22 期间存在**多泳道并发写**，任何单人时刻的「全绿」结论寿命以分钟计。

---

## 4. spark-jobs 出现 `Tests run: 0`（与 09-14 汇总矛盾，需收口）

- `final-16-spark-jobs-jdk8-test.log`（12:21:38）：`BUILD SUCCESS` / `EXIT=0`，但聚合行是 `Tests run: 0, Failures: 0, Errors: 0`，且 `MetricAdsSpecTest` 单类也是 `Tests run: 0`。
- 对照 `docs/acceptance/local-readiness-20260914.md` L18–L20：「spark-jobs 111 通过（JDK8，15 套件）」。
- ⇒ 两者**不可能同时为真**：要么本次 surefire 未收集到 Scala 测试（编译/发现配置问题），要么 09-14 的 111 来自不同命令/更早状态。**本任务不跑 Maven，故记为「矛盾·未取证」**，交由 spark-jobs 泳道用一条 `-Dtest` 之外的完整 `mvn -f spark-jobs/pom.xml test` 日志收口。
- 注意：`EXIT=0` + `Tests run: 0` 是**典型的假绿**，任何「0 测试即通过」的引用都必须同时引用测试数。

---

## 5. S01 状态（12:38 复核）

- `docs/acceptance/v25-s01-it-safety-20260914/` **仍不存在**（`Test-Path` = False）⇒ 看板 §4 该登记行的证据链接**仍是悬空指针**。
- 结合总控裁决 Q1（不预建空目录、登记行写预期路径、交付后就地更正）：该行当前应视为**预期路径**，但**未交付**这一事实必须保留在登记里，不能因为「路径合规」而读成已交付。
- `HEAD` 已由 `6062434` → `09d7046` → **`3fcf90e`**（12:21:38 时点）；dirty = **61** 行（12:38 实测）。

---

## 6. 本文件不能证明什么

1. **不能**证明 T02 的业务要求已满足：探针覆盖的是「冻结副本哈希 + 契约集合差 + 目录巡检四类负例」，**不等于** §5.7 清单新字段的端到端取值正确（那是 E3 的事）。
2. **不能**证明 platform-app 整体绿：`final-13` 只证明 warehouse-pipeline 有 1 F / 1 E，platform-app 从未执行。
3. **不能**证明 T01 的扫描器已覆盖全部载体：扫描范围与扩展名 fail-closed 行为由该泳道断言，本任务只核了 EXIT 码与用例数。
4. **不能**把 `final-11/final-12` 的绿推广到整仓：同一工作树并发写，`final-14/15` 即为反例。
5. **不能**证明 12:19:11 与 12:19:17 的绿在**当前** HEAD（`3fcf90e` + 61 行 dirty）上仍成立——绿是那两次运行的属性，不是仓库不变量。
