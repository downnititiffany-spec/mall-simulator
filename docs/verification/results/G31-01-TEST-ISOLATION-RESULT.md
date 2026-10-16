# G31-01 测试隔离与可重复执行 — 结果登记（V3.1 指导书）

**判定：PASS（01.1~01.5 全部满足；01.4 为 audit-only 结论）。**
- 执行日期：2026-09-20（00:29–00:41 +08:00）
- 证据 RunId：`g3101_20260920_003500`（仅证据标签）；数据域复用 `stage7q1_20260918_152245` 四库（3307）
- 代码基线：本地 HEAD `38cc0b3` + 本工作包未提交改动（本文件登记后分两笔本地提交：代码→docs；push 规则：仅本地提交，先前授权已用尽）
- 平台 jar：`platform-app-0.1.0-SNAPSHOT.jar`，SHA256 `762d293d21b0bd010e2980041b9468f5ac429cc61c11e2d00ca17ab97b1f8c03`（33,375,709 B，2026-09-20 00:29 重建——application.yml 去兜底后重编，**旧 jar SHA 钉定 9e79f1b3… 作废**；本工作包起 driver 只记录 SHA 不再钉死，见 D-022）
- 指导书依据：`docs/guidance/项目完整实施指导书 V3.1.md` §5（G31-01，行 111–133）

## 0. 边界遵守声明

- **3306 永久冻结零接触**：本轮全部链路 URL 均为 `jdbc:mysql://127.0.0.1:3307/...`；F2b 平台日志 `:3306` 清查零命中（50 行全扫）；session-2 收尾清查再次零命中；**未为「查证是否误连」探测过 3306**（5.1.5）。
- **无范围 DROP 零次**：prep 幂等（CREATE IF NOT EXISTS + ALTER USER 重置 + GRANT），未清理任何旧 runId。
- **root 只用于已授权隔离准备**：root + W03 受控登记的文档化值仅出现在 WSL mysql 进程环境（`MYSQL_PWD`，bash -c 字符串内），应用查询/探针一律走本次 runId 的受限账号。
- **口令纪律**：三把新口令进程内 crypto-RNG 生成，零落盘/零 argv/零 git/零日志回显；账本只记凭据**引用名**。

## 1. 01.1 配置同源核对 + 负例启动前失败 —— 满足（实跑）

**配置消费方清单（5.1.1 全量盘点）**：
| 消费方 | 键 | 缺配行为 |
|---|---|---|
| `PlatformDataSources` | 9 个 `platform.{meta,metric.publish,metric.read}.{url,username,password}` | `env.getProperty` 为 null/blank ⇒ 构造期 IllegalArgumentException ⇒ 上下文启动即失败 |
| `EnvCredentialService` | `platform.metric.read.password`（`@Value` 字面默认） | 平台上下文中 yml 占位符必先解析失败，走不到该默认（注释已更正说明） |
| `PipelineService` | `platform.metric.publish.export-dir:metric-staging` | 文件系统默认，与数据库无关，保留 |

**单一配置源改造（本工作包代码改动）**：
- `application.yml` 三个库的 url/username/password 全部改为裸占位符 `${PLATFORM_*}`，**删除写死的 `jdbc:mysql://127.0.0.1:3306/...` 兜底默认**（5.1.2 不回落原则的内层防线：变量缺失 ⇒ 占位符解析失败 ⇒ Spring 上下文启动即拒绝）。
- 新建共享门禁 `scripts/assert-platform-env.ps1`（外层防线，先于任何 JVM/连接池/Flyway）：13 个 `PLATFORM_*` 必填核对（缺失/空白 ⇒ 「缺失必填变量 X（不允许回落任何配置文件默认值，5.1.2）」）、URL 必须以 `jdbc:mysql://127.0.0.1:3307/` 开头、库名必须在 `-AllowedSchemaPrefixes` 白名单内、**任何变量值含 `:3306` 即拒**（URL 值可打印、其余只报名不回显值）。拒绝统一 exit 12。
- 启动 driver（`target/v25-it/g3101_20260920_003500/session/g3101-session1-start.ps1`）与 prep、run-tests.ps1 isolated 预检共用进程 env 单一来源（driver 进程内生成 `V25_IT_*` 并设置 `PLATFORM_*`，子进程全部继承）。

**负例实跑（子进程调共享门禁；门禁纯字符串核对，结构上无任何网络调用 ⇒ 负例不可能触碰 3306）**：
| 用例 | 篡改 | 结果 |
|---|---|---|
| N1_missing_var | 删除 `PLATFORM_META_PASSWORD` | exit 12（378ms），「缺失必填变量 PLATFORM_META_PASSWORD」，refused=True |
| N2_url_3306 | meta URL 改指 `127.0.0.1:3306/<metaDb>` | exit 12（371ms），「指向禁连目标」，refused=True |
| N3_wrong_schema_prefix | publish URL 改库 `otherdb_x` | exit 12（344ms），「不在允许前缀内」，refused=True |

