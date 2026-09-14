# 总控独立复核：F-88 写侧闭环（2026-09-14）

- 复核对象：`8853730`（写侧代码＋测试＋证据，12 files +1270/−26）＋ `5180baf`（仅报告去自指哈希）
- 复核前基线：`00a38e8`；`git status --porcelain` 空；`git diff --name-only 8853730 HEAD` 只含 `REPORT.md`
- 复核方式：**不采信泳道自述**，静态逐行复核 ＋ 总控独立复跑同一命令 ＋ 断言强度核对 ＋ 无 DB 痕迹核对

## 1. 独立实测（总控自己跑的）

| 动作 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 独立复跑模块套件 | `mvn -o "-Dmaven.repo.local=D:\maven_repository" -f analytics-server/pom.xml -pl warehouse-pipeline,platform-common -am test` | **exit 0**；`Tests run: 81 / 156 / 134, Failures: 0, Errors: 0`；`BUILD SUCCESS`；模块 SUCCESS[]=4 | `raw/04-controller-independent-rerun-module-tests.log` |

与泳道自述的 `81/156/134`、exit 0 逐项一致（**独立复现，非引用**）。
该复跑日志中独立出现 `pipeline 1: 规则冻结 ruleFingerprint=6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6 catalog=qrc-1 compatPolicy=compat-v1` ⇒ `compatPolicyVersion` 常量确为 `compat-v1`、目录版本 `qrc-1`（与 V20 注释一致）。

**无 DB 痕迹核对**（三份日志一起查 `3306|3307|Flyway|flyway|jdbc:mysql`）：
`01-…test.log` 0 次、`02-…test-compile.log` 0 次、`04-…rerun.log` 0 次。⇒ 模块级验证确实未触库。

## 2. 静态逐行复核（总控亲读，非日志推断）

- `entity/DataQualityResult.java`：四列齐备且为包装类型 —— `:58 effectiveSeverity`、`:75 ruleVersion(Integer)`、`:85 compatPolicyVersion`、`:96 ruleFingerprint`。用 `Integer` 而非 `int` 是**必要**的（否则 NULL 无法表达）。
- `QualityChecker.java:263-280 applyVersionedSeverity(...)`：`:274 severity←declaredSeverity()`、`:275 effectiveSeverity←effectiveSeverity()`、`:277 ruleVersion←registered?version:null`（**不落哨兵 0**）、`:278-279` 策略版本与指纹**恒写**；`:265-271` 的 `verdict==null` 分支只落 NULL、不猜值，与 V20 `:30-33`「缺省必须由写侧显式给出」一致。
- `PipelineService.java:786-795`：`persistChecks` 与 `persistQuality` 共用同一实现（无第二套写法）；`:794-795` 的 `cap(...,16)` 读的是 `:792` 刚写入的值。
- `RuleSeverity.java:62` 亲验：`UNREGISTERED = BLOCKING`（**别名常量**，非独立字面）⇒ 未登记行的 `effective_severity` 落库字面是 `"BLOCKING"`。
- 主代码 `getSeverity(` 全仓命中 **1 处**（`PipelineService.java:794`，读的是本行刚写入的值）；主代码无任何按"生效档位"语义读 `severity` 的读点 ⇒ 泳道「主代码读点 0 处」**成立**。

## 3. 断言强度核对（防"删断言变绿"）

`8853730` 相对测试文件的净删行共 12 行，**全部**是旧语义断言（`assertThat(q.getSeverity()).isEqualTo(生效档位)` 一类，在新契约下本身已错），逐条在 + 侧被替换为更强的两列断言：

