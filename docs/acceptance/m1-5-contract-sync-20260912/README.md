# M1-5 契约目录收口：按 V2.3 §4.1.1 之 11 同步（2026-09-12，总控执行）

**看板状态词**：本项收口后 M1-5 仍为 `REVIEW`（剩余 = B-06 严格化 + 258 处裸锚点逐条复核），本次只完成「按指导书点名的区域同步」这一件事。**已实测，未入库前的原始日志全部留在 `raw/` 与 `scripts/`。**

---

## 1. 任务与范围

**触发（逐字引用指导书 V2.3 §4.1.1 之 11【新增要求】，L232）**：

> `contract-specs/openapi/generator-api.v1.yaml` 现在把这一区域标为 `x-unspecified`，待 M1-5 收口时按本节同步（含 `TargetCheckResult` 的空对象声明与 `adapter_type` 取值集合）

即：M1-5（中立契约目录）在看板上停在 `REVIEW` 的原因之一，是**V2.3 已经把 §4.1 的实现侧形状追认为契约**（§4.1.1.3 的 DTO 表），而契约文件还写着"指导书没有给出它的任何字段"。本次把该区域按 V2.3 同步。

**改动范围（4 个文件，全在 `contract-specs/`）**：`openapi/generator-api.v1.yaml`、`schemas/generation-artifact-manifest.v1.schema.json`、`README.md`、`VERSION`。
**明确不做**：不改任何 Java/Scala/SQL；不改指导书与看板正文（看板只改状态词与指针）；不改其余 5 个契约文件的内容（只在 OpenAPI 头部加一段锚点来源说明）；不做 B-06 严格化；不逐条重算 258 处裸锚点（见 §7、§11）。

## 2. 交付物

| 文件 | 同步前 SHA-256 | 同步后 SHA-256 | 改动量（`git diff --numstat`） |
|---|---|---|---|
| `contract-specs/openapi/generator-api.v1.yaml` | `51306FE81A7468FA16DB79BB4627CE5EB7B401007141F7A08886931F6E68FDF2` | `98A07A52D9AF11665C034F5BC51E03FDF9B95E665DE3E4EB17B205D5BB6531B9` | +70 / −24 |
| `contract-specs/schemas/generation-artifact-manifest.v1.schema.json` | `865D2B283A33AEBA29982159891DF570FB2C282C23EB1C4B7DEBEFB03FB8D732` | `04D09045757135E5E8BCB08CD14DF37866A9269BBC41E5CB3200A35404D0189C` | +8 / −1 |
| `contract-specs/README.md` | `13D2D6A6BF91A56F4BF53D642F52D2BDDF8AC88480A112B1245882D9E360BB34` | `07D2F02E31CEFB28665B32C00B7D7E554621D4B4991EB64C617601B7455E33BB` | +47 / −4 |
| `contract-specs/VERSION` | `C9E89F9DC5A13DD44A5F75BE0F69F7239723875F4685B11E93AAB09B6DDBC4A0` | `B6BAB8E0547C6BC0EB7005128E177B891E32534FA3522D54B079E7AEC384EC89` | +1 / −1（`contract-specs 1.2.0` → `1.3.0`） |

未改动的对照文件（本轮同目录哈希）：`schemas/canonical-event.v1.schema.json` `0E2E4ED2…`、`schemas/ingestion-manifest.v1.schema.json` `0993E147…`、`specs/warehouse-namespace.v1.json` `463D9DC3…`。

**证据文件**：`raw/raw-sync-log-20260912.txt`（22 条替换）、`raw/raw-erratum-log-20260912.txt`（勘误写入）、`raw/raw-verify-log-20260912.txt`（可重复的状态复核）、`scripts/{sync,erratum,verify-state}.ps1`。

## 3. 方法与证据级别

- **本项不涉及 Java/SQL/Scala ⇒ 无 E1**；也**未跑 Maven**：受影响文件在代码里只被 javadoc 注释引用（全仓检索 `generation-artifact-manifest` / `generator-api.v1.yaml` / `contract-specs/VERSION` / `x-evidence` 共 4 处命中，全部是注释：`ArtifactManifest.java:12`、`RunReport.java:19`、`GeneratorApiDtos.java:11`、`GenerationRunController.java:19`）⇒ 无测试读取这些文件的内容，改契约不会改变任何 E2 结果。
- **证据类型**＝① 脚本化替换，**每一条都断言"命中且仅命中 1 次"**，任一命中数不符即整脚本 `throw`、**不写盘**（`sync.ps1` 22 条、`erratum.ps1` 4 条）；② 独立解析器复验（Python **3.14.5** + PyYAML **6.0.3** + jsonschema **4.26.0**，`Draft202012Validator.check_schema`）；③ 前后 SHA-256 对照；④ 判据脚本 `verify-state.ps1` **可重复运行**（不依赖一次性替换动作）。
- `VERSION` 升版安全性已实测：全仓检索 `contract-specs 1.2.0` / `"1.2.0"` 只命中 `IngestionManifestSourceSchemaTest.java:25` 的一行**注释**（"目录级 1.2.0，P1-05 加法扩展"），**没有任何代码或断言钉住该版本串**。

