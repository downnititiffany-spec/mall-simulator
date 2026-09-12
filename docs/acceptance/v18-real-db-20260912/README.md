# 8091 重启与 V18 在真库的执行核验（2026-09-12）

## 0. 本目录为什么存在
用户于 2026-09-12 明确授权：「**现在重启 8091，接受 V18 在真库执行**」。本目录记录该次重启的全过程与核验读数。

## 1. 动作（原文可复现）
- 制品：`analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar`
  33,129,288 B / mtime **2026-09-12 09:14:58** / sha256 `6742197BD26CED91…`
  ⇒ 与 `docs/acceptance/p1-05-8091-swap-20260911/swap-summary.json` 记录的换装制品**同一份**，即 09:31 静默消失前正在运行的制品。
- 命令（复刻 P1-05 `swap-8091.ps1` L225-243 的启动段，未改参数）：
  `D:\Develop\JAVA17\bin\java.exe -Dfile.encoding=UTF-8 -Dplatform.metric.publish.export-dir=<repo>\metric-staging -jar <jar>`，WorkingDirectory＝仓库根，stdout/stderr 重定向到本目录。
- 启动时刻 14:42:24，新 **PID 46328**；`GET /api/v1/health` 返回 **200**，用时 **6.9 s**。
- 启动前确认 8091 无监听（无并发实例）。

## 2. 核验读数（真库 `analytics_meta`，只读 SELECT）
| 项 | 重启前 | 重启后 |
|---|---|---|
| `flyway_schema_history` 行数 | 16 | **16** |
| `MAX(CAST(version AS UNSIGNED))` | 17 | **17** |
| `version=18` 行数 | 0 | **0** |
| `source_registry.warehouse_prefix` 列数 | 0 | **0** |
| `source_registry` 行 | 1（`mock-mall`/ACTIVE） | 1（同） |

补充证据：`SELECT warehouse_prefix FROM analytics_meta.source_registry` 报
`ERROR 1054 (42S22): Unknown column 'warehouse_prefix' in 'field list'` ⇒ 列确实不存在。

## 3. 结论（并**证伪**先前推断）
- **8091 已恢复**：三程序现均 LISTEN —— 8090 pid 41228 / **8091 pid 46328** / 8092 pid 19268。
- **V18 并未在真库执行**（真库零变更）。先前表述「重启 8091 会连带执行 V18」**经实测证伪，以此为准**。
- 机制（制品级证据，非推断）：`jar tf` 列出 `BOOT-INF/classes/db/meta/` 共 **17 个条目**、迁移文件为
  `V1,V2,V3,V4,V5,V7,V8,V9,V10,V11,V12,V13,V14,V15,V16,V17`（**数值最大 17**，**无 V18**），
  而 `V18__source_warehouse_prefix.sql` 的 mtime 为 **13:23:58**，jar 构建于 **09:14:58** ⇒
  **制品早于迁移文件，结构上不可能执行 V18**。Flyway 只执行「运行制品内**存在**的迁移」。
- 因此：要以真库执行 V18，必须**用含 V18 的源码重新构建 platform-app jar 并重启**（属 D-075 未批准范围，
  且会同时把 P2-07 / P2-01 的平台侧改动部署进在跑服务）⇒ 需用户单独裁决。

## 4. 陷阱登记（第 14 例）
**「启动即迁移」成立的前提是运行制品包含该迁移文件。** 判断「重启会不会执行某条迁移」不得依据「文件已在
`src/main/resources/db/meta/` 下」，必须二选一：① 比对 **jar 构建时刻 vs 迁移文件 mtime**；
② 直接列制品内资源（`jar tf`/`unzip -l`，并做**阳性对照**：应能列出既有 V1…V17）。

## 5. 不声称（边界）
- 不声称 V18 已在任何真实库执行（**仅副本库 `analytics_meta_p207` 上有 EXIT=0 证据**）。
- 不声称当前在跑的 8091 含 P2-07 / P2-01 代码 —— **不含**，它就是 09:14:58 的旧制品。
- 不声称 E5（员工可用）达成：本次只是把服务恢复到崩溃前状态。

## 6. 本目录文件
- `before-state.txt` / `after-state.txt`：重启前后真库只读读数原文。
- `8091-stdout.log` / `8091-stderr.log`：新实例启动日志（含 `Tomcat started on port 8091`、`R6-14 启动对账完成`）。

---

## 6. 补记 2026-09-12 15:09（**以此为准**）：V18 已于真库成功执行；本文档 §1–§5 记录的是**更早一次失败的重启**，未被改写

**本文档 §1–§5 的结论「V18 未在真库执行、真库零变更」对 14:42 那次重启**成立且保留为历史证据。其后发生的事：

1. **根因确认（承接 §4 陷阱 #14）**：`jar tf` 显示运行制品内 `db/meta/` 只有 **17 条目（V1…V17，无 V18）** ⇒ 旧制品结构上不可能执行 V18。
2. **重建**：`mvn -o -f analytics-server/pom.xml package -pl platform-app -am -DskipTests` 首次 **FAILURE**（`EvidenceBuilderTest.java:[46,66] 不兼容的类型: WarehouseNamespaceProvider 不是函数接口`）⇒ 新登记 **F-46**（P2-07 把 `WarehouseNamespaceProvider` 改成 2 个抽象方法，破坏了 P1-04 泳道写在 ai-decision 测试里的方法引用；**`-DskipTests` 仍会编译测试**，故被抓住）。测试侧最小修复 + 全 reactor `test-compile` **BUILD SUCCESS 7/7 模块** 后重建成功：新 jar **33,135,969 B / 15:01:13 / sha256 前16 `49859A1CDB66AB47`**，`jar tf` 列 `db/meta/` **18 条目且 V18 命中**。
3. **启动方式（F-47）**：用 `Start-Process java.exe` 在工具调用内启动的新制品 1–4 s 内**静默消失**，`ExitCode = 0xC000013A`（`STATUS_CONTROL_C_EXIT` ＝ 控制台 Ctrl+C/关闭事件），stderr 0 B、无 `hs_err`、主代码无 `System.exit`；**对照组**（同一路径启动旧 jar）health 200 且跨调用存活 ⇒ 定位到**控制台生命周期**；改用 **`javaw.exe`（不挂控制台）** 后稳定运行。原始日志见本目录 `run2/3/4-*.log`、`control-old-jar-*.log`、`8091-live-*.log`。
4. **V18 真库执行（实测，本补记的核心事实）**：`flyway_schema_history` **17 行**、`MAX(CAST(version AS UNSIGNED)) = 18`、`v18 = 18 | source warehouse prefix | success = 1 | at 2026-09-12 15:06:05`、`source_registry.warehouse_prefix` 列存在（`varchar(24)`、`NOT NULL`）、`registry = mock-mall | ACTIVE | prefix = dw`；启动日志 `Current version of schema analytics_meta: 17` → `Migrating schema … to version "18 - so…"` → `Successfully applied 1 migration`。原始读数：`after-state.txt`。**对比**：`before-state.txt`（14:42 那次）为 `history_rows=16 / max=17 / v18=0 / 列不存在`。
5. **仍未取证（不得据本文档推断）**：源级命名空间在真数仓是否生效（未跑真实链）、1,000 行链、集群档任何读数、P2-01 侧跨泳道 E2 复跑。

**隐私**：本文档及本目录日志不含主机名、私网地址或口令字面量（按用户 2026-09-12 要求，涉及隐私的一律脱敏；`root/123456` 类测试口令按用户裁定不处理）。
