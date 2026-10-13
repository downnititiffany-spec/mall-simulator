# 阶段 4 G-03：证据包上期比较范围

日期：2026-10-10  
工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`  
范围：`EvidenceBuilder` 对明确快照构建证据包时选择上一业务日快照的源码修复与离线编译。

## 发现与修复

`EvidenceBuilder` 原先按前一业务日从 `MetricStore.listSnapshots()` 选取基线。列表按当前 ACTIVE RuntimeProfile 限定，但显式 snapshotId 可以指向其他 profile 的历史快照，因此可能把当前 profile 的上一日数据用于历史快照比较。

现在基线必须同时满足：

- runtime profile ID 与当前证据快照一致；
- 业务 source ID 与当前证据快照一致；
- 指标定义版本与当前证据快照一致；
- 业务日恰为前一日，状态为 ACTIVE 或 ARCHIVED。

当前快照缺少 profile、source 或 definition 身份时不构造比较，返回既有 `WARN_NO_COMPARISON_PERIOD` 路径。没有新增 API、数据库迁移或身份推断。现有 `EvidenceBuilderTest` 快照夹具补齐 profile ID，以匹配其已提供的同范围历史快照。

## 验证

- `mvn -o -f analytics-server/pom.xml -pl ai-decision -am -DskipTests compile`：BUILD SUCCESS；Reactor 的 platform-common、connection-ingestion、metric-analysis、ai-decision 均成功。
- `git diff --check`：通过。Git 对多个既有 CRLF 文件报告未来换行转换提示；没有执行批量换行转换。
- 未运行测试；未访问 API、浏览器、服务或数据库，未触碰 3306。

## 证据边界

本报告证明源码已按 D-059 方案 A 的身份边界编译，不证明测试行为、真实历史快照回读、profile 切换或真实数据库结果。G-03、阶段 4 与产品总验收保持开放。