三轮负例期间平台 JVM 计数恒 0、8091 全程空闲——**负例在任何 JVM 启动之前被拒**。正例同门禁同代码路径：`assert-platform-env：OK（13/13 个必填变量；URL 作用域 jdbc:mysql://127.0.0.1:3307/；库名前缀 stage7q1_20260918_152245_；无 ':3306' 标记）`。原始输出留证 `session/negatives/*.out.txt`。

**启动后第二道检查（F2b，5.1.3 只作补充）**：平台日志零 `:3306`、两个 3307 JDBC URL 与 landing root 均有出现（`postStartGuard` 四项 True，证据 `session-result.json`）。

## 2. 01.2 资源账本 + PID 复用拒绝 —— 满足（实跑）

- **资源账本**：`ledger-run.json`（运行中即写，attempt-1：进程 PID/名/启动时刻、端口 3307/8091、目录、四库、凭据**引用名**清单）；停止后 `ledger-stop.json`（ownedTree/stoppedPids/port released/sweep3306/outcome）。
- **PID 复用拒绝**（`scripts/stop-platform-by-pidfile.ps1`，身份证据 = pidfile + identity.json{pid,name,marker=runId}，进程存在时还须命令行含标记）：
  - C2（命令行缺标记）：伪造 identity 指向无关牺牲 sleeper ⇒ **exit 5，一个进程未杀，牺牲进程事后仍存活**（ledger outcome=REFUSED）。
  - C3（进程名与登记不符）：登记 cmd.exe 实为 pwsh.exe ⇒ **exit 5，未杀任何进程**。
- **只停自有进程树**：CIM ParentProcessId 闭包，C1 实停 `34068(cmd.exe) → 13800(conhost.exe) → 29800(java.exe)` 三节点，**绝无按 java 进程名的组杀**。
- 停止后 8091 释放确认（TcpClient 探测失败=已释放）+ 日志 `:3306` 清查零命中 + 账本落盘，全部在 exit 0 之前。

## 3. 01.3 driver 生命周期交接 / 中断可独立清理 —— 满足（实跑）

- driver-1（`g3101-session1-start.ps1`）退出码 0，**平台有意存续**（wrapper 34068/java 13800，`platform.pid` + `platform.identity.json` 落盘，outcome=PLATFORM_RUNNING，platformLeftRunningByDriver1=true）。
- driver-2（`g3101-session2-cleanup.ps1`）在**另一次独立 pwsh 调用**中仅凭 pidfile+identity.json 完成身份核对→停树→端口→3306 清查→账本，全程 exit 0——原 driver 已退出后清理照样可执行（驱动被中断时同理，脚本本体不依赖调用方会话）。
- **清理失败绝不报 PASS**：stop 脚本端口未释放/日志出现 :3306/进程未退 ⇒ exit 7，身份不符 ⇒ exit 5；调用方契约按非 0 处理。C4 另证幂等：PID 已不存在（999999）⇒ exit 0（ALREADY_GONE，仍完成端口+日志双清查）。

## 4. 01.4 报告新鲜度与失败分类守卫 —— 满足（audit-only）

指导书允许以「已实现」为结论登记审计。`scripts/run-tests.ps1` 现行守卫（行号为当前文件实测）：
1. **硬门禁**：统一「用例数 N > 0 且 F=0 且 E=0 且模块退出码 0」——零用例/假绿一律判失败（头部行 7、行 26）。
2. **spark 档只认** `spark-jobs/target/surefire-reports/TestSuite.txt`（ScalaTest 产物），文件不存在即判失败（行 1153），**禁止用 surefire `Tests run: 0 / BUILD SUCCESS` 当成功依据**（行 27–29）；且要求 TestSuite.txt **mtime ≥ 本轮启动时刻**（行 1158、1161 新鲜度打印）——旧文件冒充本轮结果被拒。
3. **并发互斥**（S3-51，行 1185+）：同 RunId/同日志目录抢锁失败 ⇒ `[REFUSE exit=5]` 响亮拒绝，不静默降级。
4. **截断防护**：TestSuite.txt 解析含中止行检测（`-CaseSensitive` 区分良性 `aborted 0` 行）与逐模块汇总行/明细交叉核对（本轮摘要即按「模块汇总行 + 明细」双口径打印）。

