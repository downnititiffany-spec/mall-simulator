# FUNCTION-POLISH-U01-S02 结果报告：无权访问回退提示闭环（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U01、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U01-S02.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U01-S01 关账提交 `3214f9c`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U01-S02-REPORT.md`，末行标记 `U01S02-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-02（P2）后半——已登录用户无权访问受限页时被守卫静默回退 /overview、无任何提示。本切片关账后 UP-20261018-02 全项关账。

## 交付内容（总控逐行核 diff）

- `web/src/utils/routeAccess.js`：新增并导出纯函数 `resolveDeniedPath(raw)`——仅当 raw 为字符串、精确等于 12 个功能路由路径之一（复用既有 `ALLOWED_REDIRECT_PATHS`）、且不是 `/overview` 时原样返回；非字符串、数组、`/`、`/overview`、`/login`、未知路径、含 `?`/`#` 的字符串一律返回 `null`。函数注释明示仅用于无权回退提示的展示校验，不做权限判定（判定仍归 `canAccessRoute`）。既有 `canAccessRoute` 与 `resolveLoginRedirect` 逐字未动。
- `web/src/router.js`：仅无权回退分支的 token 子分支由 `'/overview'` 改为对象形式 `next(token ? { path: '/overview', query: { denied: to.path } } : '/login')`——仅传目标 `to.path`，不传 `fullPath`，避免把任意 query 带入提示面；无 token 时仍为 `'/login'` 字面量；未登录分支（U01-S01 的 redirect 传参）与已登录访问 /login 分支逐字未动。
- `web/src/App.vue`：在 `<main class="page-body">` 内、`<SnapshotSelector>` 之前新增提示横幅（`v-if="deniedPath"`、`role="alert"`、复用 `alert alert-warning` 风格 + 最小 scoped 样式），文案「无权访问页面 {{ deniedPath }}，已返回运营大盘。」；`deniedPath` 为 `computed(() => resolveDeniedPath(route.query.denied))`；关闭按钮经 `router.replace` 仅移除 query 中的 denied 参数、保留其余 query。既有导航、菜单过滤、用户态 watch、SnapshotSelector 挂载条件均未动。
- `web/tests/accessDenied.test.js`（新增）：6 个测试块，覆盖合法功能路由回显、非法类型与异常入参、根/大盘/登录/未知路径、相对路径与带 query/hash 字符串，及 router.js 与 App.vue 两处接线源码守护（含严禁 `denied: to.fullPath` 断言）。
- 与规约偏差：零偏差。未改后端、未改角色权限与 `canAccessRoute` 判定、未改接口契约、未改 `api.js`、未改 U01-S01 的 redirect 逻辑与测试、未新增依赖。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u01s02acc20261018b`，日志 `/tmp/v25tests-u01s02acc20261018b`）：tests=463、pass=463、fail=0，`npm run build` exit=0；脚本因计数基线仍为 457 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。（按巡检红线，统一脚本日志用新目录：本轮首试 RunId `u01s02acc20261018` 已被一轮误以缺省档启动的运行占用，故验收轮改用 `…b` 后缀新目录，未覆盖旧结果。）
- **计数漂移书面登记**：web 基线 457 → 463，增量 +6 构成全部为本切片新增 `web/tests/accessDenied.test.js` 的 6 个测试块（resolveDeniedPath 判定 4 块 + 接线守护 2 块），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 463`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u01s02gate20261018`，日志 `/tmp/v25tests-u01s02gate20261018`）：tests=463 MATCH、pass=463、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑（附带核查见下）。

## 附带核查：缺省档误跑失败的环境归因

- 本轮验收中曾误以缺省档（未传 `-Suite web`）启动一轮 default（RunId `u01s02acc20261018`）：analytics 1330 收集、F=26 E=10，generator F=18 E=35，mall 26 MATCH。
- 失败全部集中于回环 HTTP 测试类（`OpenAiCompatLlmProviderTest` 45 例、`ReferenceMallHttpAdapterTest` 11 例等），错误文本为沙箱 other_tcp 策略拒绝（`ProtocolException: Invalid status line: "muse: Other TCP connections is turned off…"`）——即 TOOLS.md 既载的 JVM IPv6-mapped 回环被拦截问题。本轮 shell 未设 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`（U00 基线日志第 17 行起有此项，为基线通过的必要环境口径）。
- 总控补齐该环境变量后定向亲跑两类：`OpenAiCompatLlmProviderTest` **45/45 PASS**、`ReferenceMallHttpAdapterTest` **11/11 PASS**。结论：环境口径缺失，非代码回归；U00 default 基线 1563 继续有效。后续亲跑 Java 测试须先导出该变量，巡检规约已同步此口径。

## 未测范围

- 未做真实浏览器无权访问走查（结论为源码级核对 + web 档自动化测试证据）；真实认证浏览器走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- U01 剩余：UP-20261018-08 余项（前端 401 拦截器模拟断言空白）立切片 U01-S03（测试补齐 + api.js 最小可测性导出，行为不变）派发；S03 关账后 U01 全项处置完毕，转 U02 盘点。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
