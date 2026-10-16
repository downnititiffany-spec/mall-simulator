# 阶段 4 G-03：缺少 RuntimeProfile 解析器时默认读取 fail-closed

日期：2026-10-07  
范围：`MySqlMetricStore` 默认 ACTIVE 快照查询、默认快照列表/分页，以及 `MetricAdsReader` 默认 ACTIVE ADS 快照查询。

## 修正

依 D-059 方案 A，默认数据读取和快照列表必须限定唯一 ACTIVE RuntimeProfile。此前兼容构造函数允许 provider 缺失时按全局 `status=ACTIVE` 读取或跨 profile 列出快照，构成未限定 profile 的旁路。现在 provider 缺失或没有唯一 ACTIVE profile 时，默认读返回空、快照列表为空且总数为 0，不执行 JDBC 查询。生产 Spring 构造仍注入 provider；显式 profile 查询与按明确 snapshotId 的读取路径保留。

已新增缺失 provider 的 fail-closed 回归，并调整依赖默认 ACTIVE 的测试/隔离 IT 显式提供 profile ID；金标 IT 对只使用显式 snapshotId 的夹具明确使用空 provider。

## 验证

- JDK 17 离线 Maven `test-compile`：`analytics-server` reactor 成功，14 个 main source 与 28 个 test source 编译；含相关 MySQL IT 源码编译，不代表执行 IT。
- 定向 Maven 测试实际执行 **64/64 PASS**，0 failure/error/skipped，`BUILD SUCCESS`：`AnalysisServiceTest` 42、`MetricAdsDaoTest` 11、`MetricAdsReaderActiveProfileTest` 3、`MySqlMetricStoreActiveProfileTest` 5、`MySqlMetricStorePaginationTest` 3。
- `git diff --check` 通过；存在工作树既有 LF/CRLF 提示。

## 证据边界

本轮未启动服务、未连接任何数据库、未执行真实登录、未切换真实 RuntimeProfile，也未运行 MySQL IT。证明范围仅为离线编译、mock/unit 行为和代码构造；不证明 JDBC 权限、真实 profile 切换或页面展示。阶段 4 仍部分完成，总体验收不变。
