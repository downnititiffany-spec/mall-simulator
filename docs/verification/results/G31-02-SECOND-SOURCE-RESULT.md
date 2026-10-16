# G31-02 第二来源 fixture-shop-b 全链（02.1~02.6）— RESULT

> **结论：PASS 已收口（2026-09-20）**。指导书 V3.1 §6.3 工作包 G31-02 六个子任务 02.1~02.6 全部满足验收；决策 D-023~D-026、D-029、D-030、D-031、D-032（D-027/D-028 永久空缺，见 DECISION_LOG 编号注记）。
> 证据根：`target/v25-it/g3102_20260920_014827/`（子目录 `022-evidence/` `023-evidence/` `024-evidence/` `025-evidence/` `026-wizard-e2e/` `027-independence/` + `landing/` `samples/` `logs/`）。
> 平台态：stage7q1 栈（8091 平台 / 3307 RunId-scoped 双库 / vite 5173），收尾恢复 source 1 `mock-mall` current=true、source 2 `fixture-shop-b` current=false（A-B-A 收口态保持）。
> push 规则：**仅本地提交**（先前一次性 push 授权已用尽，远端 HEAD 仍为 `883dcff`）。

## §1 验收逐条对照（指导书 V3.1 §6.3 表）

### 02.1 — A/B 输入、字段映射、oracle、hash 清单
**验收**：逐事件预期与金额/人数独立算出，不调用待测计算器生成 expected。
**满足**：oracle 唯一属主 `fixtures/source-b/fixture-shop-b/ORACLE.md`（A 金样本与 B 夹具逐事件预期独立手工核算，金额/人数独立算出）；B 夹具 `fixtures/source-b/fixture-shop-b/{normal,fault}.jsonl`；hash 清单 = 画像制品 sha256 口径（`analytics-server/source-profiles/fixture-shop-b.v2.json`，profileVersion 2.0，sha256 `3ee7d8fae3a4…848da1`，与平台态一致——向导幂等激活 ① 即以该 hash 判定 changed=false）。夹具补面决策 D-024（B 在 A 金样本缺失面补齐 product_created/behavior/user_registered，使 8 类事件映射全面覆盖）、故障集决策 D-025（5 类互异映射违规）。

### 02.2 — B 注册、画像预览/激活
**验收**：processed=accepted+quarantined+systemErrors；空样本不可激活；hash 漂移 409。
**满足**：`022-evidence/`（01-login → 02-create-source（sourceId=2，warehousePrefix=`fxsb`，D-023）→ 03-source-test → 04/05/06-dry-run（normal/fault/empty）→ 07-activate-hash-drift-409 → 08-activate-mapping → 09-activate-source → 10-sources-after）：恒等式 processed=accepted+quarantined+systemErrors 全样本成立；empty 样本 fail-closed 不可激活；画像 hash 漂移 409 `MAPPING_PROFILE_CHANGED`。源级生命周期门（v2 画像按 V2_STRICT 键集分派顶层必备键）见 D-029。

### 02.3 — B 采集→ODS→DWD/DWS/ADS→MySQL
**验收**：真实任务 ID/日志/分区/制品；source 和版本全程一致。
**满足**：`023-evidence/`：staged 文件 `2026091821.jsonl`（sha256 `783a7609…`）→ ingestion batchId=15（`ing-20260920021439-9f3819f6`，SUCCESS，38 accepted / 0 quarantined，sourceId=2）→ pipeline runId=15 SUCCESS（idempotencyKey `g3102-023b-…`，sourceDataVersion `g3102-fxsb-20260920_024253`，businessTime 2026-09-18T00:00:00）→ ADS MySQL 落库。逐事件 oracle 41/41 + ADS readback 9/9（`07-metric-readback.json` + `readout.json`，真实 Spark 任务日志与 `readout-spark-submit.log`）；全程 sourceId=2 / sourceDataVersion 单值贯穿（快照/读回逐行核对）。

