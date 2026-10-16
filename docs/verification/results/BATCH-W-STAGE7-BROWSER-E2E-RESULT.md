# BATCH-W Stage7 浏览器 E2E 结果 — PASS

> **结论：PASS（2026-09-19 深夜收口）。** W-1~W-9 全部满足；本批为 V3.1 指导书工作包 **G31-00（收口 BATCH-W）** 的执行与关闭记录。代码基线 `883dcff`（driver preflight 复核 working tree 与其零差异），被测 jar `platform-app-0.1.0-SNAPSHOT.jar` SHA256 `9e79f1b3f29f6b99d9e476e57c611203f3f80f838c1ab03fe9dadcab5649176f`（BATCH-W P1 生产形态构建：前端 dist 28 文件打进 jar，由平台同进程 8091 托管；SHA 与 BATCH-V 的 `942370c7` 不同仅因静态资源纳入，代码态相同）。隔离数据 = 复用 `stage7q1_20260918_152245_*` 四库（3307 环回实例），ACTIVE 快照 `S20260918_12`。push 按规则仅本地提交（先前授权已用尽）。
> 计划：`docs/verification/batches/BATCH-W-STAGE7-BROWSER-E2E-PLAN.md`；权威会话记录 `target/v25-it/stage7w_20260919_223000/session/session-result.json`（attempt-4 + finalSweep）；总清单 `target/v25-it/stage7w_20260919_223000/evidence-summary.json`。

## 1. 尝试史与环境恢复事件

| attempt | 结果 | 记录 | 定性 |
|---|---|---|---|
| 1 | FAIL（完整性门误判，fail-closed 于平台启动前） | `session-result.attempt1-parser-defect.json` | 门修正 C4 |
| 2 | 平台启动 ~5s 静默死亡 exit=-1073741510 (0xC000013A) | `session-result.attempt2-silent-kill.json` | 门修正 C5：外部环境控制台控制事件，stderr 空、无 hs_err、日志干净截断 ⇒ 非平台缺陷 |
| 3 | PASS（环境恢复后全绿） | `session-result.attempt3-pass-with-sweep.json` | 浏览器 E2E P3/P4/P5 在此 attempt 执行；driver-2 首次收口（37248→[14352,31876]） |
| 4 | PASS（最终权威） | `session-result.json` | W-3①/W-6 第五点补验重驱；driver-2 二次收口（38324→[33464,5100]） |

**环境恢复事件（attempt-2→3 之间）**：会话间执行环境回收杀掉了 WSL mysqld（graceful SHUTDOWN 22:40:17+08）与平台 java（`platform.attempt2.log` 干净截止、无 shutdown 记录、无 hs_err ⇒ 外部硬杀）。恢复路径：3307 经 `session/daemon3307.sh`（`mysqld --daemonize` 以 asus 用户，pid 543，完全脱离会话防回收）重启 ⇒ 数据完整性复核（stage7q1 四库在位、`metric_snapshot` 3 行、sys_user=3、ads_hot_product_m=4、ads_user_profile_m=38）⇒ driver-1 attempt-3 重驱全绿。全程零 3306 接触。

**门修正登记**（原文见 `session-result.json` gateCorrections）：
- **C4（attempt-1）**：完整性门解析器 v1 以 `^...$` 锚定整段多行文本（PowerShell `-match` 非 Multiline 语义）且 mysql batch 输出携带 CONCAT 头行 ⇒ CNT/USR 断言假 FAIL，而捕获数据本身显示 S20260918_12 ACTIVE + ADS 2/8/2/4/4/8 + sys_user=3。fail-closed 停在平台启动前，无隔离影响。v2 解析器修正后复跑。
- **C5（attempt-2）**：如上表 0xC000013A 定性。

## 2. W-1/W-9 隔离与边界合规 — PASS

