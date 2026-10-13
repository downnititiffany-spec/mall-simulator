# 阶段 8 隔离 RunId 运行状态复核

日期：2026-10-07（Asia/Shanghai）

## 复核

只读调用统一脚本并显式指定其原任务根：

```text
CLOUD_SINGLE_NODE_ROOT=/home/asus/graduationproject-runs bash scripts/cloud-single-node.sh status
```

脚本仍登记 RunId `cloud_261007_021316_0219d8`，但本次列出的服务端口均为 closed：MySQL 3307、HDFS 19000/19010/19870/19864/19866、Metastore 19083、平台/商城/生成器 8091/8090/8092、Web 5173/5174。独立 `ss` 监听检查未发现这些端口的监听项。

## 处理与边界

- 本轮仅检查状态，没有启动、停止或向任何进程发信号；未访问数据库或持久数据目录。
- RunId 与登记/数据目录仍保留，但不能描述为服务正在运行。停服后的数据恢复或新 RunId 建立仍应走统一脚本并先通过安全预检。
- 没有连接 3306；本结果不构成部署恢复、业务验收或产品签收证据。
