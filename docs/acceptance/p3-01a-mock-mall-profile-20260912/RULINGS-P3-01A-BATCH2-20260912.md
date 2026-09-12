# P3-01-a 中途物裁决书（第二批）（D-139）（2026-09-12 20:4x）

- 裁决人：总控（父会话）　对象：`analytics-server/source-profiles/mock-mall.v1.json`（**已落盘，泳道仍在飞、未报告**）
- 前序：D-135（拆出 P3-01-a）、D-136（存储态已绑定、`/activate` 因画像缺失 409 = 半阻塞）

## 1. 实测事实（父侧独立测得，非转述）

| 项 | 读数 |
| --- | --- |
| 画像文件 | **3,339 B / mtime 20:48:24 / sha256 `FBA70E3C3B1D4D81…`** |
| 顶层键 | 恰为设计 §4.2 的 **9 个**：`profileVersion`／`sourceCode`／`canonical`／`eventTypeMapping`／`fieldMapping`／`enumSemantics`／`identityPolicy`／`timePolicy`／`quarantinePolicy` |
| `timePolicy` | `field = "event_time"`，`formats = ["ISO_OFFSET_DATE_TIME"]` —— **只列实测形态**（符合 D-112；真实数据 2,622,616 个 `event_time` 值 100% 为该形态） |
| `timezone`／`currency` 键 | **不存在**（符合 D-136：不得设第二 owner） |
| 词汇出处（父侧检索，含阳性对照） | `@keep` → 设计书 **:127／:148**；`HASH64` → 设计书 **:134／:135** ＋ `SourceProfileValidatorTest.java:51`；`"ANY"` → 设计书 **:136**；`ISO_OFFSET_DATE_TIME` → 设计书 **:140** ＋ 测试 `:52`；`EPOCH_MILLIS` → 设计书 **:140**（备用形态）。阳性对照 `SourceProfile` = 32 命中 ⇒ 检索有效。**结论：画像词汇源自冻结设计，非自造** |
| **校验器自判（父侧亲自调用）** | `POST /api/v1/sources/1/test` ⇒ **HTTP 200**、`ok = true`、**7/7 `passed=true`**：`profile_path_policy`／`profile_file_exists`／`profile_json_object`／`profile_source_code_matches`／`profile_profile_version_matches`／**`profile_required_top_level_keys`（「设计 §4.2 的 9 个顶层必备键齐全」）**／`status_transition_allowed` |
| 登记面 | `GET /api/v1/sources` ⇒ `id=1 / mock-mall / FILE / profilePath=analytics-server/source-profiles/mock-mall.v1.json / Asia/Shanghai / CNY / ACTIVE / 1.0 / current=true / warehousePrefix=dw` |

## 2. D-139 裁决

1. **P2-02 的依赖（「P3-01（格式列表）」）在**材料上**已具备** ⇒ P2-02 **可开工**；但**开工前必须复测该文件 sha256 未变**（泳道仍在飞，可能续写），且 P2-02 的白名单**只读**该文件、**不得**修改它。
2. **校验器口径必须如实限定** ✗：该端点 7 项均为**浅检**（路径策略／存在性／是 JSON 对象／`sourceCode` 与 `profileVersion` 与登记一致／9 个顶层键齐全／状态迁移许可）——**不校验** `timePolicy.formats` 的元素语法与顺序（D-112）、`fieldMapping` 的取值合法性、`enumSemantics` 的 `null` 语义、`identityPolicy` 的 `shape`/`surrogate` 取值合法性。⇒ **不得**把「`ok=true`」写成「画像语义已合规」，**不得**把该端点写成 E2/E3 证据。
3. **D-136 更新**：`/activate` 的 409 成因（画像文件缺失）**已消除**（`profile_file_exists` 通过）⇒ 「半阻塞」在**存储态 ＋ 校验器**两层均已不再成立。**但**泳道**尚未报告** ⇒ 本条**不构成** P3-01-a 的完成证据，**不得**声称种子源已激活（父侧**未**调用 `/activate`，激活属状态变更、不在本次只读复核范围）。
4. **未验证项（诚实申报）**：`enumSemantics` 内以 `null` 表示「已知但无规范映射」（如 `behavior.purchase`／`channel.web`／`ageGroup.20-29`）是否属设计书定义的合法表达 —— 父侧**未**读设计书对应段落，**不得**据此断言合规；由泳道在报告中**逐条给出设计书行号**后方可采纳。
5. **入账口径**：该文件**暂不**由父侧提交（泳道仍在飞）；泳道报告后，父侧复核 final sha256 再入库。

## 3. 本裁决未做／未测

未跑 Maven、未启动/重启 809x（8091 已在运行，父侧仅**只读**调用 login ＋ `GET /api/v1/sources` ＋ `POST /api/v1/sources/1/test`）、未调用 `/activate`、未改任何文件、未连集群；画像与设计书的**逐键语义**未全量比对（只做了词汇出处检索 ＋ 校验器 7 项）。
