# FUNCTION-POLISH-U01-S03 结果报告：前端 401 拦截器模拟断言补齐（切片关账，U01 全项处置完毕）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U01、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U01-S03.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U01-S02 关账提交 `6616849`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U01-S03-REPORT.md`，末行标记 `U01S03-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-08 余项（P2/测试空白）——前端 Axios 响应拦截器 `redirectToLogin` 的非 /login 路径触发跳转、/login 路径不循环重定向、清理登录态未建立模拟断言。本切片关账后 UP-20261018-08 全项关账，U01 全项处置完毕。

## 交付内容（总控逐行核 diff）

- `web/src/api.js`：最小可测性重构，行为不变——`redirectToLogin` 改为具名导出；原匿名响应拦截器成功/失败回调提取为具名导出 `handleApiResponse` / `handleApiError`，函数体逐字一致；注册行改为 `client.interceptors.response.use(handleApiResponse, handleApiError)`。请求拦截器、TOKEN_KEY/USER_KEY、clearAuth、默认导出对象及全部 API 方法逐字未动，未新增其他导出。
- `web/tests/apiUnauthorized.test.js`（新增）：15 个测试块，以内存桩替换 `globalThis.localStorage` 与 `globalThis.window` 做动态模拟断言（非纯源码正则），覆盖规约场景 a–j：OK/ACCEPTED 信封返回 data、UNAUTHORIZED 信封在非 /login 路径清键并跳 /login、在 /login 路径清键但不改 href（防循环，含带 query 变体）、其他 code 透传 message 且不清键不跳转、无 message 兜底文案（未授权/请求失败）、失败回调 401 清键跳转、200+UNAUTHORIZED body 口径触发、500 与无 response 网络错误透传原 err 且不清键、`redirectToLogin` 直测，及拦截器注册与默认导出接线守护。
- 与规约偏差：零偏差。未改拦截器任何判定条件、消息文案、存储键名与跳转目标；未改后端、角色权限、接口契约、`router.js`/`routeAccess.js`/`App.vue` 与既有测试文件；未新增依赖；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u01s03acc20261018`，日志 `/tmp/v25tests-u01s03acc20261018`）：tests=478、pass=478、fail=0，`npm run build` exit=0；脚本因计数基线仍为 463 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 463 → 478，增量 +15 构成全部为本切片新增 `web/tests/apiUnauthorized.test.js` 的 15 个测试块（场景 a–j 共 14 块含补充兜底/防循环变体 + 接线守护 1 块），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 478`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u01s03gate20261018`，日志 `/tmp/v25tests-u01s03gate20261018`）：tests=478 MATCH、pass=478、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，另加 `-Dmaven.legacyLocalRepo=true` 绕本机 _remote.repositories 口径）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器 401 会话失效走查（结论为源码级核对 + web 档动态模拟断言证据）；真实认证浏览器走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- U01 全项处置完毕（UP-20261018-01～08 均已关账或复核符合/裁决登记）。转 U02（指标含义与上下文）盘点：立 U02-INV 只读盘点规约派发 agy Flash。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
