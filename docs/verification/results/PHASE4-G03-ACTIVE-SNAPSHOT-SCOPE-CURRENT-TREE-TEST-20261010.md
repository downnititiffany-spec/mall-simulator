# G-03 当前工作树快照范围定向回归（2026-10-10）

## 范围

按总控裁决 D-059 方案 A 复核当前源码：默认指标读取和快照列表限定唯一 ACTIVE RuntimeProfile；跨 profile 历史快照只能由调用方显式提供 snapshotId，未知/未发布快照不回退；指标与分析入口由 `dashboard:view` 保护。源代码检查确认 `MetricController.overview`、`snapshots`、`snapshots/page` 均有该权限声明；服务默认查询经 active-profile provider，快照列表分页按其 profile ID 限定；显式快照查询精确按 ID 查已发布状态。新增 HTTP 合约用例直接验证管理员会话下显式跨 profile snapshotId 传递给 MetricStore，匿名请求在服务调用前返回 401。

## 验证

命令：

`mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=ActiveRuntimeProfileIdProviderImplTest,MySqlMetricStoreActiveProfileTest,MetricAdsReaderActiveProfileTest,MetricControllerPaginationHttpTest,RuntimeProfileControllerHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

七模块 Maven reactor `BUILD SUCCESS`。本次选定 19 项全部通过：ACTIVE provider 4、MetricStore profile scope 5、ADS reader 3、MetricController HTTP 4、RuntimeProfileController HTTP 3；0 failure、0 error、0 skipped。平台模块在本轮重新编译 28 个生产源码和 58 个测试源码。

## 浏览器验证

使用 headless Chromium / Playwright，以全新浏览器上下文直接访问 `http://127.0.0.1:5173/runtime-profiles` 并等待 `networkidle`：最终 URL 为 `/login`，登录页密码输入框可见，RuntimeProfile 标题不存在；未发出后端 `/api/` 请求，页面异常 0。该结果证明当前前端未登录路由守卫，不证明认证后角色授权、profile CRUD/切换或管理员操作。
## 证据边界

本轮为 JDK 17 离线 Mockito/JUnit/MockMvc 定向回归；不连接 MySQL，不验证实际数据、Spring Security 全栈配置、管理员浏览器会话或真实 RuntimeProfile 切换。仅支持 G-03 方案 A 的当前源码和测试契约，不关闭阶段4或总体验收。当前隔离栈保持运行；3306 未访问。详见决策 `docs/decisions/DECISION_LOG.md` D-059。