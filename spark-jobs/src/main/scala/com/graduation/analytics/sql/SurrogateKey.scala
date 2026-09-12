package com.graduation.analytics.sql

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * 维度实体代理键（surrogate key）的**唯一**派生规则所有者（P2-03 实施）。
 *
 * 语义本体**不在本文件**：本对象是 `contract-specs/specs/surrogate-key.v1.json`
 * （裁决 D-083…D-092 的载体，当前 `status = DRAFT-2026-09-12`）的**薄实现**。
 * 规则一旦分歧，**以契约文件为准**；本文件只做机械实现，不得在此新增/放宽/替换规则。
 *
 * 规则（逐字对应契约 `rule` 段）：
 *  1. `material = <sourceCode>|<entity>|<normalizedRawId>`（D-083，材料取 A2）
 *  2. `normalized = trim(rawId).toUpperCase(Locale.ROOT)`，编码 UTF-8；
 *     **不做** NFKC/内部空白压缩/字母剥离/全角半角转换（D-084）
 *  3. `digest = SHA-256(UTF-8(material))`
 *  4. `key = 前 8 字节按大端无符号解释 → 清符号位（b[0] &= 0x7F）→ 为 0 则取 1`
 *     ⇒ 恒在 `[1, 2^63-1]`，**永不等于** `-1`（unknown 哨兵，见 D-085/D-087）
 *  5. `rawId` 为 NULL / 空串 / 全空白 ⇒ **NULL 键**，不生成哨兵键、不丢行（D-087）
 *
 * 为什么 `sourceCode` 取**行内** `source_system` 列而不是另建解析链：该列的值由平台参数通道
 * `--sourceSystem=<source_registry.source_code>` 注入（`OdsLoadSql.ArgSourceSystem`，D-056），
 * 即 P2-07 已冻结的**同一源身份所有者**；代理键派生只是消费它，不重复拥有它。
 *
 * 与旧口径 `IdCodec` 的关系：`IdCodec`「剥字母前缀 → BIGINT」摧毁前缀命名空间
 * （`U00000001` 与 `O00000001` 都归一为 `1`），本对象**取代**其身份语义；但按 D-090，
 * 旧口径的删除属后续任务（P2-03-b），本轮两列并存、`IdCodec` 仍在主路径上承载行为。
 */
object SurrogateKey {

  /** 契约 `rule.entityEnum`：固定枚举，禁止就地扩写（新增实体须走契约升版） */
  final val EntityEnum: Set[String] = Set("user", "product", "category", "brand", "coupon")

  /** 材料分隔符（契约 `rule.material`） */
  final val Separator: String = "|"

  /** 首字节清符号位掩码：`b[0] &= 0x7F`（D-085） */
  final val SignBitMask: Int = 0x7F

  /**
   * 64 位键值清符号位掩码 = `Long.MaxValue`（0x7FFF…FF，二进制 63 个 1）。
   *
   * **实测缺陷（第一轮 E2）**：早先误用 `SignBitMask.toLong`（= `0x7F`，只有 7 个 1）去 AND，
   * 结果把键值的高 57 位全部抹掉 → V01 算出 `0x2e`(=46) 而非契约 `0x21f71a4b7c67bfae`。
   * 「清符号位」在 64 位整数上**只清最高 1 位**，不是「保留低 7 位」——两者极易混。
   */
  final val SignBitClearMask: Long = Long.MaxValue

  /** 全零摘要时的替代键值（D-085：`0 → 1`） */
  final val ZeroMapsTo: Long = 1L

  /** 契约算法名（源画像 `identityPolicy.*.surrogate` 唯一允许值，D-089） */
  final val AlgorithmName: String = "HASH64"