**本轮 fresh 回归（application.yml 去兜底后）**：RunId `dev003c_20260920_002959_f9380b`（日志 `C:\Users\ASUS\AppData\Local\Temp\v25tests-dev003c_20260920_002959_f9380b`）：analytics-server 1039（F=1 E=0 S=1）MATCH / mall 14 MATCH / generator 111 MATCH，合计 1164 = 基线 1164；**唯一红 = 既有环境性红** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（宿主盘面历史 json 计数断言，与本改动无关，既往多轮同红登记在案）；suite exit 7 即该分类的预期表现。jar 重建 `mvn -o -DskipTests package` exit 0。

## 5. 01.5 最小隔离配置说明 —— 满足

已发布 `docs/verification/ISOLATED-EXECUTION-MINIMAL-GUIDE.md`：可重复的最小隔离执行说明，**全文无任何密钥/口令字面值**（root 口令仅以「W03 受控登记的文档化值」指称），通道只列变量名。

## 6. 5.1.4 发布/只读分权（同工作包要求）—— 满足（实跑）

- 新第四口令通道 `V25_IT_METRIC_READ_PASSWORD`（进程 env）⇒ prep 新建 **SELECT-only** `metricread` 账号 `stage7q1_202_0598ff44_metricread`（32 字符 hash 回退命名）；`PLATFORM_METRIC_READ_*` 接该账号（向后兼容：env 缺省 = 旧行为）。
- DB 层授权审计（root 仅 WSL mysql 进程 env）：`metricread` = `GRANT SELECT ON <metricDb>.*` 且无 ALL PRIVILEGES/INSERT/UPDATE/DELETE/CREATE/DROP/ALTER/INDEX、不波及 meta 库；metaapp/metricapp = 各自库 ALL（发布与只读**分别授权**）。留证 `session/grant-audit/{metaapp,metricapp,metricread}.txt`。
- **只读链路真探针**：平台以 metricread 账号经 `MySqlMetricStore→metricReadJdbcTemplate` 响应 `GET /api/v1/metrics/snapshots`（HTTP 200，data=3 条真实 ADS 快照）与 `/overview`；`metric-read-ds` 连接池出现在平台日志（`readProbe.metricReadPoolInLog=True`）。登录冒烟 admin/admin123 通过。
- 数据完好性门（复用 BATCH-W C4 v2 逐行精确匹配）：`SNAP|S20260918_12|ACTIVE` 唯一、ADS 2/8/2/4/4/8、sys_user=3。

## 7. 证据清单（`target/v25-it/g3101_20260920_003500/session/`）

| 文件 | 内容 |
|---|---|
| `session-result.json` | driver-1 全程证据（prep/integrity/grantAudit/negatives/preStartGuard/postStartGuard/loginSmoke/readProbe/outcome） |
| `session2-result.json` | driver-2 四项检查（C1/C2/C3/C4）与 outcome=CLEANUP_AND_NEGATIVES_PASS |
| `ledger-run.json` / `ledger-stop.json` | 01.2 运行中/收尾资源账本（凭据只记引用名） |
| `platform.pid` / `platform.identity.json` | 01.3 交接凭据（marker=RunId） |
| `logs/prep.log` / `logs/platform.log` / `logs/stop-c1..c4.log` | prep 输出（含 metricread 列名）、平台日志（零 :3306）、四轮停止日志 |
| `negatives/N1..N3.out.txt` + `positive.out.txt` | 共享门禁原始输出 |
| `grant-audit/*.txt` | SHOW GRANTS 三角色留证 |
| `integrity.sql` / `integrity-capture.txt` | 完好性门 SQL 与捕获 |

## 8. 本工作包决策（详见 DECISION_LOG D-022）

1. **yml 兜底默认删除**（而非仅加门禁）：双防线（外层=共享门禁脚本不启 JVM；内层=占位符解析失败拒启上下文）。
2. **数据域复用** `stage7q1_20260918_152245` 四库（幂等 prep 先例 + 完好性门可复用 + 只读探针取真实 ADS 数据更有力），**账号仍为本轮新建**。
3. **旧 jar SHA 钉定作废**：yml 改动必然改 jar；此后 driver 记录 SHA 供追溯、不再硬性钉死（钉死语义回归到「被测代码基线=git SHA」）。
4. **负例插值修正**：N2/N3 篡改串改为 driver 侧插值后的字面量（子 pwsh 无 $result/$jdbcSuffix，scriptblock 原文传递会静默展开成 null）——执行前发现并修正，非门禁缺陷。
5. 01.4 按 audit-only 登记（守卫已实现且本轮 fresh 回归实证）。

## 9. 剩余范围

本工作包关闭后，V3.1 下一工作包 = **G31-02 第二来源 fixture-shop-b（02.1~02.6）**；其后 G31-03~G31-07 依序。本结果不证明：浏览器 E2E 之外的端到端、真实 LLM、Flume/HDFS 整链（G31-05）、完整项目验收。
