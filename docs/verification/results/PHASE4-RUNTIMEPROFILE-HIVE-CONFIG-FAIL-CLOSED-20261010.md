# 阶段4 RuntimeProfile Hive 配置缺失门禁回归（2026-10-10）

## 变更

扩展 `RuntimeProfileHiveProbeRedactionTest`，补齐两种缺失 JDBC URL 的适用性契约：

- `SINGLE_NODE` 缺少 `hive_jdbc_url`：Hive 检查仍为 applicable=true、passed=false，返回稳定 `UNKNOWN: 未配置 hive_jdbc_url`；不解析凭据引用。
- `LOCAL` 缺少 `hive_jdbc_url`：Hive 检查标记为不适用且未通过，不按 UNKNOWN 错误阻断；不解析凭据引用。

生产代码未改动；此变更把现有激活门语义固定为回归断言。

## 验证

Windows Maven 3.9.14 / JDK 17 离线 reactor：

```powershell
mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am `
  '-Dtest=RuntimeProfileHiveProbeRedactionTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`RuntimeProfileHiveProbeRedactionTest` **5/5 PASS**，0 failure/error/skipped；`analytics-server`、`platform-common`、`connection-ingestion` 三模块 reactor `BUILD SUCCESS`。首轮 testCompile 因新增测试缺少 `verifyNoInteractions` 静态导入失败，补齐导入后同一命令通过。

## 边界

仅离线方法级用例，不含 RuntimeProfile API、认证、数据库-backed profile、并发数据库激活或真实档案切换；不触碰服务、数据库或 3306。阶段4/总体验收保持开放。