### 02.4 — A/B 并存与切源
**验收**：A-B-A 切换无旧图残留；同 ID 不串表/快照/缓存/AI 证据。
**满足**：`024-evidence/`（B→A 回程切源 attempt-1：fileCount=2/quarantineCount=38——B 残留文件在 A 画像检查点下重新可见被**全部隔离**，防御墙起效零入仓，登记为检查点语义事实 D-26→**D-026**；清洁复跑 `025-evidence/`：归档残留 + 新落盘文件名（D-026 每腿新文件名），batch 17 收 17/0 SUCCESS、batch 18 幂等 0/0）+ `15-B-snapshot-readback.json`（B 快照按 snapshotId 回读逐值不变）、`18-ai-evidence-pinned-B.json`（AI 证据钉定 B 快照不串）。dashboard 收口复核：repeat_rate=0.0 为平台正确值（S3-03 有效复购口径，全额退款单不计；手工预言 0.5 误用支付复购变体，D-030 伴生澄清，14/14 全绿 `025-evidence/27-close-verify.json`）。

### 02.5 — 最小管理员接入向导
**验收**：选源→受控样本→预览错误/覆盖率→确认激活；不要求编辑服务器文件。（指导书 §6.3 注：不开放任意服务器路径，预置受控样本引用即可。）
**满足**：新增 `web/src/views/SourceWizard.vue` + `web/src/utils/sourceWizard.js`（纯逻辑 node:test 直测）+ 路由/App 接线（admin 专属 `/sources/wizard`）；四步流：①选源（源列表，RUNTIME_MANAGE 只读）→ ②受控样本（预置 fixture-shop-b 三条 + 手工 sampleRef，服务端 sample-root fail-close 校验，不开放任意服务器路径）→ ③预览（dry-run 报告：错误 reasonCounts/覆盖率 requiredCoverage·enumCoverage/activationEligible 及语义说明——预览是 advisory preflight（D-030），激活门槛以 Loader/激活接口实际结果为准）→ ④确认激活（映射激活 + 源激活，幂等/真切换文案区分）。画像以**粘贴原文**提交（服务端对提交字节做 sha256 为权威 hash），全程不要求编辑服务器文件。Java 冻结表 +4 行 RUNTIME_MANAGE（`GET /sources`、`POST /sources/{id}/activate`、`POST /sources/{id}/mappings/dry-run`、`POST /sources/{id}/mappings/activate`）；dry-run 报告回查端点不接线（D-031：双占位符路径跨树不可对账 + 向导直接消费 dry-run 响应）。E2E **12/12**（真实 Chromium，`026-wizard-e2e/` 7 截图）：①同画像幂等 changed=false（「是否变更指针：否（同源同画像幂等，未重复绑定）」+ 授权报告 dr-20260920015208-976f54b8）、②真切换 ACTIVE + 「已切换为当前运行环境绑定的源」、负例 fault 样本 eligible=false（violations=5）→ 激活 409 `MAPPING_ACTIVATION_INELIGIBLE` fail-closed（审计落失败行）。

### 02.6 — 平台独立性
**验收**：关闭商城/生成器后历史指标可读；来源停机显示时效警告，不假报最新。
**满足**：独立性证据 **9/9**（`027-independence/independence-result.json`）：TCP 探测商城 :8090 与生成器 :8092 均 **refused**（进程未运行）、平台 :8091 在听（阳性对照）；双上游关闭下 `GET /metrics/overview`（14 cells，业务时点 day:2026-09-18）与 `GET /metrics/snapshots`（ACTIVE S20260918_17，businessTime 2026-09-18T00:00:00）照常可读——历史指标由平台自有库供数，不依赖商城/生成器存活。时效警告：新增 `web/src/utils/staleness.js`（唯一属主）+ Overview 琥珀色横幅——从指标载荷 `period` 取最大业务日期与**本地壁钟今日**比较（D-032：角色无关，不调 `/sources`；分析师无 RUNTIME_MANAGE 也能看到），滞后 ≥1 天渲染；实测滞后恰 2 天；文案「数据时点提示：页面指标的业务时点为 2026-09-18（滞后 2 天）。数据来源可能已停更或采集未运行；历史指标仍可读，但以下数字不代表最新业务日。」——同日不渲染且**绝不主动宣称「最新」**（单测将否定式「不代表最新」剥除断言零残留）。横幅 DOM 证据 **5/5**（真实 Chromium，`027-independence/shots/overview-staleness.png` + `banner-shot-result.json`）。cell 日期与 ACTIVE 快照 businessTime 一致性 PASS（双独立载荷同点）。

