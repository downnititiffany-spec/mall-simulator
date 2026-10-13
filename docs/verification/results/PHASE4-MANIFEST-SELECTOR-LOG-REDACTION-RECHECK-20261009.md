# 阶段4 ManifestSelector 日志诊断复核

日期：2026-10-09

`LandingManifestSelector` 本地和 LandingStorage 两条扫描路径此前将底层异常文本直接写入日志；JSON 解析失败还可能带出清单内容片段。现改为记录异常类型，解析失败日志只包含清单文件名，处理/容错行为不变。

验证：`mvn -o -f analytics-server/pom.xml -pl warehouse-pipeline,metric-analysis -am test`，platform-common 121 + connection-ingestion 412（2 skipped）+ warehouse-pipeline 213 + metric-analysis 153，共 899 项，0 failure/error、2 skipped；四模块 `BUILD SUCCESS`。`git diff --check` 通过（仅出现仓库已有 LF→CRLF 提示）。

范围仅 Windows JDK17 离线回归；未触碰数据库/3306、未启停服务、未验真实 Hive/Spark/API/浏览器链路。阶段4继续开放。