- **F1**：13 变量 `PLATFORM_*` env 逐键读回 13/13 精确一致，URL 全 3307-scoped。
- **F2a**：启动前 fail-closed 自检（任何实际 JDBC 指向 3306 即 exit 12）——每次 attempt 均执行通过。
- **F2b**：启动后日志守卫：startup log 含两个 `jdbc:mysql://127.0.0.1:3307/stage7q1…` URL + landing root，零 `:3306`。
- **F3**：driver-2 `Stop-OwnedProcessTree` 两次收口均 PASS；final sweep 扫描全程 `platform.log` 零 `:3306`、8091 释放。
- **W-9**：收尾 `git status --porcelain` 仅 `?? .zcode/`（治理规定永不提交）；平台环境无 LLM API key（AI 走 fallback 如实展示，不声称真实 Provider 已验）；无 REMOTE_CLUSTER/第二来源/故障注入；阈值/迁移/质量规则零改动；正式库 3306 全程零接触（含只读）。

## 3. W-2/W-3 真实浏览器登录与导航 — PASS（含 C6 偏差登记）

- 错误口令登录 → 401 错误提示且不入系统（`01-login-wrong-password-401.png`）；`admin/admin123` 登录成功、`/auth/me` 返回 admin（driver-1 冒烟 + 浏览器 token 双证）。
- **未登录访问受保护页**：清除 token 后直达 `/overview` ⇒ 重定向 `/login`（token null）；重登录恢复（`browser/p4-w3-redirect-w6-snapshots.json`）。
- **六类必验页 + 三附访页（/products /pipeline /ops）**：侧栏点击 + 直接 URL 双通道 9/9 可达，finalPath 与请求一致，全部 API 2xx（`browser/p4-direct-url-evidence.json`：overview 1×200、sales 1×200、behavior 2×200、rfm 2×200、ai 2×200、decisions 1×200、products 1×200、pipeline 1×200、ops 7×200）。
- **F5 刷新**：reload `/ai` 后历史 3 条 EXECUTED 保留、上下文快照 S20260918_12 在、零异常。**后退**：`/sales` → `tab.back()` → `/overview` 正常渲染（GMV 锚点在，5 API 2xx）。
- **登出**：点击退出登录 → 回 `/login`、`analytics_token` 清除（`11-logout.png`）。
- **C6 偏差登记（V3.1 §12.3 口径）**：①本批全部点击型交互经 DOM `HTMLElement.click()` 驱动——证据保留但不构成"员工鼠标可操作"证明，正常指针/键盘交互补验按 **V31-D04** 延至 G31-03/G31-07；②登录与 AI 输入框均未绑定 Enter 提交（button 无 type、无 keyup handler，实测 enterSubmitted=false）——同时构成可用性改进项线索；③推荐问题 chip 点击即提交（同题两条历史竞态实录），G31-03 决策类 POST 流程须防重复提交；④`window.__stage7Err` 错误收集器在导航后安装会漏 SPA 首屏异常，RESULT 如实标注此局限（各页以 2xx + 特征元素 + 多次到访交叉佐证渲染健康）。

## 4. W-4 渲染健康 — PASS

9/9 页特征元素在（每页 mainText 已存 `recon/p5-page-api-leg.json`）、关键 API 全 2xx（§3 明细）、零未捕获 JS exception（含上述收集器局限说明）。无白屏。

## 5. W-5 数字锚点三源对账 — PASS

对账 JSON：`recon/p5-three-source-reconciliation.json`（outcome PASS，程序化比对）；DB 腿原文：`recon/db-leg-p5-out.txt`；页面腿+API 腿原文：`recon/p5-page-api-leg.json`。

| 锚点 | 页面渲染 | API JSON | 3307 SELECT | 一致 |
|---|---|---|---|---|
| GMV | 15,112.10 | 15112.1 | 15112.10（metric_value / ads_sale_trend_m / ads_operation_overview_m） | ✅ |
| 净销售额 | 11,859.40 | 11859.4 | 11859.40 | ✅ |
| 支付订单数 | 80 | 80 | 80 | ✅ |
| 客单价 | 188.90 | 188.9 | 188.90 | ✅ |
| 退款率 | 20.00% | 0.2 | 0.2000 | ✅ |
| 有效复购率（窗口口径） | 45.71% | 0.4571 | 0.4571（window:2026-09-18..2026-09-18） | ✅ |

