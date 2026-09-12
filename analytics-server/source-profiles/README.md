# `source-profiles/` 目录属主说明

本目录存放**源画像（SourceProfile）文件**：每个"数据来源"（商城/CSV/JDBC…）一份 JSON，
描述该源的词汇与语义（事件类型映射、字段映射、枚举语义、身份策略、时间策略、隔离策略）。

解析与校验的唯一属主是 `com.graduation.analytics.source.SourceProfileValidator`（P1-03），
根目录由配置键 `platform.source.profile-root` 决定（默认 `.` ＝仓库根，故本目录内文件的
仓库相对路径形如 `analytics-server/source-profiles/<name>.v1.json`）。
**本目录中文件的键名契约**见 `docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md` §4.2（9 个顶层必备键）。

## 当前文件及归属（别把夹具当真实画像）

| 文件 | 性质 | 属主 | 说明 |
|------|------|------|------|
| `p1-03-probe-1.v1.json` | **验收夹具**（不是真实源画像） | P1-03 | 仅用于 P1-03 的 E2/E3：键齐全、`sourceCode` 与登记一致；`SourceProfileFixtureTest` 钉住其形态 |
| `p1-03-probe-2.v1.json` | **验收夹具**（不是真实源画像） | P1-03 | 同上，仅 `sourceCode` 不同（用于"登记／文件不一致 ⇒ 不可激活"的负例） |
| `mock-mall.v1.json` | **真实源画像** | **P3-01 交付物，当前不存在** | `analytics_meta.source_registry` 的 V16 种子源（id=1，`source_code=mock-mall`）的 `profile_path` 指向本文件 |

## 由此产生的一条已知状态（不是代码缺陷）

V16 种子源（id=1）当前**不可激活**：其 `profile_path` 指向尚不存在的 `mock-mall.v1.json`，
故 `POST /api/v1/sources/1/test` 返回 `ok=false`、`/activate` 返回 409 `SOURCE_PROFILE_INVALID`。
这是 D-035 §11 的**预期行为**（"没有画像文件就不能激活"），已由 `SourceProfileFixtureTest` 钉住。

⇒ **P3-01 必须交付 `mock-mall.v1.json`**，该种子源才能激活；在此之前，任何验收都不得依赖"激活种子源"这一步
（P1-05 的 E3 已按此约束设计：它只需要"当前源绑定"这一读端口，不依赖种子源可激活）。

---

## 补记（2026-09-12，P3-01-a 泳道；**追加，不改上文**）

上文第 17 行表格中的"**P3-01 交付物，当前不存在**"与第 19–26 行的"不可激活"，
都是**落盘之前**的状态记录。按"原文留痕"原则**不作删改**，现状在此补记：

1. **`mock-mall.v1.json` 已落盘**（P3-01-a 泳道交付）：
   `3,323` 字节，`sha256 = 0bb8a05c8d5e466864dcb90b8d7b97105dc65497dc3ccb463d104f27f021170b`。
   顶层键恰为设计 §4.2 的 9 个（`profileVersion`／`sourceCode`／`canonical`／`eventTypeMapping`／
   `fieldMapping`／`enumSemantics`／`identityPolicy`／`timePolicy`／`quarantinePolicy`）。
2. **措辞按 D-136 精确化**：上文第 21 行"当前**不可激活**"应读作——
   存储态 `status = ACTIVE`（`GET /api/v1/sources/1` 实测），而端点 `POST /api/v1/sources/1/activate`
   因画像缺失返回 `409 SOURCE_PROFILE_INVALID`。**不要**用"种子源不可激活"这种笼统说法。
3. **校验器自判已通过（浅检口径）**：`POST /api/v1/sources/1/test` ⇒ HTTP 200、`ok = true`、**7/7** `passed`。
   按 **D-139 §2.2**，该端点**只做浅检**（路径策略／存在性／是 JSON 对象／`sourceCode` 与
   `profileVersion` 与登记一致／9 个顶层键齐全／状态迁移许可），
   **不校验** `timePolicy.formats` 元素语法、`fieldMapping` 取值合法性、`enumSemantics` 的 `null` 语义、
   `identityPolicy` 的 `shape`/`surrogate` 取值合法性。
   ⇒ **不得**把 `ok=true` 表述为"画像语义已合规"。
   **语义**合规证据在 `docs/acceptance/p3-01a-mock-mall-profile-20260912/`（`raw/11` 断言 I1–I9 全 PASS）。
4. **本泳道未调用 `/activate`**（D-139 §2.3：激活属状态变更，不在只读范围）⇒ 不得声称种子源已激活。
5. `timezone`／`currency` **不在画像内**（按 D-136：避免第二 owner）；二者的唯一 owner 仍是
   `source_registry.timezone`（`Asia/Shanghai`）／`.currency`（`CNY`）。
   `raw/11` 的断言 I9 已把这条钉成机器可检的约束。
6. 本目录内的 `p1-03-probe-1.v1.json`／`p1-03-probe-2.v1.json` **仍是夹具**，性质未变；
   `SourceProfileFixtureTest` 的 4 条断言在本泳道改动后仍 **4/4 通过**（`raw/13-E2-tests.log`）。

