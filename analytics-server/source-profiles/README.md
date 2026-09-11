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
