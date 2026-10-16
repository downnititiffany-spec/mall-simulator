# BATCH-N31-03 — 普通员工可用性与业务决策复核结果

> 结论：**当前工作树/JAR/隔离 ACTIVE 范围内 PASS_WITH_LIMITATION**。员工主要页面、权限、快照选择及当前版本 AI→DRAFT 路径通过；既有决策审批/评价证据按指导书复用。该结果不等于真实 LLM、N31-02 全批、产品最终验收或 V3.1 发布。
> 执行日：2026-09-29（Asia/Shanghai）。
> 计划：[`BATCH-N31-03-EMPLOYEE-USABILITY-PLAN.md`](../batches/BATCH-N31-03-EMPLOYEE-USABILITY-PLAN.md)。

## 1. 被测版本与运行隔离

- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `feature/v3-development`，HEAD `300c4c9b7bfc25b8a058c62ad89f349bb15121b8`；保留原有 dirty/untracked 工作树，不提交、不推送。
- Vite `web/dist` 构建成功，共 29 个文件；平台 `src/main/resources/static` 与 dist 文件清单/内容匹配。
- Maven 平台打包成功（`mvn -o -f analytics-server/pom.xml -pl platform-app -am clean package -DskipTests`）。被测 JAR 为 `platform-app-0.1.0-SNAPSHOT.jar`，SHA-256 `1179F63DA27BC7CA98B7DDBFD456D9413BB421756234270E1395C2AA69B2D7D9`，83,473,007 bytes；构建时间 2026-09-29 13:42:32 +08:00。当前 8091 实际由该 SHA 的 JAR 服务，`/api/v1/metrics/health` 返回 HTTP 200/OK。stub LLM 运行于 18080。
- 隔离运行沿用 `n3102iso_20260929_095610`；数据库 schema 为 `n3102iso_20260929_095610_analytics_meta` 与 `n3102iso_20260929_095610_analytics_metric`，仅连 MySQL 3307。运行守卫记录 `guard=PASS`、健康检查 PASS、启动日志零 `:3306`；本批没有连接或执行 3306 SQL。
- ACTIVE 快照 `S20260918_2`，业务日期 2026-09-18。按 2026-09-29 计算，页面提示滞后 11 天；这是真实样本业务水位，不是静态页面发布日期。

## 2. 验证结果

### 2.1 当前包、页面和权限

- A8 发布资源从 Vite 重新构建、同步至平台静态资源后打包；Jar 与 dist 的 29 个静态文件匹配。此前“页面停留 9 月 19 日”根因是只运行 Maven 后端打包，没有触发 Vite 构建/资源同步，JAR 因而继续内嵌旧静态文件。本次新包显示源码已有的业务时效横幅；以后发布应使用 `scripts/build-web-and-package.ps1` 或等价的“前端构建→同步→后端打包”顺序，不能只执行后端 `mvn package`。
- `n3103-employee-browser-result.json`：分析员登录、Overview、Sales、Behavior、Products、RFM、AI、Decisions 页面，以及管理员来源向导/刷新均 PASS。主页面 API 返回 2xx；浏览器 `pageErrors=[]`、`consoleErrors=[]`、`requestFailures=[]`。日期范围输入与来源选择存在；页面显示当前 ACTIVE `S20260918_2`。
- analyst 获取 `/api/v1/runtime-profiles` 返回 403；直接访问 `/pipeline`、`/ops`、`/sources/wizard` 均回到 `/overview` 且不展示管理员入口。管理员来源向导 `/api/v1/sources` HTTP 200、深链刷新可用、运行配置 HTTP 200。分析员与管理员退出登录均通过。
- 运营大盘显示样本业务时点 2026-09-18、滞后 11 天提示，未把旧数据说成最新。决策页初始空态“暂无”与空数据库状态一致。

### 2.2 快照选择一致性

- `snapshot-selector-result.json` PASS：页面默认选择 `S20260918_2`，切换到历史 `S20260918_1` 后，Overview、Sales 和 AI 上下文请求均携带该 snapshotId，服务端响应也逐一返回相同 ID；本专项没有提交 AI 问题或写业务数据。

### 2.3 当前 JAR AI→DRAFT 正向路径