补充锚点（同 PASS）：漏斗 **下单 49 → 支付 35（rate 0.7143）**——view/intent=0 为 mock-mall 纯交易源合法空态（与 81d8f93 v2 规则语义一致），49→35 即 order→pay 锚点；RFM 八组合计 **35**（一般挽留12/重要价值5/一般发展5/重要保持5/一般价值2/重要发展2/重要挽留2/一般保持2，页面 share 0.1429=5/35 自洽）；/sales 趋势行 2026-09-18（80/35/15112.10/11859.40/188.90）；PV/UV/DAU/加购/收藏=0 纯交易源空态如实展示。AI 行：证据包 `EV-20260919-0d200c`/`EV-20260919-ffb3ee` pin S20260918_12，SQL 白名单表 ads_sale_trend_m/ads_operation_overview_m。decisions 行：`decision_task` 计数 0 ⇒ 空态渲染。

## 6. W-6 ACTIVE 快照一致 — PASS（五点）

① 各分析页 `AnalysisContext` 快照标识 = S20260918_12（8/9 页 snap=true；/decisions 为无快照空态页，属预期）；② `metric_snapshot` ACTIVE 行唯一（S20260918_12 ACTIVE/active_flag=1；S20260918_11 FAILED；S20260901_4 ARCHIVED）；③ `/metrics/snapshots` 列表 ACTIVE 标识 = S20260918_12；④ AI 证据包 snapshotId = S20260918_12；⑤ 各 API payload `snapshotId` = S20260918_12。

## 7. W-7 空态 — PASS

决策中心空列表渲染「暂无决策」类空态 ×2、导出按钮置灰、统一信封字段缺失逐项如实标注；AI 首次查询前历史空态「暂无历史记录」；AI 空数据区间（2026-01-01）返回空态语义——不伪造区间外数据、声明模型假设（ACTIVE 快照业务日前推 7 天）与 `limitations` 说明、只返回快照内真实行；fallback 限制说明「规则回退模式：模型服务不可用，未调用大模型」如实展示。截图：`09-decisions-empty.png`、`10-ai-initial.png`、`10-ai-empty-range.png`、`10-ai-normal-gmv-refund.png`。

## 8. W-8 证据归档 — PASS

`target/v25-it/stage7w_20260919_223000/`：14 张页面截图（01~11 系列）+ 2 个浏览器 JSON + 3 个对账 JSON/原文 + 4 份会话记录 + 完整性门输出 + 3 个驱动脚本 + daemon3307.sh + 4 份日志 + jar SHA 记录 + 根部 `evidence-summary.json`（总清单）。driver console 全文见批次执行输出（prep.log / platform.log）。

## 9. 本批自主决策记录（V3.1 授权框架下）

- **D31-00a**：push 保持仅本地（V3.1 git 规则：先前 push 授权已用尽，新提交不 push 不 force）。
- **D31-00b**：漏斗 view/intent=0 定性为纯交易源合法空态而非缺陷（与已提交的 81d8f93 `ADS_STAGING_PRESENT` v2「0 行合法空态」语义同源）。
- **D31-00c**：为补齐 W-3①/W-6 第五点证据而重驱 attempt-4（driver-1 preflight 复核 code fresh vs 883dcff + jar SHA pin 后再启，driver-2 二次收口），attempt-3 记录保全为 `session-result.attempt3-pass-with-sweep.json`。
- **D31-00d**：G31-06 真模型正样本方向预登记：待 provider/凭据引用/数据外发许可/费用上限齐备方可执行，缺则登记 BLOCKED，不阻塞其它工作包。
- **D31-00e**：C6 正常指针/键盘交互补验按 V31-D04 排入 G31-03/G31-07，本批不重复消耗。

## 10. V3.1 定位与后续

本批关闭 **G31-00**（00.1~00.6：W 终态核对 ✅、AI 空/正常范围 ✅、P5 三源对账+四舍五入口径=页面/API/DB 各自原生精度逐值相等 ✅、driver-2 清理核实 ✅、C4/C5/C6 分类说明 ✅、下一批次 SHA 按当前分支 HEAD 本地重钉于 G31-01 计划）。后续主序：**G31-01 测试隔离与可重复执行 → G31-02 第二来源 fixture-shop-b → G31-03 有数正样本与决策人工流程 → G31-04 真实故障恢复 → G31-05 WSL 单节点整链 → G31-06 真实 AI（stub/安全矩阵先行）→ G31-07 部署交接与本版验收**。
