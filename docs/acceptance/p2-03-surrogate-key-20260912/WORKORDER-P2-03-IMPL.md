# P2-03 实施施工单：代理键落列与写入（2026-09-12 总控出单）

> 契约依据：`contract-specs/specs/surrogate-key.v1.json`（`DRAFT-2026-09-12`，23 向量）＋ 裁决 **D-083…D-092**（`docs/acceptance/p2-03-surrogate-key-20260912/RULINGS-20260912.md`）。
> 本单只出**施工要求**，不含实现。实施必须**在 P2-01 收工并释放 `spark-jobs/**` 之后**开始（同模块单写者）。

## 0 已实测的现状（出单时读数，执行者须**先重取**）

**`IdCodec` 调用点共 3 个文件 10 处**（`grep` 实测，非自述）：

| 文件 | 行 | 现状 |
|---|---|---|
| `spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala` | **L18 / L19** | `IdCodec.toBIGINT("rn.payload_user_id") AS user_id`、`…payload_product_id AS product_id` |
| 同上 | **L36 / L37** | **JOIN 谓词**：`LEFT JOIN … dim_user u ON u.user_id = ${IdCodec.toBIGINT("rn.payload_user_id")}`、`dim_product` 同形 |
| `spark-jobs/src/main/scala/com/graduation/analytics/sql/DimSql.scala` | **L19 / L42** | `payload_user_id AS user_id`、`payload_product_id AS product_id` |
| 同上 | **L45 / L48 / L51** | `CASE WHEN … THEN **-1** ELSE … END AS category_id / parent_category_id / brand_id` |
| `spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala` | **L118 / L119 / L120** | `val orderKey/userKey/productKey = IdCodec.toBIGINT("t.order_id"/"t.user_id"/"t.product_id")` |

**真实落点表（`spark-warehouse/` 实测）**：`dw_dwd` = `dwd_user_behavior_detail`、`dwd_order_detail`、`dwd_reject_record`；`dw_dim` = `dim_user`、`dim_product`；`dw_ods` = `ods_{behavior,product,trade,user}_event`。

**两条必须处理的冲突（本单新增裁决）**：

- **【裁 D-093】`order` 不在冻结实体枚举内。** `TradeDwdJob:118` 已在算 `orderKey`，但 `surrogate-key.v1.json` 的 `entity` 枚举为 `{user, product, category, brand, coupon}`，**无 `order`**。本轮裁决：**`order` 一律不得套用代理键算法**（否则物料的 `<entity>` 段是自造的，违背"枚举封闭"）；`orderKey` 保持现状不动，**登记为契约缺口**，若后续确需订单级稳定身份，走**契约升版**新增 `order` 实体，不得就地扩写。本任务**零改动** `TradeDwdJob`。
- **【裁 D-094】`-1` 哨兵与 D-087 冲突，须"双列并存"而非替换。** `DimSql:45/48/51` 对空/缺 `category_id`/`parent_category_id`/`brand_id` 落 `-1`。D-087 规定**代理键不得用哨兵**（`0`/`-1`），空/缺 ⇒ **NULL 键 + 记 DQ + 不丢行**。故：**旧 `*_id` 列与其 `-1` 语义保持不变**（下游 DWS/ADS 现依赖它，删除属另一任务），**新 `<entity>_key` 列**在空/缺时落 **NULL**（绝不落 `-1`）。两列语义须在表注释/文档里显式区分：`*_id` = 旧口径编码（含哨兵、已被 `IdCodec` 破坏源命名空间），`*_key` = 契约口径代理键（无哨兵、跨源不合并）。

## 1 交付要求（A1–A8）

