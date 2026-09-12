# P1-05 轮 · D-040 原子换血：真库应用 V17 + 8091 换新 jar（同一批）

**状态：未执行。** 前置条件是"P1-05 代码已合入且 E2 独立复核为绿"；由**总控**执行（D-040）。执行一次即可，脚本自带失败即停。

## 为什么必须同批原子

V17 让 `file_checkpoint.source_id` 变成 `NOT NULL` 且**无默认值**。旧 jar 的写入路径不是按源写入的，因此：

| 顺序 | 结果 |
|---|---|
| 先迁库、稍后换 jar | 中间窗口内**采集写入失败**：`Field 'source_id' doesn't have a default value`（读路径不受影响） |
| 先换 jar、稍后迁库 | 新 jar 读不到 `source_id` 列而失败 |
| **同批（本方案）** | 停机窗口内一次性完成，无上述中间态 |

因此本脚本把"迁库"交给**新实例启动时的 `MetaFlywayInitializer`**——即"启新 jar"本身就是"应用 V17"，两者天然同批。

## 执行

```powershell
pwsh -File docs/acceptance/p1-05-8091-swap-20260911/swap-8091.ps1
```

## 断言（任一不满足即 FAIL-STOP，且**不自动回滚**）

| 阶段 | 断言 |
|---|---|
| G0 | `git status --porcelain -- analytics-server` 为空 ⇒ **打包树 == 提交树** |
| G1 | 真库迁移末条（按 `installed_rank`）为 **V16** |
| 1 | 记录旧实例 PID / 命令 / jar sha256；`mysqldump --no-data` 存 DDL 快照；记录行数 |
| 2 | 只停 8091 这一个 PID（**不碰 8090/8092**），30 s 内端口必须释放 |
| 3 | 打包成功；新 jar sha256 **≠** 旧 jar；`jar tf` 含 `SourceRegistryController` 与 `SourceRegistryServiceImpl` |
| 4 | `/api/v1/health` 150 s 内 200 |
| 5 | 迁移末条 = **17**；`file_checkpoint.source_id` 为 NOT NULL；旧 `uk_ckpt` 消失、新 `uk_ckpt_source` 存在；`source_id` 无 NULL；行数与换血前**逐字一致**（迁移不应动数据） |
| 6 | **带会话**探测（未认证 401 无区分力，见 F-04）：`GET /api/v1/sources` = 200，且对照路径 `__no_such_endpoint__` 状态码**不同**（否则判定本次探测无区分力、结论无效） |
| 7 | 登记新实例 PID 与新 jar sha256 → `swap-summary.json` |

## 与常驻旧实例的有意差异（换血时生效，须记录）

旧实例命令行把 `-Dplatform.metric.publish.export-dir=…` 放在 `-jar` **之后**（应用参数位，Spring 不绑定 `-Dk=v` 形态，见 F-06）。本脚本改为：

1. 该属性放在 `-jar` **之前**（JVM 系统属性，绑定无疑义）；
2. 显式 `-WorkingDirectory = 仓库根`。

两个改动在"Spring 是否绑定 `-D`"两种解释下结果相同（值本就等于"默认值 + CWD=仓库根"），但行为不再依赖进程工作目录。

## 产物

`swap-8091.log`（全程时序）、`pre-v17-schema.sql`（换血前 DDL 快照，mysqldump 自带头含版本与导出时刻）、`package.log`、`8091-stdout.log`、`8091-stderr.log`、`swap-summary.json`（新旧实例身份与关键断言结果）。

> `*.log` 在本仓库被 gitignore 覆盖，提交时需 `git add -f`。
