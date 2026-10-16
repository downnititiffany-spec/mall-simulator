# 阶段 4 RuntimeProfile Landing 探测使用候选命名空间

日期：2026-10-07

## 发现与修复

`RuntimeProfileServiceImpl.checkLanding()` 对 LOCAL profile 直接调用应用级 `LocalLandingStorage` bean 的健康探测，而采集和流水线按 profile 的 `landing_uri` 解析 LandingStorage。测试/激活候选 profile 时，探测因此可能验证另一个目录；候选 URI 为空时还可能借应用默认根误报通过。

现统一通过 `LandingStorageResolver.forProfile(candidate)` 解析并探测候选档案自身的 Landing 命名空间。缺失或无效 URI 会安全失败，健康检查响应仍不暴露 URI/异常正文。Spring 使用三依赖生产构造器；保留仅供现有隔离测试的四参数兼容构造器，其传入默认 LandingStorage 不参与探测。

## 验证

命令：

```text
mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am '-Dtest=RuntimeProfileProbeRedactionTest,RuntimeProfileServiceActiveProfileTest,RuntimeProfileHiveProbeRedactionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

JDK 17 离线 Maven reactor BUILD SUCCESS。`RuntimeProfileProbeRedactionTest` 6/6、`RuntimeProfileServiceActiveProfileTest` 4/4、`RuntimeProfileHiveProbeRedactionTest` 2/2，共 **12/12 PASS**，0 failure/error/skipped。新增验证覆盖候选临时目录收到探针，以及空 URI 不回退应用默认存储；原凭据/路径脱敏、MetricStore 候选 profile 和 Hive 探测回归仍通过。

## 证据边界

测试仅使用本地临时目录、mock 和 fake JDBC；未启动平台服务、未访问真实 Landing/HDFS/Hive/MySQL、未切换 profile、未部署新版。本项证明当前代码的候选路径解析与本地探针，不证明真实环境权限或远端存储可写。阶段 4 与项目验收保持部分完成。