- `QualityCheckerSeverityTest`：`effectiveSeverityOf(...)` 辅助方法替代 `severityOf(...)`；核心断言 `dup.getSeverity()==WARN` **且** `dup.getEffectiveSeverity()==BLOCKING` **且** `isNotEqualTo(dup.getSeverity())`；`getRuleFingerprint()` 另有 `hasSize(64)`；期望值取自 `QualityRuleCatalog.DEFAULT.find(code,null).orElseThrow().severity()` / `rules.find("EVENT_ID_UNIQUE").version()` —— **无硬编码 64 位指纹**。
- `PipelineServiceTest#sparkCheckResultsCarryDeclaredAndEffectiveSeverityPlusVersionInfo`：钉住 `persistChecks` 这个写点「作业回传字面 ERROR ⇒ severity 落目录声明 WARN、effectiveSeverity 落 BLOCKING」。
- 未登记用例：断言 `severity isNull` ＋ `ruleVersion isNull` ＋ `effectiveSeverity==UNREGISTERED` ＋ 策略版本/指纹非空，并**先断言前提** `UNREGISTERED==BLOCKING`（把我下达任务书里"落 UNREGISTERED 字面"的错误假设按事实纠正，未掩盖）。
- **结论：语义迁移，不是削弱。**

## 4. 总控裁决（对泳道提出的 8 条）

| # | 事项 | 裁决 |
|---|---|---|
| ① | `UNREGISTERED` 与 `BLOCKING` 同值 ⇒ 未登记行如何识别 | **采认**：判据为 `severity IS NULL AND rule_version IS NULL`（不可只看 `effective_severity`）；**不新增字面哨兵**（会与 `RuleSeverity` 既有语义冲突，且属 R5 禁改面）。该判据须写进 V2.4 并约束后续读侧实现。 |
| ② | `MetricController./quality` 是否读时归一化 | **暂不改**，并入 F-93（读侧）条目统一裁决；但登记为 F-93 子项，避免漏项。 |
| ③ | 列宽 `cap` 静默截断是否改报错 | **不改**：`severity`/`effectiveSeverity` 的取值域是代码常量（`BLOCKING/ERROR/WARN/INFO`，最长 8 字符）与目录声明值，`cap(...,16)` 理论不可达；属防御性代码。**登记为「不可达的防御性截断」，不追加构建轮次。** |
| ④ | 读侧跨 run 冻结（F-93） | 独立条目，排在隔离真链之后处理。 |
| ⑤ | `rulesFor()` 改读 `quality_rule_definition`（D-2） | 本轮不做，独立裁决；与 F-93 同属"读侧按落库版本判定"族。 |
| ⑥ | 是否可声称「规则版本化已闭合」 | **采认"不得声称"**：迁移未在目标库执行证据、无一次真实 run 落库取证、读侧重算未做，三缺。 |
| ⑦ | `RuleSeverityPathConsistencyTest` 是否补等价反证 | **不补**：该文件注释已回指 `DataQualityGateTest#gateIgnoresBothSeverityColumns`，反证已存在，不重复。 |
| ⑧ | V20 迁移执行排期 | **下一步即隔离库（3307）**：(a) 重建 jar 使 `db/meta` 含 V1–V20；(b) 由 `MetaFlywayInitializer` 在**隔离** `analytics_meta` 上落地并取证；(c) 重跑真链断言四列落库。**3306 仍须 D-5 启动前门禁通过后另行裁决**，本轮不得对 3306 执行。 |

## 5. 结论分级（分项判定，不合并）

| 分项 | 判定 | 依据 |
|---|---|---|
| 代码提交 | **提交完成** | `8853730` + `5180baf`，工作区干净 |
| 单元测试 | **测试通过（模块级）** | 总控独立复跑 exit 0，81/156/134 全绿 |
| 写侧契约违例修复 | **限定验收** | 「severity=声明 / effective_severity=生效 / 版本三列」在单测级已钉死；真实落库未验 |
| 规则版本化整体 | **未验收（未闭合）** | 迁移未在目标库执行、无真实 run 落库、读侧未接 |

## 6. 未取证（不得当通过）

真实落库四列值（V20 未在目标库执行）· 端到端 run · `platform-app` 测试**运行**（会触发 Flyway→3306，本轮仅编译）· `metric-analysis`/`ai-decision` 测试运行 · 页面渲染 · `VARCHAR(16)` 极端值（已裁为不可达）· 在线依赖解析 · spark-jobs 是否旁路写 `data_quality_result`（静态仅注释命中，无 INSERT）。
