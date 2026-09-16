# S3-37 设计差异登记：`pipeline_run.input_batch_id` **展示侧**（让 S3-36 落库的批次真的可见）

- 日期：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`；主检出 `D:\Develop_code\GraduationProject` 全程未改）
- 行来源：`docs/PROJECT_STATUS.md` backlog 行「**`pipeline_run.input_batch_id` 恒 NULL**」的**剩余部分 R-2**（S3-36 收口了写入侧，本轮做展示侧）
- 类别：**A 类（实现/加性）** —— 见 §6（11 门逐门否）

## §0 本轮主张与证据边界（先说不能说什么）

**本轮实测**：`web` 套件 `npm test`（`node --test "tests/**/*.test.js"`）**114/114 `exit=0`**（基线 111 ⇒ **+3 条新守卫**）
＋ **7 个源码变异探针（K–Q）每个恰好 1 红且红的就是对应新守卫** ＋ 复原后 hash 逐字相同、终态复跑 114/114。
本轮**另跑**统一门禁 `default` 档一轮作**旁证**（`s337-side-1`，**非基线轮、不动基线**），用于把「后端逻辑上不可能受影响」
变成「实测未受影响」，见 §5。

**本轮未测**：
① **渲染未验** —— `web/node_modules` 不存在 ⇒ `vite build` 与真浏览器**都没跑**，**没有一张截图**。
⇒ 本轮**只能**主张「映射/列定义/页内表格三处接线在**源码层**可证，且有探针证明改动会被守卫抓住」，
**不能**主张「页面上确实出现了批次号」。
② `/api/v1/pipeline-runs` 的**真实响应**未取（无在跑的后端实例）⇒ 「响应里确实带 `inputBatchId`」是由
**后端源码**推得（§1 F2），不是抓包实测。
③ 真库里**没有任何一行**写入了批次（S3-36 未连库）⇒ 即便渲染正常，当前页面也会整列显示 `—`。
④ 历史行回填**不做**（写正式库 ＝ HARD DECISION 第④门）。

## §1 开工前实测（F1–F7，全部真跑）

| # | 实测 | 证据（命令 / 文件:行） |
| --- | --- | --- |
| F1 | `web/**` 全仓对 `inputBatchId` / `input_batch_id` **0 命中** ⇒ 前端从未搬运该列（与 backlog 行一致） | `Get-ChildItem web\src,web\tests -Recurse -File -Include *.js,*.vue,*.json \| Select-String 'inputBatchId\|input_batch_id\|批次'` ⇒ 空 |
| F2 | 后端 `/api/v1/pipeline-runs` 返回 `ApiResponse<List<PipelineRun>>` —— **实体整行**（`runMapper.selectList`）⇒ `inputBatchId` **已在响应报文里**（S3-36 起写入侧落库） | `PlatformController`…→`PipelineController.java:70-72`（`public ApiResponse<List<PipelineRun>> list(...)`） |
| F3 | `pipelineRunRows`（`tables.js:78`）是实例行**唯一映射属主**，被 `/pipeline` 与 `/ops` 共用；S3-34 的 `sourceDataVersion` 走的就是同一模式（映射 ＋ `COLUMNS`） | `Pipeline.vue:111`、`Ops.vue:370`（`mapPipelineRunRows`）、`tables.test.js:119-141`（S3-34 先例） |
| F4 | `COLUMNS.opsPipelineRuns`（`tables.js:157`）驱动 `/ops` 表格与 **CSV 导出**（表头 `:415`、行 `:416` 同一表对象）⇒ 加一列**自动**进导出件 | `Ops.vue:120/370/415/416` |
| F5 | `/pipeline` 的实例表是**页内硬编码 8 列**（不用 `COLUMNS`），空态 `colspan="8"` ⇒ 加列必须**同批**改表头/单元格/`colspan` | `Pipeline.vue:44`（表头）、`:51-52`（单元格）、`:59`（空态） |
| F6 | 缺失值词汇属主＝`number.js:5 EMPTY_TEXT='—'`；**id 类列既有先例用 `text()`**（`id: text(r.id)`） | `tables.js:7`、`tables.js:80` |
| F7 | `docs/contracts/**` 对 `pipelineRunRows` / `opsPipelineRuns` / `源数据版本` **0 命中** ⇒ 行映射层**不受契约治理** ⇒ 本轮**无需**契约补记（与 S3-35 改归一化器不同） | `Get-ChildItem docs\contracts -File \| Select-String 'pipelineRunRows\|源数据版本\|opsPipelineRuns'` ⇒ 空 |

## §2 可复现命令

```powershell
Set-Location 'D:\Develop_code\GraduationProject-wt\v3-dev\web'
npm test                                   # 114/114 exit=0（RED 阶段：3 红，全为本轮新守卫）
# 旁证（非基线轮）：
Set-Location 'D:\Develop_code\GraduationProject-wt\v3-dev'
& .\scripts\run-tests.ps1 -Suite default -RunId 's337-side-1' -LogDir '.verify\s337-default-1' -Confirm
# 变异探针（脚本在 $env:TEMP\s337_probe.ps1，逐个改一处在跑 npm test 后从备份复原）：
& "$env:TEMP\s337_probe.ps1"
```

## §3 本轮冻结口径

1. **只搬运、不重算**：`inputBatchId` 一律 `text(r.inputBatchId)`（`String(v)`），**不**做任何推断、**不**从别的字段推。
   id 类列用 `text()` 而非 `formatInteger()`，与既有 `id: text(r.id)` 同形（千分位会把批次号读成数量）。
2. **NULL 必须显示占位符 `—`**：S3-36 的语义是「NULL ＝ **未知**」（不是「缺失」、更不是 0）。
   ⇒ 缺失时**不得**用 `sourceDataVersion` / `targetSnapshotId` 顶替（探针 M），**不得**显示 `0`（探针 N）。
3. **列标签固定为「输入批次」**（＝ `ingestion_batch.id`），两个消费面同批：`COLUMNS.opsPipelineRuns`（`/ops` 表格 ＋ CSV 导出）
   与 `Pipeline.vue` 页内硬编码表（`/pipeline`）。
4. **页内表格的「列数 ＝ 空态 `colspan`」不变量**：新列落位后 `colspan` 由 8 → 9（探针 O 证明该不变量有牙）。
5. **不加新的映射层**：值仍在唯一属主 `pipelineRunRows` 内搬运；`/pipeline` 不新起一套自渲染（沿用 S3-34/S3-35 的属主收敛结论）。
6. **页内说明文字同批更新**：原本写「源数据版本 / 目标快照」两列，现为**三列**并写明老实例该列未记录、显示「—」
   （避免出现 S3-35 登记过的「注释与实现不一致」）。

## §4 实现面（4 文件，全部 `web/**`；生产 +12/−4（`tables.js` +6/−0、`Pipeline.vue` +6/−4），测试 +38/−0）

| 文件 | 改动 | 行 |
| --- | --- | --- |
| `web/src/utils/tables.js` | `pipelineRunRows` 加性加 `inputBatchId: text(r.inputBatchId)`（含口径注释：NULL 是未知、不得顶替/不得显示 0） | 注释 L86-89、映射 L90 |
| `web/src/utils/tables.js` | `COLUMNS.opsPipelineRuns` 加性加 `{ key: 'inputBatchId', label: '输入批次' }`（紧随「源数据版本」） | L167 |
| `web/src/views/Pipeline.vue` | 页内表加 `<th>输入批次</th>`（L45）与 `<td class="mono">{{ r.inputBatchId }}</td>`（L53）；空态 `colspan` 8→9（L61）；页内说明文字「两列」→「三列」并写明老实例显示「—」（L11-12）；顶部注释同批补「输入批次」（L7） | L7、L11-12、L45、L53、L61 |
| `web/tests/tables.test.js` | 新增 2 条：映射（含"不得顶替/不得显示 0"断言）＋ `COLUMNS` 列存在 | L143-166 |
| `web/tests/pipelinePage.test.js` | 新增 1 条：页内表头/单元格 ＋ **列数＝colspan** 不变量 | L69-82 |

## §5 证据矩阵（真跑，逐条可复现）

| 轮次 | 动作 | 结果 |
| --- | --- | --- |
| **RED** | 先写 3 条新守卫，未改实现 | `npm test` **`exit=1`**、`✖` **3 条全部为本轮新守卫**（映射 / COLUMNS / 页内表格） |
| **GREEN-1** | 实现 §4 三处加性改动 | `npm test` **114/114 `exit=0`**（基线 111 ＋ 3 新） |
| **探针 K**（删行映射） | 删 `inputBatchId: text(r.inputBatchId),` | `pass=113`、**唯一红＝行映射守卫** |
| **探针 L**（删列定义） | 删 `COLUMNS` 的 `inputBatchId` 项 | `pass=113`、**唯一红＝COLUMNS 守卫** |
| **探针 M**（顶替） | 缺失时改用 `r.sourceDataVersion` | `pass=113`、**唯一红＝映射守卫**（"不得顶替"有牙） |
| **探针 N**（显示 0） | 缺失时显示 `0` | `pass=113`、**唯一红＝映射守卫**（"不得把未知说成 0 号批次"有牙） |
| **探针 O**（忘改 colspan） | `colspan="9"` → `8` | `pass=113`、**唯一红＝页内表格守卫**（跨列不变量有牙） |
| **探针 P**（删表头） | 删 `<th>输入批次</th>` | `pass=113`、**唯一红＝页内表格守卫** |
| **探针 Q**（删单元格） | 删 `<td …>{{ r.inputBatchId }}</td>` | `pass=113`、**唯一红＝页内表格守卫** |
| **复原** | 从 `$env:TEMP\s337_bak` 复原两文件 | `tables.js` `B5002673F234587E…`、`Pipeline.vue` `D40BAD2BC5E7E483…` **与探测前逐字相同**；终态复跑 **114/114 `exit=0`** |
| **旁证轮**（非基线轮，`s337-side-1`） | 统一门禁 `default` 档（**不动基线**） | `analytics-server` `tests=956 MATCH`（明细 `93+350+169+93+94+157`）、mall `13 MATCH`、generator `106 MATCH`、三棵树 `1075（基线 1075）`、`[FAIL exit=7]`，**唯一红＝同一已登记环境性红**（`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61` `expected: 43 but was: 0`）＝与 S3-36 收口轮**逐字相同** ⇒ 实测「本轮改动对后端零影响」（逻辑上也零字节：`git status` 只列 `web/**` 与文档） |

**探针语义**：每个探针**只改一处**、锚点唯一性在脚本里强制（命中数 ≠ 1 直接抛错），跑完立即从备份复原；
`✖` 计数包含 `✖ failing tests:` 表头行，故输出 `fail=3` 而**唯一红测试恒为 1 条**（见 §5 每行的红名）。

## §6 类别判定与 11 门逐门核对

**类别：A 类（实现/加性）** —— 只在前端映射层与页内表格**增加**一列的搬运与展示，无删除、无语义改写、无后端与库改动。

| # | 门 | 是否触发 | 依据 |
| --- | --- | --- | --- |
| ① | DROP TABLE/COLUMN | **否** | 未动任何 DDL |
| ② | 改已有字段类型或既有业务语义 | **否** | `input_batch_id` 语义由 V7 定义、S3-36 落库；本轮只**读并展示**，未改语义 |
| ③ | 改已发布 Flyway migration | **否** | 未触碰任何 `V*.sql` |
| ④ | 写/迁移正式 3306 数据 | **否** | 本轮 **0 次连库**；历史行回填明确不做 |
| ⑤ | 切 ACTIVE | **否** | 未触碰快照/激活链路 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | **否** | 未触碰 `contract-specs/**`；且 F7 实测行映射层不受契约治理 ⇒ 连 `docs/contracts/**` 也无需改 |
| ⑦ | 改 V3.0 总体架构 | **否** | 只加一列的搬运/展示 |
| ⑧ | 改正式项目范围 | **否** | 落在既有 E5-c/阶段5「页面显示溯源信息」范围内（同一 backlog 行的剩余部分 R-2） |
| ⑨ | 删除已发布功能 | **否** | 纯加性（`+6/−2`，其中 −2 是 `colspan` 与说明文字的同批更新） |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **否** | 未新增依赖（`web/node_modules` 依旧不存在） |
| ⑪ | 两种方案造成重大长期架构分叉 | **否** | 只有一种合理落法：沿用唯一映射属主 ＋ 既有 `COLUMNS` 先例 |

## §7 未测与边界（不得越界表述）

1. **渲染未验**：`web/node_modules` 不存在 ⇒ `vite build`／真浏览器未跑 ⇒ **不得**称「页面已验证显示批次」，
   只能说「源码层接线可证 ＋ 探针证明守卫有牙」。**无截图**。
2. **接口真实响应未取**：F2 由后端源码推得；未对在跑服务抓包。
3. **库里仍无批次数据**：S3-36 未连库、历史行未回填 ⇒ 即便渲染正常，当前页面整列会是 `—`。
   ⇒ **不得**称「批次可追溯已补齐」。
4. **`/ops` 之外的两处未动**：`/overview` 等其它页与 `/pipeline` 的**上下文条**不展示批次（上下文条是响应级口径，批次是行级事实，
   **故意**不混入 —— 这正是 S3-35 冻结的「不得把行级值冒充响应级口径」）。
5. **CSV 导出只验到「列进表头/行同一对象」这一层**（`Ops.vue:415-416` 同源），未生成真实 CSV 文件。
6. **`—` 与「未记录」的区分**：展示层统一 `—`；「S3-36 之前的历史实例未记录」这一语义只在页内说明文字里表达，
   **不**做逐行判断（前端无从知道该行是历史行还是 fail-closed 行）。
7. **E5-c 仍不关闭**：本轮推进的是「批次级溯源」的展示侧；其**业务源身份**（`source_id`/`sourceCode`/per-source namespace）仍 **B 类、未做**。
8. **不重跑 `spark`/`isolated` 档**：零改动。

## §8 遗留

- **R-1**：历史行 `input_batch_id` 回填 —— 写正式库 ⇒ HARD DECISION **第④门**，**未做**，等待总控裁决。
- **R-2**：`pipelineRunRows` 目前映射 `sourceDataVersion`/`inputBatchId`/`targetSnapshotId` 三个溯源字段；
  是否把「阶段证据 JSON」也搬到页面（当前只搬实体列）属新范围，未做。
- **R-3**：`useAnalysis.js:33` 注释漂移（S3-35 登记，`docs/PROJECT_STATUS.md` backlog 行）仍开放。
- **R-4**：S3-36 登记的「把钉批次的读源从证据 JSON 改为 `input_batch_id` 列」仍为可选收敛方向，未做。
- **R-5**：渲染验证依赖 `web/node_modules`（环境缺口，非本轮引入）。

## §9 反熵声明

- **没有第二个批次属主**：值仍在唯一映射属主 `pipelineRunRows` 内搬运（探针 K 证明删掉它就有牙），
  `/pipeline` 页内表**没有**新起自渲染/副本逻辑，只加一列绑定；`/ops` 与 `/pipeline` **共用**同一映射与同一 `COLUMNS` 定义。
- **没有新的缺失值词汇**：沿用 `number.js` 的 `EMPTY_TEXT='—'`（该词汇的既有唯一属主）；未新增「未知/未记录/N.A.」等第二套词汇。
- **没有把行级事实提升为响应级口径**：批次只出现在**实例表**，**不**进上下文条/导出件元信息（那是响应级口径，S3-35 已冻结）。
- **零删除、零降级**：生产 `+6/−2`（−2 为同批更新的 `colspan` 与说明文字，均**必须**与新列同步才算正确），
  既有断言与既有列全部保留，唯一改动是**追加**。