## 4. 同步内容（7 类）

| # | 对象 | 同步前（1.2.0） | 同步后（1.3.0） | 依据 |
|---|---|---|---|---|
| 1 | `TargetCheckResult` | 空对象 + `x-unspecified`，明写"指导书没有给出它的任何字段，不发明检查项字段" | 声明 `targetId`/`reachable`/`detail`/`capabilities` 四个 properties；**不声明 `required`** | V2.3 §4.1.1.3 L240 |
| 2 | `TargetCapabilities` | **不存在** | 新增 schema：`verdicts`（能力名→三态）+ `declared` | V2.3 §4.1.1.3 L241、§4.1.1.2 之 5 |
| 3 | `adapter_type` 取值集合 | "取值集合未冻结（§4.1 L126 只说明第一版实现…）" | "**按契约不冻结**"（语义由"来源漏规定"改为"契约明确留开"） | V2.3 §4.1.1.1 L158 |
| 4 | `generator_target.capabilities` 列 | "能力集合（对应 §4.1 L108）。结构未冻结" | "能力**台账列**，**不参与任何能力判定**" | V2.3 §4.1.1.1 L216-218 |
| 5 | `credential_ref` | "凭据只存引用；引用形态未冻结" | 追加凭据纪律（日志/流水/异常里只允许出现引用名） | V2.3 §4.1.1.2 之 4 |
| 6 | 制品清单 `x-synthetic-marker` | "未规定该标记落在制品清单、运行报告还是响应体；需契约任务确认" | **落点已被追认**（制品清单是三载体之一），并要求三处载体都写 | V2.3 L675 |
| 7 | 全部 `§x.y Lzzz` 锚点 | 裸 V2.1/V2.2 行号 | 本轮核过的加 `V2.3 ` 前缀并改行号（OpenAPI 16 处 + README Q9/Q10/Q11 + 制品清单 x-evidence） | 指导书已迭代到 V2.3 |

被**撤销的 `x-unspecified` 只有 2 处**（`/targets/{id}/test` 的 200 响应、`TargetCheckResult` 对象级）；其余一律保留。同步后的 `x-unspecified` 出现次数（实测）：OpenAPI **70**、`generation-artifact-manifest.v1` **7**、`canonical-event.v1` **2**、`ingestion-manifest.v1` **8**、`warehouse-namespace.v1` **0**、`README.md` **7** ⇒ **不得声称已清零**。

## 5. 勘误：契约里的 V2.1 锚点系统性"少一行"（新事实 F-29）

同步过程中发现：这些契约文件里的 **V2.1 锚点全部比实际行号少一行**。不是推断，是拿 V2.1 原文逐条比对出来的（11 条对照，见 `raw/raw-erratum-log-20260912.txt` 与 `raw/raw-verify-log-20260912.txt` 第 2 步，11/11 PASS）：

| 旧锚点（原文写法） | V2.1 该行**实际**内容 | 正确行号（V2.1） | V2.3 行号 |
|---|---|---|---|
| §4.1 L107 `test(TargetConfig)` | `interface MallTargetAdapter {` | L108 | L120 |
| §4.1 L108 `capabilities()` | `TargetCheckResult test(TargetConfig config);` | L109 | L121 |
| §4.1 L126 第一版实现 | 空行（该句在 L127） | L127 | L139 |
| §4.1 L118-L123 EventSink 四方法 | EventSink 块实在 L119-L124 | L119-L124 | L131-L136 |
| §4.2 L132 `generator_target` | 表格分隔行 | L133 | L278 |
| §4.2 L134 `generation_run` 字段 | `generation_plan` 行 | L135 | L280 |
| §4.2 L135 `generation_artifact` 字段 | `generation_run` 行 | L136 | L281 |
| §4.2 L138 `generation_event_stat` 字段 | 空行（该行在 L137） | L137 | L282 |
| §4.3 L149 异常样本/期望隔离数 | 「可复现」条目 | L150 | L295 |
| §4.4 L153 响应写作 `runId` | 空行（该句在 L154） | L154 | L299 |
| §4.4 L158 连通性检查 | `GET /api/v1/scenarios` | L159 | L304 |

