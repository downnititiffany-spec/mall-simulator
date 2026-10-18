# FUNCTION-POLISH-U01-S01 结果报告：登录目标页重定向与安全校验闭环（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U01、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U01-S01.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线 `feed74f` + U00/U01 盘点登记提交 `c4a3d02`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U01-S01-REPORT.md`，末行标记 `U01S01-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-01（P1，关账）、UP-20261018-02（redirect 传参部分，关账；无权提示部分转 U01-S02）、UP-20261018-08（redirect 与守卫测试补齐部分，关账；401 拦截器模拟断言仍为后续候选）

## 交付内容（总控逐行核 diff）

- `web/src/utils/routeAccess.js`：新增纯函数 `resolveLoginRedirect(raw)`——非字符串回落 `/`；拒绝 `//` 开头、反斜杠、`:`、空白/控制字符；`?`/`#` 前路径部分按白名单（`/` + 12 个功能路由）校验，`/login` 与未知路径回落 `/`；合法时原样保留 query/hash。函数注释明示回跳后仍受 `canAccessRoute` 二次拦截、本函数不做权限判定。既有 `canAccessRoute` 逐字未动。
- `web/src/router.js`：仅未登录分支由 `next('/login')` 改为 `next({ path: '/login', query: { redirect: to.fullPath } })`；其余守卫分支逐字未动（含 canAccessRoute 回退分支的 `/login` 字面量，经核对该分支在未登录时已被前一分支先行处理，保持现状成立）。
- `web/src/views/Login.vue`：引入 `useRoute` 与 `resolveLoginRedirect`，登录成功后 `router.push(resolveLoginRedirect(route.query.redirect))`；语义化 form、loading 重入锁、`api.login(username.value, password.value)` 位置参数与 finally 释放形态均未动。
- `web/tests/loginRedirect.test.js`（新增）：7 个测试块，覆盖规约必测的合法回跳、异常回落、开放重定向逃逸、反斜杠/空白/控制字符、`/login` 自指、未知路径与相对路径，及 router/Login 两处接线源码守护。
- 与规约偏差：零偏差。未改后端、未改角色权限与接口契约、未改 `api.js`、未新增依赖。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u01s01acc20261018`，日志 `/tmp/v25tests-u01s01acc20261018`）：tests=457、pass=457、fail=0，`npm run build` exit=0；脚本因计数基线仍为 450 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 450 → 457，增量 +7 构成全部为本切片新增 `web/tests/loginRedirect.test.js` 的 7 个测试块（resolveLoginRedirect 5 个判定块 + 接线守护 2 个），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 457`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u01s01gate20261018`，日志 `/tmp/v25tests-u01s01gate20261018`）：tests=457 MATCH、pass=457、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（离线 Maven，JDK17，本地仓 m2repo）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS、web 450→457 见上）继续有效并已增量更新 web 口径。

## 未测范围

- 未做真实浏览器登录深链接回跳走查（结论为源码级核对 + web 档自动化测试证据）；真实认证浏览器走查并入 U07 演示走查或 U01 后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- U01 剩余：UP-20261018-02 后半（无权访问静默回退无提示，P2）立切片 U01-S02 派发；UP-20261018-08 的 401 拦截器模拟断言仍列候选，S02 关账后排队裁定。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