  /**
   * 归一化：去首尾空白 + **文化无关**大写（D-084）。
   *
   * 用 `String.trim`（去 ≤ U+0020 的字符）显式对齐 Spark SQL 侧 `trim()` 的语义；
   * `toUpperCase(Locale.ROOT)` 是硬要求——无参 `toUpperCase()` 在土耳其语区域会把 `i` 变成 `İ`，
   * 与 Scala/Spark 侧不一致（只读取证 F-50/F-51 已记录该风险）。
   *
   * @return 归一化后的字符串；输入为 null 时返回 null
   */
  def normalize(rawId: String): String =
    if (rawId == null) null else rawId.trim.toUpperCase(Locale.ROOT)

  /** 归一化后是否为「空/缺」（NULL / 空串 / 全空白）⇒ 按 D-087 不生成键 */
  def isMissing(normalized: String): Boolean = normalized == null || normalized.isEmpty

  /**
   * 材料字符串：`<sourceCode>|<entity>|<normalizedRawId>`。
   *
   * 单射性前提（契约 `rule.injectivePrecondition`）：`sourceCode`/`entity` 不含 `|`，
   * `rawId` 位于**末位**且归一化后原样拼接 —— 故 `A|B` 这类内含分隔符的 id 也**不 split、不截断**。
   */
  def material(sourceCode: String, entity: String, normalizedRawId: String): String =
    s"$sourceCode$Separator$entity$Separator$normalizedRawId"

  /** 材料文本的 UTF-8 字节 */
  def materialUtf8(sourceCode: String, entity: String, normalizedRawId: String): Array[Byte] =
    material(sourceCode, entity, normalizedRawId).getBytes(StandardCharsets.UTF_8)