**规律**：V2.1 锚点 = 实际行号 **−1**；V2.3 对 V2.1 的偏移是 §4.1 **+12**、§4.2/§4.3/§4.4 **+145**（后三者同偏移，因为 V2.3 在 §4.1 之后插入了 §4.1.1）。

**影响**：照旧锚点复核的人会看到**相邻段落**（例如按"§4.2 L135 = `generation_artifact`"去查 V2.1，看到的是 `generation_run` 行），在 V2.3 下更是完全对不上。
**处置**：本轮**不批量改写历史文本**（append-only 纪律），只做三件事——① 修正我自己新写的两行里引用的 V2.1 行号；② 在 OpenAPI 头部与制品清单 `x-evidence` 里加"**请先 +1** 再按 V2.1 复核"的说明；③ 在 `contract-specs/README.md` §11 留下上面这张勘误表 + 重算规则，供后续机械重算。

## 6. 契约声明 vs 实现形状（同一提交内的对照）

| 契约声明 | 实现现状 | 结论 |
|---|---|---|
| `TargetCheckResult{targetId,reachable,detail,capabilities}` | `adapter/TargetCheckResult.java` = `record(long targetId, boolean reachable, String detail, Map<MallCapability,CapabilityVerdict> capabilities)`，构造器把 null/空表归一为 `Map.of()` | **同形**；因构造器有归一逻辑，契约**故意不声明 `required`**（不发明来源未给的约束） |
| `TargetCapabilities{verdicts,declared}` | `adapter/TargetCapabilities.java:17` = `record(Map<MallCapability,CapabilityVerdict> verdicts, boolean declared)` | 同形 |
| 路由所有权（§4.1.1.2 之 6） | `adapter/TargetRoute.java:20` = `record TargetRoute(String method, String path)`（M1-9 ② 泳道新增，**尚未合并、尚未复核**） | 契约只按 DTO 表登记；**不得**据此声称路由所有权已实现 |

## 7. 未解决 / 仍待决

- **Q12（REST 约定）未解决**：成功状态码、错误体形状、`/targets` 的动词与前缀、进度字段名——§4.1.1 覆盖的是 SPI/DTO，不覆盖 REST。本轮只把 200 响应的 `x-unspecified` 撤销，**状态码本身仍按 200 记录并指向 Q12**。
- **新增 Q16**：§4.1.1.2 之 6【新增要求】要求运行流水/报告的路由取自 `operationRoutes(config)`，但生成器对外 API 与页面**尚未规定如何暴露该路由信息**（`GET /generation-runs/{id}` 无对应字段）⇒ 需决定是否加字段（会改本 OpenAPI）。
- **Q3 降级**：合成标记的「落点」部分已被 V2.3 L675 追认；**仍未决**的只剩 `source_system` 取值（文件模式事件是否仍写 `mock-mall`）。
- **B-06 严格化未做**；**258 处裸锚点未逐条复核**（R-M1-5-1）。

## 8. 变更清单（可逐条核对）

替换名（每条都断言唯一命中）：`Y1`–`Y16`（OpenAPI）、`M1`–`M2`（制品清单 schema）、`R1`–`R4`（README Q 条）、VERSION 升版、README §11 新增；勘误轮：`E1`（修正我写的 V2.1 对应行 + 加勘误条）、`E2`（OpenAPI 头部说明）、`E3`（README §11 勘误表与规律）、`E4`（残留量化 247→258）。
合计：**22 + 4 条替换 + 1 次升版 + 1 节新增**，全部记录在 `raw/raw-sync-log-20260912.txt` 与 `raw/raw-erratum-log-20260912.txt`。

## 9. 反熵声明（Anti-Entropy Declaration）

- **加性**：`+126 / −30`；被删除的 30 行**全部是被替换的旧描述行**，可逐行核对；历史写法一律以「**以此为准作废**」+ 日期 + 指向 git 历史/`VERSION` 1.2.0 的方式标注，**没有静默改写**。
- **单一所有者**：行号权威 = 指导书（契约只**引用**）；契约目录版本唯一所有者 = `VERSION`（本次从 1.2.0 升到 1.3.0）；本次**没有新增任何字段/列的第二所有者**，也没有把实现细节写进契约（只登记 DTO 表已追认的形状）。
- **没有第二份真相**：勘误表只存在于 `contract-specs/README.md §11`（契约侧）与 `docs/acceptance/m1-5-contract-sync-20260912/`（证据侧），后者是历史证据、不是活文档，指针指向契约。
- **未触碰运行面**：未启停 8090/8091/8092；未执行任何 SQL；未跑 Maven。

## 10. 不得声称

