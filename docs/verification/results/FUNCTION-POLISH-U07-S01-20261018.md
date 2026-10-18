# FUNCTION-POLISH-U07-S01 结果报告：新建 V3.0 答辩演示手册（切片关账）——U07 关账、U 系列全队列云端处置完毕

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U07、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U07-S01.md` 与修复规约 `mall-simulator-notes/U07-S01-FIX.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U07 盘点验收提交 `d59fac8`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U07-S01-REPORT.md`，末行标记 `U07S01-DONE`；修复轮交付 `U07-S01-FIX-REPORT.md`，末行标记 `U07S01FIX-DONE`）；验收由总控亲验，不采信执行方自述数值。
- 关联问题：UP-20261018-53（P1，演示脚本与现行前端路径脱节）与 UP-20261018-54（P2，脚本明文口令回显与停服口径缺失——手册侧处置，脚本本体维持现状，裁决见盘点结果报告 `FUNCTION-POLISH-U07-20261018.md` 裁决登记第 3 项）。

## 交付内容（总控逐行核验）

- `docs/demo/DEMO-MANUAL-V3.0.md`（新建，实测 377 行）：标题与定位（本地单节点 LOCAL、三程序三端口 8091/8090/8092、前端 5173 代理与 8091 内置双模式）、§0 边界先行（限定通过口径、AI 解释 template 回退口径、ACTIVE 快照数据口径）、§1 准备与启动（`start-all.ps1` 及 `-PlatformOnly`/`-MallOnly`/`-GeneratorOnly` 参数、账号仅写角色与用户名、口令一律 `<本地种子口令>`、三项无凭据健康判据）、§2 普通员工路径 9 步与 §3 管理员路径 5 步（每步「操作 / 预期现象 / 异常时」三段，总控实测「预期现象」「异常时」各恰 14 处）、§4 停服口径（先停前端再停后端；PowerShell 以 `Get-CimInstance Win32_Process` 按命令行含 `platform-app`/`mall-simulator`/`synthetic-data-generator` 三个 jar 特征全匹配逐个终止，与 `scripts/start-all.ps1` 三处 JarGlob 逐一对应；严禁按端口杀进程、严禁假定旧 PID；以端口不再监听确认）、§5 应急恢复入口汇总（仅已存在机制）、§6 本地彩排勾选表（员工 9 项 + 管理员 5 项复选框，实测恰 14 项，表头注明云端不代签）、§7 未测范围与诚实边界（UP-48/UP-55/RES-01 单列、RES-02～11 沿用签收处置、增量登记属性）。
- `docs/demo/demo-script.md`（仅 +2 行）：标题后插入一行导航附注指向新手册，其余逐字未动（总控以 git diff 亲验：1 file changed, 2 insertions）。
- 总控回源抽验属实：登录按钮「登 录」文案、SnapshotSelector 提示「本页数据固定使用 …（…）」、Decisions 页面内对话框 `@keyup.esc` 取消与 `@keyup.enter` 确定、Overview 六指标卡集合、平台健康端点 `/api/v1/metrics/health` 与生成器 `/api/v1/scenarios` 判据（与 `start-all.ps1` 一致）、各表列数（Decisions 13 / Pipeline 实例 10 / RuntimeProfiles 7 / Ops 审计 6+7、批次 9）与 U04/U06 各关账登记一致；手册全文经总控以源码种子口令字面集合程序化比对，零命中（口令脱敏属实）；全文无任何具体快照编号、无按端口杀进程指令。

## 首轮计数错误与同轮修复（U07-S01-FIX）

总控首轮验收发现两处计数事实错误（内容本身无误）：① 手册 §2 步骤 2 称 AnalysisContext「完整回显 7 项关键元数据」，但其后枚举实为 8 项，且源码 `AnalysisContext.vue` 的 meta-item 实为 8 个（「生效查询范围」为条件显示）；② 执行方报告称手册 378 行，实测 377 行，§7 行号范围随之多 1。同轮修复轮 `U07-S01-FIX` 仅改该一句（改「8 项」并注条件显示）与原报告三处行数表述，总控亲验修复后手册仍 377 行、其余内容逐字未动，复验通过。

## 总控亲跑收尾回归（不采信执行方自述）

- web 档（RunId `u07s01gate20261018`，日志 `mall-simulator-notes/u07s01-gate-web.log`）：tests=602、pass=602、fail=0，**602 MATCH**，`npm run build` exit=0，**PASS exit=0**。本切片为纯文档改动、无新增测试，计数基线 602 不变，未改 `run-tests.ps1`，未使用 `-AllowCountDrift`。
- 脚本契约：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（BUILD SUCCESS；在线经本地中继 18128，JDK17，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，日志 `mall-simulator-notes/u07s01-contract.log`）。
- default/spark/isolated 按指导书 §6.1 与盘点裁决未重跑：U 系列全部切片零后端 Java/Spark/数据库改动，沿用 U00 基线亲跑证据（default 1563 PASS）与既有关账证据（spark 389、isolated 62）。
- 环境注记：巡检开始时中继 18128 与 3307 隔离 MySQL 未监听（VM 替换后遗症），总控按 TOOLS.md 恢复节拉起并实查（中继 LISTEN、mysqld is alive）后执行门禁；本切片未连库，3307 仅为环境在位确认。

## 未测范围

- 本手册未经本地活动服务实走彩排（UP-20261018-55，用户本地事项）：手册内容为源码级逐行对照结论，§6 勾选表供用户本地逐项实走填写，云端不代签、不预判。
- 真实视觉审阅未做（UP-20261018-48 / RES-06，用户本地事项）；真实 LLM 冒烟未做（RES-01 单列，仅在用户单独提交 be8a7a8 本地实链报告时按 STAGE6 清单逐条核）。
- 未启停服务，未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续与队列状态

- UP-20261018-53 **关账**；UP-20261018-54 手册侧处置完毕（脚本本体维持现状登记）；UP-20261018-56/57 已于盘点轮复核符合。
- **U07 关账**（盘点 + S01 切片全部总控亲验关账）。**U 系列 U00→U07 全队列云端可处置项就此处置完毕**：UP-20261018-01～57 全部有归宿（关账/全项关账、裁决维持现状、复核符合、待本地事项四类）。云端队列余项仅为用户本地事项，不代造证据：UP-48 真实视觉审阅（RES-06）、UP-55 本地演示彩排、RES-01 真实 LLM 冒烟审核（待用户提交本地实链报告）；RES-01～11 一律沿用签收处置，不自动销项。
- 结论口径（指导书 §5 U07 纪律）：本轮增量在所列范围通过（web 602 MATCH exit=0、脚本契约 13/13、两条演示路径手册就绪），增量单独登记；原签收「限定通过」维持不变，不宣称全项目/F-88 完整验收。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
