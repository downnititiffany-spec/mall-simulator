# FUNCTION-POLISH-U01 盘点结果报告：U01 登录、角色与导航（只读盘点验收）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U01、§4 问题分级、§6.1 分层验证、§7 证据与动态文档
- 代码版本：开发线 `codex/cloud-v3-development @ feed74f`（盘点期间仓库零代码改动，工作树仅既有 untracked `sample-data/`，不属本包）
- 执行：盘点由 Antigravity（Flash）按规约 `mall-simulator-notes/U01-INV.md` 只读执行，交付 `mall-simulator-notes/U01-INVENTORY-REPORT.md`（末行标记 `U01INV-DONE`）；验收由总控亲验，不采信执行方自述数值。
- 基线引用：U00 总控亲跑 default 1563 PASS（RunId `u00base20261018`）、web 450 PASS（RunId `u00web20261018`）；本包无代码改动，未重跑门禁，基线继续有效。

## 总控亲验记录（抽验锚点）

逐项实查现行源码，与盘点报告对照：

- `web/src/views/Login.vue`：语义化 form、`loading` 重入锁与输入/按钮禁用、失败文案回显且不清空输入——属实；成功后硬编码 `router.push('/')`、未读 `route.query.redirect`——属实（UP-20261018-01，P1 成立）。
- `web/src/router.js`：未登录守卫 `next('/login')` 未携带 `redirect` 参数——属实（UP-20261018-02 前半，P2）；无权时静默 `next('/overview')` 无提示——属实（UP-20261018-02 后半，P2）。
- `web/src/router.js` 与 `web/src/App.vue` 菜单过滤同源调用 `canAccessRoute`（`web/src/utils/routeAccess.js`）——属实，地址直达与菜单可见性同口径（复核符合）。
- `web/src/api.js`：401/`UNAUTHORIZED` 触发 `clearAuth()` + 非 `/login` 路径硬跳转 `/login`、登录页自身不循环——属实（复核符合）；注销链路清除 token/user 并重置快照选择——属实（复核符合）。
- 后端 `RolePermissions` 矩阵：`pipeline:run`、`ops:log:view` 为 admin/data_dev/operator，`runtime:manage` 为 admin/data_dev——与报告差异清单 3 项一致；前端 `/pipeline`、`/ops`、`/sources/wizard` 为 admin-only、`/runtime-profiles` 为 admin+data_dev，与指导书 §5 U01 第 3 条现行角色契约一致。

## 勘误（不影响分级结论）

1. 报告 §7 将第四角色记为 `viewer`，现行角色名为 `analyst`（`RolePermissions.ROLE_ANALYST`、`web/src/App.vue` ROLE_NAMES）；普通分析页对全部已登录角色放行的结论不变。
2. 报告 §1.2 登录调用写作对象参数形式，现行签名为 `api.login(username.value, password.value)` 位置参数；§1.3 将 token 落盘归于 Login.vue，现行落盘在 `api.js` 的 `login` 方法内。证据锚点文件无误，仅表述归属需按此勘误理解。

## 问题清单处置

| 编号 | 分级 | 处置 |
|---|---|---|
| UP-20261018-01 | P1 | 成立。立首个实施切片 U01-S01（见下） |
| UP-20261018-02 | P2 | 成立。redirect 传参部分并入 U01-S01；无权访问无提示部分登记为 U01 后续 P2 切片候选，S01 关账后排队 |
| UP-20261018-03～06 | 复核符合 | 采信，不改代码 |
| UP-20261018-07 | §3.2 待裁决 | 见下裁决登记 |
| UP-20261018-08 | P2/测试空白 | redirect 与守卫动态测试随 U01-S01 补齐；401 拦截器模拟断言登记为后续候选，不在 S01 强行扩围 |

## §3.2 裁决登记（UP-20261018-07）

3 项前后端权限宽窄差异（pipeline/ops 对 operator、data_dev 较宽，sources 激活对 data_dev 较宽）属指导书 §3.2「新增角色权限/扩大数据访问范围」范畴。总控处置：**维持现行角色契约**——前端 `/pipeline`、`/ops`、`/sources/wizard` 保持 admin-only、`/runtime-profiles` 保持 admin+data_dev，与指导书 §5 U01 第 3 条明文一致；本轮不扩大任何页面访问，不改后端矩阵。此为维持现状登记，不构成新授权；若用户日后希望放宽，按 §3.2 另行提交影响与方案裁决。U01-S01 不依赖该事项，继续推进。

## 首个实施切片

- `U01-S01` 登录目标页重定向与安全校验闭环：守卫拦截时携带 `redirect=to.fullPath`；登录成功后经纯函数校验（仅站内已知路由相对路径，拒绝 `//`、反斜杠、协议头、`/login` 自指等）后回跳，非法或缺失回落 `/`；新增 `web/tests/loginRedirect.test.js` 定向测试。边界：不改后端、不改角色权限、不改接口契约。规约见 `mall-simulator-notes/U01-S01.md`，验收由总控亲跑 web 档后另行登记。

## 未测范围

- 本包为只读盘点：未做真实浏览器四角色走查（路由/角色结论均为源码级核对 + U00 web 450 既有测试证据）；U01 验证标准中的真实认证浏览器一项留待 U01-S01 实施后按受影响路径登记，或并入 U07 演示走查。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
