# 阶段2/4 采集与源画像异常信息脱敏（2026-10-10）

## 修复

- `SourceProfileValidator` 解析源画像 JSON 失败时，日志不再输出 Jackson 原始异常消息（可能包含配置片段）；仅保留受控仓库相对路径和异常类型。
- `IngestionService` 单文件摄取/ Landing 目录扫描失败日志不再输出异常正文；单文件仍保留相对 `inputKey` 用于定位。
- 运维状态 API 的 `landingError` 不再直接回显 Landing URI 解析/初始化异常文本，改为稳定提示；服务端日志保留 profile id 和异常类型。无存储时仍不回退到默认目录。

## 验证与边界

- `mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am -DskipTests compile`：platform-common、connection-ingestion 均 `BUILD SUCCESS`；connection-ingestion 实际重编 95 个源码文件。
- 未运行测试、真实 API/浏览器/数据库或部署；未连接 MySQL 3306。当前仅为源码与离线编译证据，阶段2/4/总验收开放。
- 动态文档编辑前备份后缀：`bak-20261010-ingestion-error-log-redaction`。
