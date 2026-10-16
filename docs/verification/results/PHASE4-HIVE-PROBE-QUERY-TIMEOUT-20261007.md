# 阶段 4 RuntimeProfile Hive 探测查询时限

日期：2026-10-07

## 实现

`RuntimeProfileServiceImpl.checkHive()` 在执行 HiveServer2 `SELECT 1` 前调用 `Statement.setQueryTimeout(QueryTimeoutPolicy.readTimeoutSeconds(...))`，读取统一配置 `platform.query.read-timeout-seconds`。空、非法、非正和越界输入继续按 `QueryTimeoutPolicy` 回退 30 秒默认值。探测响应仍只暴露固定成功信息或异常类型。

该 JDBC 语句超时从连接建立后生效；它不约束 `DriverManager.getConnection()` 的建连阶段，不应表述为完整的连接与请求双时限。

## 验证边界

- 未运行测试。
- JDK 17 Maven `-DskipTests compile` 尝试在构建项目源码前失败：WSL 离线仓库缺少 `org.springframework.boot:spring-boot-starter-parent:3.2.5`，目录中只有 `.lastUpdated` 标记。未下载依赖。
- Java 代码编译、fake JDBC 查询超时参数和真实 HiveServer2 探测均未验证。
- 阶段 4 与最终验收保持未完成。