- ❌ **M1-5 `DONE`**：还剩 B-06 严格化、258 处裸锚点逐条复核、Q12/Q16 待决；本次只是"按 §4.1.1 之 11 同步"这一项。
- ❌ **契约锚点已全部校正**：只有 11 处（上表）给出了正确行号；其余 258 处只给了**重算规则**，未验证。
- ❌ **`TargetCheckResult` 的必填性已冻结**：来源没有规定必填性，契约**故意不声明 `required`**。
- ❌ **`x-unspecified` 已清零**：见 §4 实测计数（OpenAPI 仍 70 处）。
- ❌ **路由所有权/新增要求已实现**：`TargetRoute` 属 M1-9 ② 泳道产物，**未合并、未经我复核**。
- ❌ **契约已被"冻结生效"**：OpenAPI 头部仍写 `状态：DRAFT，未经总控冻结`；本次同步**不改这个状态词**（冻结与否是另一项决策）。
- ❌ **pipeline 行为因此改变**：契约无运行时消费者（§3 的 4 处 javadoc 引用），本次改动对任何一次真实运行**没有**行为影响。

## 11. 未取证清单

1. **258 处裸锚点未逐条复核**（R-M1-5-1）；重算规则只在 §4.1/§4.2/§4.3/§4.4 上验证过，**§2/§3/§5/§6 等章节的 V2.1→V2.3 偏移未测**。
2. **未验证 V2.1 与 V2.3 在 §4.2/§4.3/§4.4 的正文是否逐字相同**：只验证了主题对应（本次勘误用的是"该行内容是什么"，不是"两版逐字相同"）。
3. **未做真集群/真运行验证**：契约声明没有运行时消费者，无法用运行证据支持。
4. **未检索契约目录之外的文档是否按旧锚点引用过**（例如其他 `docs/**` 里写"§4.2 L135"）；本轮只扫了 `contract-specs/**` 内部。
5. **未跑 Maven**（理由见 §3：无读取方）。生成器泳道的整模块 E2 完成后可作为交叉对照，但**不能替代**本项的解析器复验。

## 12. 下一步

1. 生成器泳道交付后：M1-9 ② 复核时，同时做**契约-vs-实现再对账**（`TargetRoute`/`operationRoutes` 落地可能自动消解 Q16）。
2. 独立小任务：按 §5 规则机械重算 258 处裸锚点（工具已具备：`scripts/verify-state.ps1` 第 6 步即扫描器）。
3. B-06 严格化。
4. 以上三项完成后，M1-5 才具备记 `DONE` 的条件。

## 13. 补记（工具链自纠，2026-09-12 同日）

- **判据脚本第一版自己错了两处**：① 用 PowerShell `-like` 做包含判断——`WildcardPattern` 把反引号当转义符，而锚点文本里满是反引号 ⇒ 误报 4 处 FAIL；② 把 V2.3 §4.1 L131 的期望写成 §4.1.1.1 的 `interface EventSink extends AutoCloseable {`，实际 L131 是 §4.1 骨架的 `interface EventSink {`（L192 才是追认版）。修正判据后复跑 **11/11 PASS**；失败记录保留在会话与 `raw/raw-erratum-log-20260912.txt` 前的首跑输出中。
- **证据日志第一版把 python 的 stdout 捕获进来** ⇒ Windows 下按 OEM 码页解码成中文乱码；第二版改成在 python 脚本末尾替换 `sys.stdout` ⇒ 丢掉已缓冲的 `print`，输出 **0 字节且 exit=0**（更具欺骗性）；第三版改为 **python 自己用 `encoding='utf-8'` 写文件、PowerShell 按 UTF-8 读回** ⇒ 正常。三版脚本与日志全部留档（`scripts/verify-state.ps1` 注释里写明了这两个坑）。
- **残留裸锚点数从 247 变 258 的原因**：勘误表刻意保留了 11 处旧锚点原文用于对照 ⇒ 计数上升 11，非新引入缺陷；`contract-specs/README.md` §11 已写明构成。
- **第五个坑（我自己的假阳性）**：更新看板时打印某行"尾部 40 字"，因回显截断，看起来像**表格行缺尾 `|`**；我据此在看板 §6 写下了"上一轮写入的表格行缺尾、本轮补齐"的错误结论。随后用程序化判据（全表扫描 `StartsWith('| ')` 且 `!EndsWith('|')`）复测两次，**异常行 = 0**，且那个"修复"动作本身也是空操作（`$broken.Count = 0`）⇒ 该结论**当场撤回并改写**（看板 §6 该行现记"假阳性、自我更正、未做无谓改写"）。教训与 F-27 同类：**任何"缺失/为零"的结论必须在同一对象上用程序化判据复测，不能只看回显或抽样。**