  /** `SHA-256(UTF-8(material))` 全文摘要（32 字节；契约 `rule.digest`） */
  def sha256(sourceCode: String, entity: String, normalizedRawId: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(materialUtf8(sourceCode, entity, normalizedRawId))

  /**
   * 从**已算好的摘要**派生键值：取前 8 字节 → 清符号位 → 全零取 1（D-085）。
   *
   * 与 `shaHex16 → key` 是**同一函数的两段**：SQL 侧走 `hex(sha2(...))` 只能拿到十六进制文本，
   * 故 `keyFromDigestHex` 必须与 `keyFromDigest` 逐位一致（由向量对账测试钉住）。
   */
  def keyFromDigest(digest: Array[Byte]): Long = {
    require(digest != null && digest.length >= 8, "SHA-256 摘要至少 8 字节")
    // 首字节先 `& 0x7F` 再拼进 64 位值——等价于「拼完后只清最高位」，
    // 但把规则写成契约里那句 `b[0] &= 0x7F`，避免再出现「掩码位宽」这类误读。
    var v = (digest(0) & SignBitMask).toLong
    var i = 1
    while (i < 8) { v = (v << 8) | (digest(i) & 0xFFL); i += 1 }
    if (v == 0L) ZeroMapsTo else v
  }

  /**
   * 摘要前 8 字节的**原值**十六进制小写（未清符号位）。
   * 契约列名 = `digestPrefixHex16`；与 `keyHex16` 在首字节 ≥ 0x80 时**故意不同**（载体口径差异，D-088）。
   */
  def digestPrefixHex16(digest: Array[Byte]): String = {
    require(digest != null && digest.length >= 8, "SHA-256 摘要至少 8 字节")
    val sb = new StringBuilder(16)
    var i = 0
    while (i < 8) { sb.append(f"${digest(i) & 0xFF}%02x"); i += 1 }
    sb.toString
  }

  /** 契约列名 = `keyHex16`：清符号位后键值的 16 位十六进制小写（等价于 `keyBigint` 的无符号十六进制） */
  def keyHex16(key: Long): String = f"$key%016x"

  /** 由契约列 `keyHex16`（16 位十六进制）还原 `keyBigint` */
  def keyFromHex16(hex16: String): Long =
    java.lang.Long.parseUnsignedLong(hex16.trim.toLowerCase(Locale.ROOT), 16)

  /**
   * 端到端派生（Java/Scala 内存侧唯一入口）。
   *
   * @return `Some(key)`；若 `rawId` 为 NULL / 空串 / 全空白 ⇒ `None`（**不生成键**，D-087）
   */
  def derive(sourceCode: String, entity: String, rawId: String): Option[Long] = {
    require(sourceCode != null && sourceCode.nonEmpty, "sourceCode 不得为空（材料首段）")
    require(entity != null && entity.nonEmpty, "entity 不得为空（材料中段）")
    val n = normalize(rawId)
    if (isMissing(n)) None else Some(keyFromDigest(sha256(sourceCode, entity, n)))
  }

  // ------------------------------------------------------------------
  // SQL 侧：与内存侧逐位同值
  // ------------------------------------------------------------------

  /**
   * 条件包装：**任一个** guard **谓词**成立 ⇒ 整式 NULL（**行保留**，键列为 NULL），
   * 供 `*_key` 列与 `LEFT JOIN ... ON` 谓词共用（D-087）。
   *
   * **实测缺陷 1（第一轮 E2，三个用例同时报 `PARSE_SYNTAX_ERROR`）**：早先写成
   * `${guards.mkString(", ")} IS NULL` ⇒ 两个 guard 时生成
   * `CASE WHEN u.source_system, trim(...) IS NULL THEN …` —— **逗号不是 SQL 逻辑或**，
   * 解析器在逗号处直接失败。
   *
   * **实测缺陷 2**：把 guard 定义成「表达式、由本方法补 `IS NULL`」后，空串判据
   * `trim(x) = ''` 会被补成 `trim(x) = '' IS NULL` —— 语义与可读性都不对。
   *
   * 故 guard 一律是**完整布尔谓词**（`x IS NULL`、`trim(x) = ''`…），本方法只负责用
   * `OR` 连接。谓词由调用方经 {@link blankGuard} 等构造，判据只有一份定义。
   *
   * @param expr   被包装的取值表达式
   * @param guards 完整布尔谓词（**谓词**，不是裸表达式；例如 `x IS NULL`）
   */
  def nullSafe(expr: String, guards: String*): String = {
    require(guards.nonEmpty, "nullSafe 至少需要一个 guard（无 guard 时不该调用 nullSafe）")
    val cond = guards.mkString(" OR ")
    s"CASE WHEN $cond THEN NULL ELSE $expr END"
  }

  /**
   * 单个业务键的代理键 SQL 表达式（**不含** NULL 包装，也不含枚举校验）。
   *
   * **为什么「不含枚举校验」是刻意的**：契约 23 向量里 V06(`order`)/V20(`payment`)/V21(`refund`)
   * 用的是**枚举外实体**——契约要求它们的哈希**照算**（值已写进 `keyBigint`），
   * 而生产落列必须拒绝它们（D-083 枚举封闭）。这两件事必须分开：
   * 本方法是**纯公式**，枚举护栏只在 {@link keyExpr} 与 {@link literalSource} 出口施加。
   * 第一轮 E2 我把二者混在一个方法里，结果是「要测契约向量就必须放松生产护栏」——
   * 那是拿护栏换绿灯，已拆开。
   *
   * 与内存侧的关系：`substr(sha2(<material>, 256), 1, 16)` 即 `sha256(...)` 的十六进制**前 16 位**，
   * 经 {@link clearSignBit} 后 = `keyFromDigest(摘要)`（清符号位 + 全零取 1）。
   * 「SQL 表达式 == 内存实现」由向量对账测试在真实 Spark 上逐向量钉住，不靠本注释声称。
   *
   * **实测缺陷（第一轮 E2，V01 实测 SQL 键 `3616784362992907362` ≠ 契约 `2447453834011197358`）**：
   * 早先写成 `hex(sha2($material, 256))` —— 而 Spark 的 `sha2(x, 256)` **本身就返回十六进制字符串**
   * （不是二进制），再套一层 `hex()` 等于把该字符串的 **ASCII 字节**又编码了一遍：
   * 期望前缀 `21f71a4b7c67bfae` 被变成 `3231663731613462`，而 `0x3231663731613462` 正是
   * 字符 `'2','1','f','7','1','a','4','b'` 的 ASCII —— 读数与推断逐字符吻合。
   * 修正：去掉外层 `hex()`，直接对 `sha2(...)` 的十六进制串取 `substr`。
   *
   * @param sourceExpr 源编码表达式（ODS 行内 `source_system`，D-056 注入）
   * @param entity     实体枚举值（`user`/`product`/…）；本方法**不校验**枚举，见上
   * @param rawIdExpr  原始业务键表达式（ODS `payload_*_id`，**必须是原始字符串 id**，
   *                   严禁传入 `IdCodec` 已折叠命名空间的结果，见 D-083/A2）
   */
  def keyFormula(sourceExpr: String, entity: String, rawIdExpr: String): String = {
    val normalized = normalizedRawId(rawIdExpr)
    val material = s"concat(cast($sourceExpr AS STRING), '$Separator', '$entity', '$Separator', $normalized)"
    val digestHex = s"sha2($material, 256)"
    val raw16 = s"substr($digestHex, 1, 16)"
    s"GREATEST(cast(conv(${clearSignBit(raw16)}, 16, 10) AS BIGINT), $ZeroMapsTo)"
  }

  /**
   * **生产入口**：公式 + 枚举护栏（不含 NULL 包装，供 {@link nullSafe} 组合）。
   *
   * @throws IllegalArgumentException entity 不在契约枚举内（D-083）
   */
  def keyExpr(sourceExpr: String, entity: String, rawIdExpr: String): String = {
    require(EntityEnum.contains(entity),
      s"entity 不在契约枚举内：$entity（D-083 枚举封闭；order/category 之外的新增实体须走契约升版，不得就地扩写）")
    keyFormula(sourceExpr, entity, rawIdExpr)
  }

  /**
   * SQL 侧「清符号位 + 全零取 1」：与内存侧 {@link keyFromDigest} 逐步对应。
   *
   * **实测缺陷（第一轮 E2，23 向量里 11 条 SQL 侧得 NULL）**：我先前只做了 `sha256 → 前 16 位十六进制
   * → conv(…,16,10)`，**漏掉了契约规则里的 `b[0] &= 0x7F` 这一步**。后果不是「略有偏差」
   * 而是**整条键丢失**：首字节 ≥ 0x80 时该 16 位十六进制 ≥ 2^63，`conv(...,16,10)` 溢出
   * 有符号 BIGINT ⇒ Spark 返回 **NULL**。实测命中 11 条（V04/V06/V07/V08/V11/V17/V18/V19/V20/V22/V23），
   * 与「首字节 ≥ 0x80」的向量集合逐条吻合。
   *
   * 逐段对应关系（左=SQL，右=内存侧 `keyFromDigest`）：
   *  - `substr(sha2(…),1,2)` → `digest(0)`
   *  - `& 127`               → `digest(0) & 0x7F`（`SignBitMask`）
   *  - `cast(… AS BIGINT)`   → 位与要求整型；实测 `conv(x,16,10)` 返回 **DOUBLE**，
   *                            直接 `& 127` 会报 `DATATYPE_MISMATCH … ("DOUBLE" and "INT")`
   *  - `lpad(hex(…),2,'0')`  → 十六进制两位补零（`hex()` 对 <16 的值只出 1 位）
   *  - `|| substr(…,3)`      → 低 7 字节原样保留
   *  - `GREATEST(…, 1)`      → `if (v == 0L) ZeroMapsTo`（`ZeroMapsTo = 1`）
   *
   * **两次实测的返回类型陷阱（都报 `DATATYPE_MISMATCH`，都不是语义问题）**：
   * ① `conv(x, 16, 10)` 返回 **DOUBLE** ⇒ 位与前必须 `cast(… AS BIGINT)`；
   * ② 同一函数在**内层**（toBase=10）返回 **STRING**（外层才映射为 DOUBLE）
   *    ⇒ `GREATEST(conv(…), 1)` 报 `["STRING","INT"]`，必须写成
   *    `GREATEST(cast(conv(…) AS BIGINT), 1)`，即 **cast 在内、GREATEST 在外**。
   * 「SQL 与内存逐向量同值」由向量对账测试在真实 Spark 上钉住，不靠本注释声称。
   */
  private def clearSignBit(raw16Expr: String): String = {
    val firstByte = s"cast(conv(substr($raw16Expr, 1, 2), 16, 10) AS BIGINT) & $SignBitMask"
    s"concat(lpad(hex($firstByte), 2, '0'), substr($raw16Expr, 3))"
  }

  /**
   * 归一化表达式：`upper(trim(cast(rawId AS STRING)))`（契约 `rule.normalize`）。
   *
   * 单独抽出是为了让**键值表达式**与**NULL 判据**共用同一段文本——
   * 若判据和取值各写一份 `upper(trim(...))`，将来改归一化规则时极易只改一处。
   */
  private def normalizedRawId(rawIdExpr: String): String =
    s"upper(trim(cast($rawIdExpr AS STRING)))"

  /**
   * 空判据表达式：归一化后为 NULL **或空串**。
   *
   * **实测缺陷（第一轮 E2，`空串/仅空白/NULL` 用例列 0 实测 `false was not equal to true`）**：
   * D-087 要求「空 / 缺 ⇒ NULL 键」，但 `'' IS NULL` 与 `'   ' IS NULL` **都为假**，
   * 于是空串行会算出一个**真实存在的键**（所有无 id 行还会因此合并成一条，正是契约
   * `emptySemantics.forbidden` 明令禁止的形态）。仅 guard `IS NULL` 漏掉了空串与全空白。
   *
   * 写法说明：`trim(...)` 的结果再判 `= ''` 即覆盖「空串」与「全空白」两种；
   * 与 `IS NULL` 取或，覆盖第三种（NULL 列 / NULL 字面量）。
   */
  private def blankGuard(rawIdExpr: String): String =
    s"(trim(cast($rawIdExpr AS STRING)) IS NULL OR trim(cast($rawIdExpr AS STRING)) = '')"

  /**
   * 代理键 SQL 表达式（带 NULL 传播）：任一入参 NULL ⇒ NULL 键、**不丢行**（D-087）。
   *
   * 为什么 guard 里用 `trim(...)` 而不是原列：`rawId` 为**空串 / 全空白**时同样必须得 NULL
   * （D-087），而 `'' IS NULL` 为假 ⇒ 只 guard NULL 会算出一个「空 id 的键」，
   * 使所有无 id 行合并成一条（契约 `emptySemantics.forbidden` 明令禁止）。
   *
   * **`sourceExpr` 必须是列表达式，不能是字符串字面量**（第一轮 E2 实测缺陷）：传 `'mock-mall'`
   * 会生成 `CASE WHEN 'mock-mall' IS NULL THEN …`，Spark 解析器在此处直接
   * `PARSE_SYNTAX_ERROR: Syntax error at or near ''mock-mall''`。
   * 源编码是常量（如 `dwd_order_detail` 的临时视图不带 `source_system`）时必须用下面的
   * `literalSource` 显式声明常量语义——**常量源不做 NULL 传播**：整条管道缺源编码是
   * 上游参数通道故障（D-056），应由上游抛错，不该在这里静默降级成 NULL 键。
   *
   * @param sourceExpr 源编码**列**表达式（ODS 行内 `source_system`，D-056 注入）
   * @param entity     实体枚举值（`user`/`product`/…，D-083 封闭枚举）
   * @param rawIdExpr  原始业务键表达式（ODS `payload_*_id`，**必须是原始字符串 id**，
   *                   严禁传入 `IdCodec` 已折叠命名空间的结果，见 D-083/A2）
   */
  def toBIGINT(sourceExpr: String, entity: String, rawIdExpr: String): String =
    nullSafe(keyExpr(sourceExpr, entity, rawIdExpr), s"$sourceExpr IS NULL", blankGuard(rawIdExpr))

  /**
   * `sourceCode` 是**常量**（非行内列）时的入口：渲染成 SQL 字符串字面量后交给 {@link keyExpr}。
   *
   * 与列式入口的语义差异（刻意，非遗漏）：**源编码**不做 NULL 传播、只做非空校验——
   * 常量源的「本行为空」不存在，整体缺失即管道故障（D-056），空值在此抛
   * `IllegalArgumentException`。
   *
   * **但 raw id 侧的空语义必须与列式入口一致**：空串 / 全空白 / NULL ⇒ 键为 NULL
   * （D-087 + 契约 `emptySemantics`）。第一轮 E2 实测该用例列 0（空串）得到**非 NULL**，
   * 就是因为常量源入口整个绕过了判空。故此处也套 {@link nullSafe}，
   * 判据复用同一个 {@link blankGuard}——判据只有一份定义，两个入口不可能各写一套。
   *
   * @param sourceCode 源编码**常量**（来自 `--sourceSystem`，D-056）
   */
  def literalSource(sourceCode: String, entity: String, rawIdExpr: String): String = {
    require(sourceCode != null && sourceCode.trim.nonEmpty,
      s"sourceCode 常量不得为空：必须来自 --$ArgSourceSystem 参数通道（D-056）")
    nullSafe(keyExpr("'" + sourceCode.replace("'", "''") + "'", entity, rawIdExpr),
      blankGuard(rawIdExpr))
  }

  /** 源编码注入参数名（与 `OdsLoadSql.ArgSourceSystem` 同一字面量；此处只作报错文案） */
  private val ArgSourceSystem = "sourceSystem"

  /**
   * P2-03 新增列清单的**唯一所有者**（建表 DDL、逐列对账、测试三处共用，避免多处漂移）。
   *
   * 为什么放在算法所有者里而不是 `DimSql`：这份清单是「代理键落在哪些表的哪些列上」的
   * 施工面定义，与算法同属一个契约面；放在某个 SQL 模板对象里会让建表作业反向依赖模板。
   *
   * `category` 同时承载二级分类（`payload_category_id`）与一级分类（`payload_parent_category_id`）
   * ——二者是**不同的 raw id**，故各自成键；这不是「新增实体」，不越 D-093 的枚举封闭。
   * `dwd_order_detail` **没有** `order_key`：`order` 不在契约枚举（D-093）。
   */
  val KeyColumns: Map[String, Map[String, Seq[(String, String)]]] = Map(
    "dwd" -> Map(
      "dwd_user_behavior_detail" -> Seq(
        "user_key" -> "user", "product_key" -> "product", "category_key" -> "category"),
      "dwd_order_detail" -> Seq(
        "user_key" -> "user", "product_key" -> "product", "category_key" -> "category")),
    "dim" -> Map(
      "dim_user" -> Seq("user_key" -> "user"),
      "dim_product" -> Seq(
        "product_key" -> "product",
        "category_key" -> "category",
        "parent_category_key" -> "category",
        "brand_key" -> "brand")))

  /** 平铺形式（建表/对账按 (层, 表, 列清单) 迭代） */
  def keyColumnPlan: Seq[(String, String, Seq[(String, String)])] =
    KeyColumns.toSeq.flatMap { case (layer, tables) =>
      tables.toSeq.map { case (t, cols) => (layer, t, cols) }
    }

  // ------------------------------------------------------------------
  // A4「空 id → 记一条 DQ」的记录通道：**本轮未实现，已停下报总控（D-096 第 3 条路）**。
  //
  // 为什么不在这里写：施工单 A4 只授权「向**既有**通道记一条（`dwd_reject_record` 或
  // `data_quality_result`，按现有通道语义择一并说明）」，**未授权新建任何表**。
  // 我先前的做法（新建 `dwd_surrogate_key_quality` 明细表）属越权，已按 D-096 全部撤回。
  //
  // 两条既有通道的实测不可用理由与「空 id 真实发生量」读数见
  // `docs/acceptance/p2-03-surrogate-key-20260912/IMPL-REPORT.md`「A4 记录通道：停下报告」一节，
  // 由总控裁决走 DDL/契约升版还是改由平台侧记录。
  // ------------------------------------------------------------------
}