- **A1 加法列（只加不改，先落列再写数）**：在 `dw_dwd.dwd_user_behavior_detail`、`dw_dwd.dwd_order_detail`、`dw_dim.dim_user`、`dw_dim.dim_product` 上**新增** `BIGINT` 可空列（名称 `<entity>_key`，如 `user_key`/`product_key`/`category_key`/`parent_category_key`/`brand_key`），**不删不改**任何既有列。DDL 走既有 `LocalSchemaInitJob`/建表模板的**加法分支**；若模板无法表达"仅新增列"，须显式报告并给出方案，**不得**用 `DROP`/`OVERWRITE` 重建表（真数仓 `dw_*` 有真实数据，见 D-071 零迁移前提）。
- **A2 键算自"原始 id"而非旧编码列**：新键的输入必须是 **ODS payload 里的原始 id**（`rn.payload_user_id` 等，DwdSql 现成可见），**严禁**由 `IdCodec.toBIGINT(...)` 的结果再算 —— 后者已把 `U00000001`/`O00000001` 折叠成 1，会**永久丢失源命名空间**（D-083 选 A2 的全部理由）。
- **A3 算法实现**：严格按契约 `material = <sourceCode>|<entity>|<normalizedRawId>`，`trim` + `Locale.ROOT` 大写 → UTF-8 → SHA-256 → 取前 8 字节 → **清符号位** → `0→1` → `BIGINT ∈ [1, 2^63-1]`。`sourceCode` 取**本运行源**（与 P2-07 的同一源身份所有者，不得另建解析链）。实现须集中在一处（一个对象/函数），DWD/DIM 均调用它。
- **A4 空/缺/不可解析**：⇒ 该行**新键列 NULL**，**不丢行**，并向既有质量通道记一条（`dw_dwd.dwd_reject_record` 或 `data_quality_result`，按现有通道语义择一并说明）。
- **A5 JOIN 一致性（本轮不改语义，只记风险）**：`DwdSql:36/37` 现按 `IdCodec.toBIGINT(...)` 连 `dim_*`。本轮**保持该 JOIN 原样**（改 JOIN 属 P2-04 的 DWD 切换）；但须在报告里写明：**新键列与旧编码列在同一行内共存，故两表 JOIN 目前仍走旧口径**，并给出"若改走 `*_key` 是否等价"的**实测对照**（同一批 55 条数据上两种 JOIN 的行数对比），等价性未实测前**不得**改。
- **A6 测试（E2）**：① 契约 23 向量中 `entity ∈ {user, product}` 的向量在 Scala 侧复算**全部命中**（含 `keyHex16`/`keyBigint` 与 `expect=NULL_KEY` 三条）；② `category`/`brand`/`coupon` 向量复算命中（**仅单测覆盖**，见 A7）；③ 空/缺 ⇒ NULL 且不丢行（负例）；④ 同一三元组恒同键、不同三元组不同键（幂等与单射）；⑤ 旧列与新列**同表共存**且旧列取值不变（防回归）。
- **A7 真链边界（必须显式声明未取证）**：当前 landing 实测 `category_id`/`brand_id` **零命中** ⇒ `dim_product` 的类目/品牌键路径**在真实 55 条链上不可达**，只有单测能覆盖；`coupon` 实体同样无真实数据。⇒ 报告须写"真链覆盖：user/product ✅；category/brand/coupon **未取证（无数据）**"。
- **A8 收尾与顺序（D-090）**：① 加法列 → ② 实现 → ③ **T2 55 条真实链复跑**（新 jar）→ ④ 之后方可在 **P2-03-b** 删除 `IdCodec`。本任务**严禁**删除 `IdCodec.scala` 或 `IdCodecSpec.scala`。

## 2 硬约束

- 模块单写者：本任务写 `spark-jobs/**`；**开工前必须确认 P2-01 已收工**。禁并发 Maven（同模块）。
- **不改 `contract-specs/**`**（实现若发现契约有误 ⇒ 停下报告，走契约升版）；不改看板；不做 git 写操作。
- **不得**运行会写 `spark-warehouse/dw_*.db` 的重活；真实链只在明确批准的 55 条口径下跑。
- 以下情况**停下报告**：① 需重建/覆盖 `dw_*` 表；② `sourceCode` 拿不到唯一值；③ JOIN 等价性实测不等价；④ 需改 `analytics-server/**`；⑤ P2-01 尚未收工。

## 3 已知的、与本任务直接相关的既有事实（避免重复发现）

- `P2-03` 取证已记录：`IdCodec` 破坏前缀命名空间（`U00000001`/`O00000001` 均 → 1）；`product_id` 存在**两个不相交域**（目录 `1001` vs 19 位雪花，交集 0）⇒ 换源/换商城后**必须**靠新键，旧编码列不可作为跨源身份。
- `event_id`/`behavior_id` 的单一所有者是 `DwdSql.scala:31`，**本任务不动**（D-055）。
- 出生证：`surrogate-key.v1.json` 的 23 向量已由总控**独立重算**复核（并因此查出泳道草案的 `key_hex8` 缺"清符号位"与散文空值记号两处载体缺陷）⇒ 测试夹具**必须**以契约文件为准，**不得**引用泳道 `raw/draft-vectors.tsv`。
- 期望产出：`docs/acceptance/p2-03-surrogate-key-20260912/IMPL-REPORT.md`（含 E1/E2/E3 证据与 A6 逐条读数）＋ `raw/` 原始日志。

