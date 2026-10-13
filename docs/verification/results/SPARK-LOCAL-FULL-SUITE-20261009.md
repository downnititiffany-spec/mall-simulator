# Spark local[1] 当前工作树全量 ScalaTest（2026-10-09）

- 统一入口：`pwsh -NoProfile -File scripts/run-tests.ps1 -Suite spark -MavenCmd 'D:\apache-maven-3.9.14\bin\mvn.cmd' -JdkSpark 'D:\Develop\JDK1.8' -JdkDefault 'D:\Develop\JAVA17' -MavenRepoLocal 'D:\maven_repository'`。
- RunId：`dev003c_20261009_132902_4f637a`；临时日志目录：`C:\Users\ASUS\AppData\Local\Temp\v25tests-dev003c_20261009_132902_4f637a`。
- Maven 使用 JDK 1.8.0_202；ScalaTest `TestSuite.txt` 本轮新写（2026-10-09 13:31:29，39,627 bytes）。统一脚本确认：332 tests、42 suites、332 succeeded、0 failed/canceled/ignored/pending、0 aborted；332 基线 MATCH；Maven exit 0 / BUILD SUCCESS。
- 首次手工直接 Maven 使用 JDK17，SparkContext 在 Java module access 阶段中止，未进入测试断言；按仓库统一入口规定切换 JDK8 后重新跑完整套件通过。首次错误的 ScalaTest报告随后由本轮 JDK8 fresh 结果覆盖；正确结果严格由统一入口通过新写时间、JDK版本、总数、状态及基线门禁确认。

## 证据边界

仅证明本地 Spark `local[1]` + in-memory catalog 测试域；不证明生产 Hive/Thrift、共享 metastore、远端 Spark 集群、真实 MySQL、浏览器或完整产品验收。统一测试入口裁定排除的 DEV-004 `SourceRegistryMigrationMySqlIT`、`SparkStageExecutorSmokeIT` 和 `AnalysisGoldenMySqlIT` 不包含在本套件计数中。