- 使用真实浏览器可操作控件登录 analyst，在 AI 页面提交“最新一期的 GMV 和退款率是多少？”；`POST /api/v1/ai/queries` HTTP 200、`code=OK`、`query.status=EXECUTED`，SQL/解释 provider 均明确为 `stub-local`，AI 响应含证据包 ID、证据快照仅 `S20260918_2`，返回 2 条建议。
- 在页面实际点击“转决策草稿”，员工手动选择方向 `UP`，再实际点击“创建草稿”。`POST /api/v1/decisions` HTTP 200，服务端返回 `source=ai`、`status=DRAFT`、decisionNo `DC-20260929141635-32eb`。只读 API 回查显示 `evidencePackageId` 非空、对应 analyst 最近一次 `EXECUTED` AI 历史使用 `S20260918_2`；因草稿按前端锚点规则优先保存 evidencePackageId，`suggestionSnapshotId=null` 是预期形状，不是丢失证据。
- 再次只读打开决策中心，真实页面可见该 DRAFT；回查浏览器 `pageErrors=[]`、`consoleErrors=[]`、`requestFailures=[]`；退出登录 PASS。草稿保留在本次隔离 schema 中，不提交审批、不执行商业动作、不删除。
- 本测试实际写入仅为隔离库中的 AI 查询/审计/历史记录与上述一条 DRAFT；未改 ACTIVE、指标数值、ADS、订单或数仓数据。

## 3. 按 N31-03 指导书复用的既有证据

指导书允许复用 G31-03 已有决策四结果和审批证据，只对最新 ACTIVE 与最新 JAR 做必要回归。本批已经用当前 JAR/当前 ACTIVE 补验 AI 解释→草稿可见；未重放整个审批状态机。

- `docs/PROJECT_STATUS.md` §“2026-09-25 交接项3”记录了隔离栈的真实 HTTP/DB 状态、RBAC、非法转移拒绝、审计与双层审批 fail-closed；批准当前单日快照被拒是设计预期。
- 同节明确历史决策已有 `EFFECTIVE/PARTIAL/INEFFECTIVE` 评价记录，并包含 `INSUFFICIENT_DATA` 的证据。它们属于已登记的历史决策，不是本次新运行结果；不能将它们说成当前快照的新评价。
- `docs/PROJECT_STATUS.md` §“2026-09-25 G31-03 浏览器腿”记录了旧版浏览器 AI→DRAFT 和决策页显示证据。该历史 JAR/快照与本次不同，因此只作既有工作流证据，不代替本次当前包回归。

## 4. 测试器偏差与定性

- AI→DRAFT 首个专用 harness 先后出现同步 `threading.Event.wait()` 阻塞 Playwright 回调、对隐藏原生 `<option>` 使用可见性等待、以及“决策中心”侧栏/草稿内页两个同名链接导致 strict-mode 选择歧义。这些是新测试器实现缺陷。最终 `current-draft-readback.json` 以独立只读 API 查询与导航作用域真实点击确认产品路径通过；原先失败 JSON 均留原貌。
- 创建草稿前快速从 Overview 导航至 AI 曾产生一条主动路由切换期间被取消的 Overview GET（`net::ERR_ABORTED`），而其余页面响应正常。单独的主页面验收和最后的决策中心只读回查均记录零请求失败；不把被导航主动取消的探针请求扩写成产品网络故障。
- 本轮没有修改业务源码、测试源码或发布脚本；新增浏览器验收脚本和原始运行产物全部位于 ignored `target/v25-it/n3103-a8-refresh-20260929-133200/`。

## 5. 最终状态与未完成范围

- **N31-03 本轮可执行退出项**：PASS_WITH_LIMITATION（员工常用页面/访问权限/来源向导/快照 pinning/当前 JAR AI→DRAFT 已实测；现成审批及评价证据已按计划复用）。
- **限制**：真实 LLM 仍按 G31-06/D-039 BLOCKED；本次 AI 只用本机 stub。当前隔离样本最大业务日仍是 2026-09-18，没有运行新采集/生成订单/发布新快照；不能用此结果声称业务数据已更新。
- N31-02 整体仍未完成：腿③分类/地区契约等待 C3 总控裁决；不得自行建 ADS 表、修改指标口径或调整设计。N31-01 §2.3/§2.4 与 N31-05 的签收/正式版本发布也仍受总控治理门控。
- 证据根：`target/v25-it/n3103-a8-refresh-20260929-133200/`。计划/结果和动态状态本地更新；本轮不 commit、不 push。

## 6. 追加勘误：N31-03 代码与测试变更归属（2026-09-29）

N31-01 状态审计发现 §4 的“本轮没有修改业务源码、测试源码或发布脚本”与当前工作树不符。相对于计划固定的 HEAD 300c4c9b7bfc25b8a058c62ad89f349bb15121b8，N31-03 浏览器验收和结果登记前已有 30 个新增/修改的产品、测试、E2E 与前端/平台打包文件，内容与本批来源选项/权限、快照选择、员工分析页、AI→DRAFT 和页面发布包路径相符；具体路径见 docs/verification/results/BATCH-N31-01-STATE-CALIBRATION-AUDIT-20260929.md §3.1。

因此本批不是“源码零改动”的验收批。原有 JAR SHA、浏览器/数据库证据及 PASS_WITH_LIMITATION 仍按原证据边界读取；本勘误更正变更归属，不把测试范围扩成 N31-02 腿③或真实 LLM 验收。原始结果文件已在 .bak-20260929-n3101-audit 备份。
