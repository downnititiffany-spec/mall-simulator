# BATCH-N31-03 — 普通员工可用性与业务决策复核计划

> 状态：执行完成，结果见 [`BATCH-N31-03-EMPLOYEE-USABILITY-RESULT.md`](../results/BATCH-N31-03-EMPLOYEE-USABILITY-RESULT.md)。
> 范围来源：`docs/guidance/项目完整实施指导书 V3.1.md` §5 N31-03；本批只落实该草稿中已由 `PROJECT_STATUS.md` 明确允许并行推进的员工验收，不改指导书范围。
> 执行日：2026-09-29（Asia/Shanghai）。

## 1. 登记与执行边界

- 执行工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `feature/v3-development`，基线 HEAD `300c4c9b7bfc25b8a058c62ad89f349bb15121b8`；工作树原有未提交修改全部保留。
- 运行隔离：沿用 N31-02 run `n3102iso_20260929_095610` 的隔离 MySQL `127.0.0.1:3307` schema；当前 metric ACTIVE 锚为 `S20260918_2`。平台 8091 由本工作树 JAR 提供，stub LLM 仅监听本机 18080。
- 平台产物：`platform-app-0.1.0-SNAPSHOT.jar`，SHA-256 `1179F63DA27BC7CA98B7DDBFD456D9413BB421756234270E1395C2AA69B2D7D9`；打包内 29 个静态文件与本工作树 `web/dist` 清单一致。
- 资源边界：只允许当前隔离 3307 schema 内的登录会话、AI 查询审计/历史记录和一条 DRAFT 决策；不创建或发布快照，不运行采集、Spark、Hive、HDFS、Flume 作业，不清理现有数据。Windows MySQL 3306 不连接、不执行 SQL。
- 诚实性边界：provider 为 `stub-local`，不代表真实大模型；当前快照业务日是 2026-09-18，日期提示按执行日如实显示滞后 11 天。不得把页面/JAR 日期与业务数据日期混为一谈。

## 2. 执行顺序与判据

| 顺序 | 检查项 | 通过判据 |
|---|---|---|
| 1 | 当前发布包与 A8 页面 | Web 构建成功；平台 JAR 内静态文件与 `web/dist` 一致；当前 JAR 实际由 8091 提供；概览显示真实 ACTIVE 与数据时效提示 |
| 2 | 员工主页面与筛选 | analyst 登录；概览/销售/行为/商品/RFM/AI/决策可用正常点击访问；来源、日期控件可见；API 请求成功；快照标识一致；刷新和退出正常 |
| 3 | 快照选择一致性 | ACTIVE 与已发布历史快照均可选择；概览、销售及 AI 上下文请求参数与响应快照一致，不混读 |
| 4 | 角色隔离与管理员入口 | analyst 对运行配置和管理员路由被拒；admin 可访问来源接入向导，深链刷新后仍可用 |
| 5 | 当前 JAR AI→DRAFT | 从 AI 页面真实提交普通问题；响应 `EXECUTED` 并标明 provider/证据快照；完整建议可转草稿；员工手选方向；服务端返回 `source=ai,status=DRAFT`；决策中心能读回该草稿 |
| 6 | 既有决策状态机证据复用 | 依 N31-03 §3.3 不重放完整决策生命周期；引用 G31-03 及 2026-09-25 item3 已有审批、拒绝、状态流转、审计和评价证据，明确它们的运行版本/快照边界 |

浏览器操作使用 Playwright 可操作的标签、按钮、输入框和导航链接；不以直接调用业务 API 代替员工路径。允许只读 API 回读用于校验页面与持久化记录。

## 3. 证据与偏差登记

- 主页面与管理员验收：`target/v25-it/n3103-a8-refresh-20260929-133200/browser/n3103-employee-browser-result.json`。
- 快照选择验收：`target/v25-it/n3103-a8-refresh-20260929-133200/browser/snapshot-selection/snapshot-selector-result.json`。
- 当前 JAR AI→DRAFT 探针与只读回查：同目录 `browser/ai-draft-current/` 下 `n3103-ai-draft-current-result.json`、`current-draft-readback.json` 和截图。
- A8 包体证据：attempt 根下 `platform-package.json`、`web-static-manifest.json`、`evidence/platform-restart-a6.json`。
- A8 页面与员工页面截图留在上述 ignored `target/` 证据目录。
- 本计划在 A8 发布包重建及普通页面回归开始后补记；不伪称计划早于该次已批准的 A8 操作。AI→DRAFT 的首次专用脚本亦经历测试器同步回调阻塞、隐藏 option 可见性误判和严格模式双链接歧义；原始 FAIL 结果保留，修正后用独立只读回查确认草稿实际在决策页可见，不覆盖历史结果、不把 harness 失败归类为产品失败。

## 4. 不在本批范围

- N31-02 腿③分类/地区指标的新增 ADS/表/口径/API；等待 C3 总控裁决。
- 新造数据或审批单日快照；不能用合成未来值制造“有效/无效”评价结果。
- 真实模型外呼及费用；继续由 N31-04 管理，缺授权保持 BLOCKED。
- REMOTE_CLUSTER、多节点容错、论文/答辩撰写、V3.0/V3.1 正文发布、commit/push。
