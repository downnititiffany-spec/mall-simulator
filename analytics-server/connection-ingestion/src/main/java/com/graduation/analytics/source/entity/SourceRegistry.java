package com.graduation.analytics.source.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 源登记（P1-03，表 {@code source_registry} 由 V16 迁移建立，本类只映射、不改表）。
 *
 * <p>定位（《分析平台商城无关化改造设计》§4.1）：**源身份与源词汇**的唯一所有者。
 * 平台不再把某个具体商城写进代码，而是把「有哪些源、每个源的画像文件在哪」作为数据登记；
 * 每次运行用的参数仍归 {@code runtime_profile}（本表不承接运行参数）。</p>
 *
 * <p>术语与状态口径见《开发过程事实与决策记录》D-035：</p>
 * <ul>
 *   <li><b>读取层</b>接受 {@code DRAFT/ACTIVE/PAUSED/DISABLED} 四值并集——库里存在历史值时不得报错；</li>
 *   <li><b>写入层</b>只写三值：create → {@code DRAFT}（或缺省即 DRAFT，或显式 {@code PAUSED}）、
 *       activate → {@code ACTIVE}、pause → {@code PAUSED}。{@code DISABLED} 本期不实现（不发明无消费者的状态）；</li>
 *   <li><b>「当前激活源」不在本表</b>：它是唯一 ACTIVE {@code runtime_profile} 行的 {@code source_id}
 *       （见 {@link com.graduation.analytics.source.mapper.ActiveSourceBindingMapper}）。
 *       不新增 {@code is_current}/{@code current_since} 列，也不新增单例表——那样会出现第二个所有者。</li>
 * </ul>
 *
 * <p><b>不含凭据</b>：本表没有 credential 列，源画像只描述语义映射；
 * 访问具体商城所需的凭据属运行环境（{@code runtime_profile.credential_ref}）。</p>
 */
@Data
@TableName("source_registry")
public class SourceRegistry {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_DISABLED = "DISABLED";

    /** 读取层接受的状态并集（V16 的 CHECK 约束口径）——读到历史值不得报错 */
    public static final Set<String> READABLE_STATUSES =
            Set.of(STATUS_DRAFT, STATUS_ACTIVE, STATUS_PAUSED, STATUS_DISABLED);

    /** 写入层允许出现的状态（DISABLED 本期不实现，故不在其中） */
    public static final Set<String> WRITABLE_STATUSES =
            Set.of(STATUS_DRAFT, STATUS_ACTIVE, STATUS_PAUSED);

    /** create 允许显式指定的状态：ACTIVE 只能经 activate 得到（activate 是唯一带画像校验的入口） */
    public static final Set<String> CREATABLE_STATUSES = Set.of(STATUS_DRAFT, STATUS_PAUSED);

    public static final String INGEST_MODE_FILE = "FILE";

    /** 本期唯一实现的接入方式（设计 §4.1：FILE 为本期唯一实现，JDBC/HTTP 预留） */
    public static final Set<String> SUPPORTED_INGEST_MODES = Set.of(INGEST_MODE_FILE);

    /** 预留接入方式：登记层 fail-closed 拒绝，不假装支持（未实现的采集链路不写入登记） */
    public static final Set<String> RESERVED_INGEST_MODES = Set.of("JDBC", "HTTP");

    /** source_code 形状（V16 的 uk_source_registry_code 注释口径：小写字母开头，允许小写字母/数字/连字符） */
    public static final String SOURCE_CODE_PATTERN = "[a-z][a-z0-9-]{1,63}";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 源编码：创建后**不可修改**（改它等于换了一个源，历史语义会错位） */
    private String sourceCode;

    private String displayName;

    /** 接入方式，本期仅 FILE */
    private String ingestMode;

    /** 源画像文件路径：**仓库相对**（严格策略见 SourcePathPolicy），不是本机绝对路径 */
    private String profilePath;

    private String timezone;

    /** ISO 4217 三字母币种 */
    private String currency;

    private String status;

    /** 画像文件的声明版本，必须与画像文件内的 profileVersion 一致才允许激活 */
    private String profileVersion;

    /**
     * 数仓命名空间前缀（P2-07，列 {@code warehouse_prefix} 由 V18 迁移新增）：
     * 本源的五个层库名（{@code <prefix>_ods} 等）由它派生。
     *
     * <p><b>为什么在源级而不在 {@code runtime_profile}</b>（D-070/D-071）：库名是"哪个源的数据"，
     * 属源身份；运行参数（landing、激活态）才是环境的。原先它在 {@code runtime_profile}
     * 里与"当前源"分居两表 ⇒ 一次切换源会连库名一起换掉，历史数据与库名错位。
     * 取值规则本体不在本模块：唯一所有者是
     * {@link com.graduation.analytics.warehouse.WarehouseNamespace}（规格
     * {@code contract-specs/specs/warehouse-namespace.v2.json}），本字段只存结果。</p>
     *
     * <p>写入侧拒绝形状非法值（{@code WAREHOUSE_PREFIX_*} 四码）；{@code create} 另要求**必填**
     * （缺省不代替填 —— 缺省会让忘记填的源静默落进别人的库，见 V18 注释）。</p>
     */
    private String warehousePrefix;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
