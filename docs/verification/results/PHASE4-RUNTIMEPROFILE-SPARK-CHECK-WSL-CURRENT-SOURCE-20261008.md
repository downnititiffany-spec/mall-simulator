# 阶段 4 RuntimeProfile Spark 当前源码 WSL 方法级检查（2026-10-08）

## 目的与范围

补充 Windows CLI 方法级检查之后的 WSL 当前源码证据。测试通过反射直接调用当前工作树 `RuntimeProfileServiceImpl.checkSpark()`，对 `SINGLE_NODE` 候选档案运行配置的 Spark CLI `--version`，并确认探测返回成功且详情不暴露可执行路径。

## 执行与结果

- WSL 使用 JDK 17.0.12，Spark CLI：`/opt/spark-3.5.1-bin-hadoop3/bin/spark-submit`。
- 当前类加载来源核实为工作树 `analytics-server/connection-ingestion/target/classes/`。
- 经已有打包应用的 `PropertiesLauncher` 加载当前类和当前模块依赖运行方法级检查，结果：`RuntimeProfile checkSpark passed; detail=探测通过`。
- Spark 3.5.1 的 `--version` 已在 Windows JDK17/Maven 3.9.14 的离线七模块 reactor 中由显式 `RuntimeProfileSparkHealthCheckIT` 覆盖；IT 1/1，`RuntimeProfileProbeRedactionTest` 7/7，`RuntimeProfileHiveProbeRedactionTest` 3/3，合计 11/11 PASS，0 failure/error/skipped，BUILD SUCCESS。详见 `PHASE4-RUNTIMEPROFILE-SPARK-CHECK-CURRENT-SOURCE-20261008.md`。

## 结论边界

本次 WSL 结果证明当前源码的 `checkSpark()` 方法能运行该 WSL Spark CLI 并返回脱敏成功结果。它不证明数据库持久化 RuntimeProfile 的路径、Spring HTTP/API 调用、真实认证、档案激活或切换，也不提交 Spark 作业或证明业务计算。没有连接数据库，没有访问 MySQL 3306，没有启动/停止服务或改动持久数据。阶段 4 和最终验收保持未关闭。