## §2 测试与回归基线

| 套件 | 结果 |
|---|---|
| web（node --test，`web/`） | **329/329**（02.5 前基线 324 + sourceWizard/接线锚点，02.6 +5 staleness；含 permissionReconcile 判据 A/C0/C1/B/D、boundary、sourceWizard 14 测、overviewStaleness 5 测） |
| `ControllerPermissionCoverageTest`（冻结表） | **5/5**（+4 行 RUNTIME_MANAGE 期望；scannerSelfCheck/runtimeProfilesFullyGuarded 全绿） |
| Playwright E2E（02.5 向导） | **12/12**（`026-wizard-e2e/`） |
| 独立性证据脚本（02.6） | **9/9**（`027-independence/`） |
| 横幅 DOM 证据（02.6） | **5/5**（`027-independence/`） |
| analytics-server（最近全量，D-029 证据 RunId `dev003c_20260920_020324_00d2c3`） | **1043 MATCH**（基线 1039→1043，唯一红 = 既有环境性 `IngestionManifestRuntimePatrolTest`，与本工作包代码无涉） |

## §3 决策登记

D-023（warehousePrefix=`fxsb`）、D-024（夹具补面 8 类全覆盖）、D-025（故障集 5 类互异）、D-026（A-B-A 每腿新文件名 + 检查点跨源重扫语义）、D-029（画像生命周期门按语法分派必备键）、D-030（dry-run=advisory preflight，Loader 为激活权威）、**D-031（02.5 向导面收口：冻结表 +4 行 / 报告回查不接线 + `${id}` 占位符约定 / 预置回填 / 调用前 current 判幂等）**、**D-032（02.6 时效警告：载荷派生 + 壁钟比较 + 角色无关 + 不假报最新）**。D-027/D-028 永久空缺（编号注记见 DECISION_LOG）。

## §4 代码制品（本工作包提交）

- `web/src/utils/sourceWizard.js`、`web/src/views/SourceWizard.vue`、`web/tests/sourceWizard.test.js`（新增，02.5）
- `web/src/api.js`（sources/sourceActivate/mappingDryRun/mappingActivate 封装，`${id}` 约定）、`web/src/router.js`、`web/src/App.vue`（admin 专属路由接线，02.5）
- `analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/ControllerPermissionCoverageTest.java`（冻结表 +4 行，02.5）
- `analytics-server/source-profiles/fixture-shop-b.v2.json`（画像制品，02.1/02.2）
- `web/src/utils/staleness.js`、`web/tests/overviewStaleness.test.js`（新增）、`web/src/views/Overview.vue`（时效横幅，02.6）

## §5 边界与未覆盖范围

- 通用样本上传 / 全可视化字段拖拽映射为后续增强（指导书 §6.3 注明不阻塞本版最小配置表单）。
- 向导预置清单只收 fixture-shop-b 受控集三条（boundary 守卫：分析前端不出现商城字样）；mock-mall-aba 双腿样本仍可经手工 sampleRef 引用（platform sample-root 内真实存在）。
- 02.6 时效提示随壁钟刷新（页面加载时派生一次，无定时轮询）；不做「数据是新的」正向断言。
- 平台运行态收尾：source 1 恢复 current=true（02.4 收口态）；landing `events/` 终态为空（三处归档保留出处，D-026）；push 仅本地（D-001）。
