# 阶段 4：AI 默认快照限定当前 RuntimeProfile

日期：2026-10-07

## 发现与修复

G-03 裁决 D-059 规定，不带 `snapshotId` 的默认读路径只读取当前唯一 ACTIVE RuntimeProfile 的 ACTIVE 快照。复核发现 `AiScopeResolver` 原先使用全局 `status='ACTIVE' ORDER BY id DESC LIMIT 1`，profile 切换后可能将旧 profile 的 ACTIVE 快照用于 AI 问数。

- `AiScopeResolver` 现注入现有 `ActiveRuntimeProfileIdProvider`，默认快照 SQL 必须匹配 `runtime_profile_id` 和 `status='ACTIVE'`。
- 缺少 provider、没有 ACTIVE profile 或 profile 不唯一时，默认解析 fail-closed，不执行全局快照查询。
- 显式 `snapshotId` 路径不经过默认 profile 解析，但仍要求目标快照为 `ACTIVE/ARCHIVED`；未知/未发布仍 fail-closed。
- 未新增 RuntimeProfile 数据库查询实现，复用当前只读 identity provider；无 profile 时没有默认快照回退。

## 验证

JDK 17 离线 Maven 定向命令（包含平台 Spring 装配回归）：

```text
mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=AiSqlSecurityTest,PlatformDataSourcesQueryTimeoutTest,QueryTimeoutOwnerDriftTest,SqlExecutorTimeoutConfigurationTest,AiQueryTimeoutMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`AiQueryTimeoutMappingTest` 3/3、`AiSqlSecurityTest` 38/38、`QueryTimeoutOwnerDriftTest` 2/2、`SqlExecutorTimeoutConfigurationTest` 4/4、`PlatformDataSourcesQueryTimeoutTest` 6/6，合计 **53/53 PASS**，0 failure/error/skipped，七模块 reactor `BUILD SUCCESS`。

新增断言确认默认快照 SQL 带当前 profile ID 参数；无唯一 ACTIVE profile 时查询没有发出。兼容单参数构造器不配置 profile provider，默认解析因此 fail-closed。

## 证据边界

以上是离线单元测试，不连接 MySQL、不部署服务，也未执行真实 RuntimeProfile 切换。它不证明 `metric_read` 账号在真实 schema 对快照 join/查询的授权、真实 ACTIVE profile 数据状态或浏览器端行为。阶段 4 和项目总体验收仍未关闭。未访问 3306。