---

## 4 补记：D-095 裁决（2026-09-12 20:07，总控；**上文原文全部保留，本条以此为准**）

**触发**：本单 §0【裁 D-093】写「本任务**零改动** `TradeDwdJob`」，而 §1 **A1 的加法列表含 `dw_dwd.dwd_order_detail`** —— 订单明细的 `user_key`／`product_key`／`category_key` 只能在 `TradeDwdJob` 内计算 ⇒ **单内自相矛盾**，实施者无法同时满足。20:0x 读实施泳道工作区 diff 后裁决：

1. **D-093 口径收窄**为：「**不得新增 `order_key`**（`order` 不在契约实体枚举 `{user, product, category, brand, coupon}`）＋ 既有 `orderKey`／`userKey`／`productKey` 的 `IdCodec` 旧列逻辑**逐字不变**」。「零改动 `TradeDwdJob`」更正为「**只加不改**」。
2. **允许** `orderDetailInsertSql` 增参 `sourceSystem: String`（A3 要求源编码取自既有唯一所有者 `OdsLoadSql.sourceSystemLiteral`，而 `tdw_tmp`／`OUT_SCHEMA` 不含 `source_system`）⇒ 随之的 `IdCodecSpec`／`WarehouseNamespaceSpec` 机械调用点更新**允许**。
3. **既有断言口径变更须显式交代**：`IdCodecSpec` 中 `payload_user_id` 计数 `3 → 5`（新增 `user_key`／`product_key` 各再引用一次原始 id，属 A2 的结构性后果）**予以认可**，但必须：① 在 `IMPL-REPORT.md` 单列「既有断言口径变更」条目，写清原断言／新断言／原因／原意图如何仍被保住；② 保持**精确值**断言，不得放宽为 `>=` 或删除；③ 保留新增的「旧列逐字保留」「A5 JOIN 谓词不得改动」「订单明细不得出现 `order_key`」三条断言。
4. **收尾范围硬判据**：最终 `git diff --name-only` 必须**恰好**等于下列 11 个文件（另有证据文件）：`spark-jobs/src/main/scala/com/graduation/analytics/sql/SurrogateKey.scala`（新）、`spark-jobs/src/test/scala/com/graduation/analytics/SurrogateKeyVectorSupport.scala`（新）、`spark-jobs/src/test/scala/com/graduation/analytics/sql/SurrogateKeySpec.scala`（新）、`.../sql/DwdSql.scala`、`.../sql/DimSql.scala`、`.../job/TradeDwdJob.scala`、`.../job/LocalSchemaInitJob.scala`、`warehouse/ddl/01-dwd.sql`、`warehouse/ddl/02-dims.sql`、`.../test/.../IdCodecSpec.scala`、`.../test/.../WarehouseNamespaceSpec.scala`。**出现额外文件即越界**，须回退而非解释。
5. **不变项**：禁删 `IdCodec.scala`／`IdCodecSpec.scala`（A8）；禁改 `contract-specs/**`；禁改看板；禁 git 写操作；Java 侧对账按 **P2-03-j** 放行口径（只新增 `platform-common` 文件、运行时读契约向量、不内嵌期望值）。
6. **A5／A7 口径不变**：JOIN 本轮**不得**改动，等价性对照未实测即写「未实测」；真链覆盖须原样写明「user/product ✅；category/brand/coupon **未取证（无数据）**」并附命令、输出与**阳性对照**（陷阱 #54）。
7. **父侧独立核对（20:0x 只读，非复现）**：`srcSys` 定义在 `WarehouseNamespaceSpec.scala:224`（`:252` 引用可编译）；`orderDetailInsertSql` 调用点 **4 处全部同步**（`TradeDwdJob.scala:81/128`、`IdCodecSpec.scala:78/112`、`WarehouseNamespaceSpec.scala:252`，其余命中均为文档）；三个新文件确实**运行时读** `contract-specs/specs/surrogate-key.v1.json`（`SurrogateKeyVectorSupport.scala:22`）且 `SurrogateKeySpec.scala:16` 自述**不内嵌期望值**。
