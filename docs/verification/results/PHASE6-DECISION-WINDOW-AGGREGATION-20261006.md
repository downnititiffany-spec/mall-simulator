# 阶段 6 决策效果完整窗口聚合定向验证

## 结论

当前工作树的决策效果逻辑按完整连续业务日计算基线与实际窗口；适用的可加和指标逐日求和，并校验来源、运行环境、指标版本、日期覆盖和快照血缘。缺日、重复日、越界日、混源/混口径和未登记公式均 fail-closed。此次定向 Java 回归 **35/35 PASS**。

## 执行与结果

- 环境：Windows JDK 17.0.12、Maven 3.9.14；离线 Maven `-o`。
- 命令：

  ```powershell
  mvn -o -f analytics-server/pom.xml -pl ai-decision,metric-analysis -am '-Dtest=DecisionWindowAggregatorTest,DecisionServiceTest,MySqlMetricStoreWindowQueryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```

- 结果：`DecisionWindowAggregatorTest` 5/5、`DecisionServiceTest` 28/28、`MySqlMetricStoreWindowQueryTest` 2/2；总计 **35/35**，0 failure、0 error、0 skipped，五模块 reactor `BUILD SUCCESS`。
- 相关 Surefire 报告：
  - `analytics-server/ai-decision/target/surefire-reports/com.graduation.analytics.decision.DecisionWindowAggregatorTest.txt`
  - `analytics-server/ai-decision/target/surefire-reports/com.graduation.analytics.decision.DecisionServiceTest.txt`
  - `analytics-server/metric-analysis/target/surefire-reports/com.graduation.analytics.metric.MySqlMetricStoreWindowQueryTest.txt`

## 范围限制

这是当前源码的单元/Mock JDBC 定向证据；没有连接 MySQL、平台服务或真实 RuntimeProfile，没有创建真实决策/快照，也没有验证员工页面、审批/执行操作或外部模型建议血缘。它证明窗口算法和 MySqlMetricStore 查询映射测试通过，不等于真实数据库的决策效果生命周期验收，也不关闭阶段 6 或阶段 7。

全程未访问 3306，未启动/停止服务，未修改数据库。工作树既有改动均保留。
