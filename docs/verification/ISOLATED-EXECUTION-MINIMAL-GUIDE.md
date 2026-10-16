# 最小隔离执行说明（可重复执行配置）

> 依据：V3.1 指导书 §5 G31-01 任务 01.5。本文档是「可重复的最小隔离配置」说明。
> **全文无任何密钥/口令字面值**；涉及既有文档化口令处一律以「W03 受控登记的文档化值」指称。
> 配套结果：`docs/verification/results/G31-01-TEST-ISOLATION-RESULT.md`。

## 1. 隔离原则（为什么是这样）

1. **3306 永久冻结零接触**（含只读）：所有数据库 URL 只允许 `jdbc:mysql://127.0.0.1:3307/<数据域RunId>_<库名>`；禁止为了「查证是否误连」而探测 3306。
2. **必填变量缺失即拒绝启动，不回落任何配置文件默认值**：`application.yml` 已无兜底默认（裸占位符）；外层门禁 `scripts/assert-platform-env.ps1` 在任何 JVM 启动之前拒绝。
3. **发布与只读分别授权**：平台写路径用 metricapp（库内 ALL），读路径用 SELECT-only metricread；root 仅限已授权隔离准备（prep），绝不用于应用查询或 IT 断言。
4. **口令纪律**：口令只在进程环境存在——进程内生成（crypto-RNG）或调用方注入；零落盘、零 argv、零 git、零日志回显；账本/证据只记**引用名**。
5. **只清理自己本次拥有的资源**：按 pidfile+身份证据精确停树（绝不按进程名组杀）；无范围 DROP；不清理旧 runId。

## 2. 前置条件

| 项 | 要求 |
|---|---|
| 隔离 MySQL | WSL 3307（`--port=3307 --bind-address=127.0.0.1`），数据域 `<dataRunId>_{analytics_meta,analytics_metric,mall,generator}` 已由 prep 建好 |
| JDK | 平台/测试 JDK17；spark 档 JDK8（见 scripts/run-tests.ps1 头部工具链段） |
| 端口 | 8091 空闲（平台 HTTP）；3307 可达 |
| 仓库 | 代码基线以 git SHA 钉定；jar 由当前工作树构建（driver 记录其 SHA256 供追溯） |

## 3. 环境变量通道（只列名，值一律进程内生成/注入）

**IT 口令通道（prep 输入，进程 env）**
- `V25_IT_META_PASSWORD` — meta 库应用账号口令
- `V25_IT_METRIC_PUBLISH_PASSWORD` — metric 库发布账号口令
- `V25_IT_METRIC_READ_PASSWORD` — 只读账号口令（缺省 = 不创建只读账号，向后兼容旧行为）
- `IT_GUARD_*` — isolated 档守卫凭据（run-tests.ps1 isolated 档要求）
- `V25IT_ADMIN_PWD` — prep 用 root 凭据，**取 W03 受控登记的文档化值**，且仅在 WSL mysql 进程环境内使用

**平台启动通道（13 个 `PLATFORM_*`，缺一即拒）**
- `PLATFORM_META_URL / PLATFORM_META_USER / PLATFORM_META_PASSWORD`
- `PLATFORM_METRIC_PUBLISH_URL / PLATFORM_METRIC_PUBLISH_USER / PLATFORM_METRIC_PUBLISH_PASSWORD`
- `PLATFORM_METRIC_READ_URL / PLATFORM_METRIC_READ_USER / PLATFORM_METRIC_READ_PASSWORD`
- `PLATFORM_LANDING_LOCAL_ROOT / PLATFORM_SOURCE_PROFILE_ROOT / PLATFORM_SPARK_WAREHOUSE_DIR / PLATFORM_SPARK_METASTORE_DIR`
- URL 形状：`jdbc:mysql://127.0.0.1:3307/<dataRunId>_analytics_{meta|metric}?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true`
- READ 账号 = 本轮 metricread（SELECT-only）；PUBLISH 账号 = metricapp。

## 4. 最小执行序列（五步）

```text
# ① 幂等准备（零 DROP；创建/重置本次 runId 账号并授权；四口令通道在进程 env 中）
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId <dataRunId> -Confirm -AllowRootOnIsolated -IncludeAnalytics

# ② 启动前门禁（13 变量 + URL 作用域 + 库名前缀 + 禁 3306；任一不满足 exit 12，不启 JVM）
pwsh -NoProfile -File scripts/assert-platform-env.ps1 -RequirePlatform -AllowedSchemaPrefixes @('<dataRunId>_')

# ③ 分离启动平台（独立隐藏控制台；写 platform.pid + platform.identity.json{pid,name,marker=RunId} + ledger-run.json）
#    参考实现：target/v25-it/g3101_20260920_003500/session/g3101-session1-start.ps1 [5/7] 段
#    就绪探针：GET http://127.0.0.1:8091/api/v1/metrics/health
#    启动后 F2b：平台日志零 ':3306' 且两个 3307 JDBC URL 均出现

# ④ 业务探针（只读链路证据）
#    POST /api/v1/auth/login → GET /api/v1/auth/me（Bearer）
#    GET /api/v1/metrics/snapshots、/overview（走 metricRead 只读源；日志应出现 metric-read-ds 池名）

# ⑤ 独立清理（可在任意时刻、甚至原驱动已退出/中断后调用；清理失败非 0 退出）
pwsh -NoProfile -File scripts/stop-platform-by-pidfile.ps1 -PidFile <session>\platform.pid `
     -IdentityFile <session>\platform.identity.json -LogPath <session>\logs\platform.log `
     -Port 8091 -LedgerOut <session>\ledger-stop.json
```

**退出码契约**：门禁 12（拒绝）/ 停止脚本 0=STOPPED 或 ALREADY_GONE、5=身份拒绝（未杀任何进程）、7=清理未达效（端口/3306/进程残留）；1=用法错误。清理失败一律不得报 PASS。

## 5. 可重复性要点

- 同一 `<dataRunId>` 重复执行 prep 是安全的：CREATE IF NOT EXISTS + ALTER USER 重置 + GRANT，**零 DROP、不清理旧数据**；数据完好性门（`SNAP|...|ACTIVE` 唯一 + ADS 计数 + sys_user 计数）每次执行后复核。
- 测试报告新鲜度由 `scripts/run-tests.ps1` 硬门禁保证：N>0 且 F=E=0 且模块退出码 0；spark 档只认本轮新写的 `TestSuite.txt`（mtime ≥ 启动时刻），不用 surefire 零用例兜底；并发同 RunId/同日志目录被互斥锁响亮拒绝（exit 5）。
- 每轮 attempt 的资源（进程/端口/目录/库/凭据引用名）写账本（ledger-run/ledger-stop），PID 复用由 identity.json（pid+name+命令行 marker=RunId）三重核对拒绝。
- 端到端示范 driver：`target/v25-it/g3101_20260920_003500/session/g3101-session{1-start,2-cleanup}.ps1`（钉死 RunId、需 `-Confirm`；可直接作为新批次模板复用，替换 RunId 与数据域即可）。

## 6. 禁止事项（与指导书 5.1/边界一致）

- 禁止在 `application.yml` 或任何配置文件中恢复数据库 URL/账号/口令默认值。
- 禁止把任何口令写进脚本参数、日志、证据文件、git（含 `.zcode/`——该目录永不提交）。
- 禁止以 root 账号运行平台或 IT 断言；禁止为「验证是否误连 3306」而对 3306 发起任何连接。
- 禁止对隔离域执行无范围 DROP、清理历史 runId、重置既有 HDFS。
