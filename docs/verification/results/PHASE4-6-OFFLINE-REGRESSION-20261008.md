# 阶段 4–6 当前工作树离线回归（2026-10-08）

## 执行范围

针对当前在途 Java 17 后端与 Web 改动运行模块级/前端验证。未修改业务代码；没有连接 MySQL/Hive/HDFS，没有调用外部 LLM，没有部署或写入业务数据。

## 结果

命令：

```text
mvn -o -f analytics-server/pom.xml -pl platform-app -am test
cd web && npm run verify
```

以本轮新写的 Surefire XML（2026-10-08 12:43:59 后）逐模块核数：

| 模块 | 测试 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| platform-common | 119 | 0 | 0 | 0 |
| connection-ingestion | 404 | 0 | 0 | 2 |
| warehouse-pipeline | 209 | 0 | 0 | 0 |
| metric-analysis | 152 | 0 | 0 | 0 |
| ai-decision | 208 | 0 | 0 | 0 |
| platform-app | 218 | 0 | 0 | 0 |
| **合计** | **1310** | **0** | **0** | **2** |

Maven 七模块 reactor BUILD SUCCESS。Web Node 测试 **403/403 PASS**，0 fail/cancelled/skipped；Vite production build 成功，680 modules transformed。测试输出包含既有 WebSocket 端口占用诊断行，未影响测试或构建结果。

## 证据边界

本报告证明当前工作树上述离线测试/构建结果，不证明数据库授权/迁移、真实浏览器业务数据、Hive/HDFS 集群或真实 LLM Provider。阶段 4/6 及最终产品验收仍依 V3.0 阶段门单独核验。管理员任务变量当前不可见，故没有认证分页验证。`git diff --check` 通过，只有既存 LF/CRLF 提示。动态文档备份后缀：`bak-20261008-full-offline-regression`。
