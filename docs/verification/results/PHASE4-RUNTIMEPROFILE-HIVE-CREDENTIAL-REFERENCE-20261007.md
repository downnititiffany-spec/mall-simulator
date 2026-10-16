# RuntimeProfile Hive JDBC 凭据引用修复（离线回归）

## 发现与实现

RuntimeProfile 的 HiveServer2 探测此前把 `credentialRef` 指向的环境变量内容作为 JDBC 用户名传递，同时密码固定为空。这既不能按凭据引用提供密码，也可能把秘密值错误地送入用户名字段。

现将探测用户名固定为 `hive`，并通过 `CredentialService.resolve(credentialRef)` 解析引用值，只将解析出的秘密作为 JDBC 密码。接口结果继续仅返回固定成功状态或异常类型，不返回 JDBC URL、凭据引用或底层驱动消息。`credentialRef` 未配置时仍按现有 `CredentialService` 默认解析策略处理。

## 验证

- JDK 17 离线定向命令：

  ```powershell
  mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am '-Dtest=RuntimeProfileHiveProbeRedactionTest,RuntimeProfileProbeRedactionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

- `RuntimeProfileHiveProbeRedactionTest` 2/2、`RuntimeProfileProbeRedactionTest` 2/2，合计 **4/4 PASS**；0 failure/error/skipped，三模块 Maven reactor BUILD SUCCESS。
- 测试替身检查 JDBC 用户为 `hive`、密码来自 credential reference，且实际执行 `SELECT 1`；错误响应不泄漏 JDBC URL、用户名、密码或驱动异常正文。
- 随后执行 `mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am test`：`connection-ingestion` **390 项**（0 failure、0 error、2 skipped），上游 `platform-common` **118/118 PASS**，三模块 reactor BUILD SUCCESS。
- 曾直接对 `connection-ingestion` 单模块运行，因本地 Maven 仓库缺少 `platform-common` test-jar 依赖而失败；按 reactor 依赖关系改用 `-am` 后 fresh 运行通过。该首次依赖解析失败不属于测试用例失败。

## 证据边界

本项是 fake JDBC / Mockito 离线回归。未连接 HiveServer2、未读取当前真实 RuntimeProfile、未部署新版平台；既有 `PHASE4-RUNTIMEPROFILE-HIVESERVER2-20261006.md` 的真实方法级探测证据仍只适用于其原运行环境与版本。阶段 4 整体仍部分完成。未连接 MySQL，3306 零接触。

编辑前备份：

- `docs/PROJECT_STATUS.md.bak-20261007-hive-credential-reference`
- `docs/PROJECT_COMPLETION_CHECKLIST.md.bak-20261007-hive-credential-reference`
- `docs/verification/CURRENT_BATCH.md.bak-20261007-hive-credential-reference`
