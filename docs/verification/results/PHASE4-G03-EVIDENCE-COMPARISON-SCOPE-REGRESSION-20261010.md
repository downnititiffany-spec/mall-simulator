# 阶段 4 G-03：证据包比较范围回归

日期：2026-10-10  
关联实现报告：[PHASE4-G03-EVIDENCE-COMPARISON-SCOPE-20261010.md](PHASE4-G03-EVIDENCE-COMPARISON-SCOPE-20261010.md)

## 变更

在 `EvidenceBuilderTest` 增加一条回归用例，向上一日候选列表分别加入 runtime profile、source 和 definition version 不同的已归档快照。断言证据包不上期比较、包含 `WARN_NO_COMPARISON_PERIOD`，且不会为任一跨范围候选加载分析数据。

## 验证

执行：

```powershell
mvn -o -f analytics-server/pom.xml -pl ai-decision -am '-Dtest=EvidenceBuilderTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：EvidenceBuilderTest **8/8 PASS**，0 failure/error/skipped；platform-common、connection-ingestion、metric-analysis、ai-decision Reactor `BUILD SUCCESS`。首次调用因 PowerShell 将未加引号的 `-D` 参数拆分，在 Maven 进入测试前失败；引用参数后重跑成功。

## 证据边界

这是离线 JUnit/Mockito 证据，不证明真实数据库跨 profile 历史回读、用户授权浏览器行为或实际 profile 切换。G-03、阶段 4 与项目总验收继续开放。
