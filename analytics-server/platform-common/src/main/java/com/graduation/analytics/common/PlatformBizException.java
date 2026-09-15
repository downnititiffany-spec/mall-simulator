package com.graduation.analytics.common;

/**
 * 平台业务异常（M1-6 由整改前的商城命名改名而来，旧名与根因见《开发过程事实与决策记录》D-023）：
 * code 为稳定错误码（REST 返回），message 面向调用方。
 *
 * <p>命名规则：分析平台自有异常一律 {@code Platform*}——平台不隶属任何具体商城（V2.1 §3.1/§3.4-4），
 * 由 {@code PlatformMallBoundarySourcePolicyTest} 的"商城命名遗留"规则常驻钉住（守卫自身也因此
 * 要求平台主子源码里不出现旧类名，故此处只留指针、不复述那个字面量）。</p>
 */
public class PlatformBizException extends RuntimeException {

    private final String code;

    public PlatformBizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    // 常用错误码（§23.2 错误分类在平台层做完整映射）
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String PARAM_INVALID = "PARAM_INVALID";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String INTERNAL = "INTERNAL";

    // 源登记域错误码（P1-03，2026-09-11）：逐字取自任务书 §4 P1-03，
    // 与 platform-app 的 /api/v1/sources 一一对应。语义边界见《开发过程事实与决策记录》D-035 裁决 6：
    //   SOURCE_NOT_FOUND        → 404（资源不存在）
    //   SOURCE_CODE_IMMUTABLE   → 409（source_code 创建后不可改）
    //   SOURCE_PROFILE_INVALID  → 409（画像文件缺失/非法/与登记不一致）
    //   SOURCE_IN_USE           → 409（该源是当前激活源，不能暂停）
    // 状态映射的**唯一所有者**是 GlobalExceptionHandler.mapStatus，不另立异常类型或映射表。
    public static final String SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";
    public static final String SOURCE_CODE_IMMUTABLE = "SOURCE_CODE_IMMUTABLE";
    public static final String SOURCE_PROFILE_INVALID = "SOURCE_PROFILE_INVALID";
    public static final String SOURCE_IN_USE = "SOURCE_IN_USE";

    // 源绑定域错误码（P1-05，2026-09-12，加法式新增；D-037）：
    //   SOURCE_NOT_BOUND → 409（运行环境未绑定源：runtime_profile.source_id 为空时采集 fail-closed）
    // 语义：这不是"参数写错了"（400），而是**运行环境的绑定状态不满足采集前提**——与既有的
    // SOURCE_IN_USE 同属"源域的状态冲突"，故归 409，由 mapStatus 统一映射（裁决：状态映射单一所有者）。
    // 为什么不做兜底：回落 1/mock-mall、或给列加 DEFAULT 1，会把"来源不明"伪装成"来自某个源"，
    // 断点挂到错误的源名下后，切源即产生**静默少采**（D-037 裁决 2 的同一条理由，从迁移侧延伸到运行侧）。
    public static final String SOURCE_NOT_BOUND = "SOURCE_NOT_BOUND";

    // 映射 dry-run 域错误码（S2-01B，加法式新增；状态映射仍由 GlobalExceptionHandler.mapStatus 独有）：
    //   DRY_RUN_SAMPLE_NOT_FOUND → 404（受控样本引用指向的位置没有这个文件）
    //   DRY_RUN_REPORT_NOT_FOUND → 404（报告不在本进程内，或 reportId/sourceId 不匹配）
    // 语义：两者都是"你要的那个资源不存在"，与 SOURCE_NOT_FOUND 同类，故归 404；
    // 而"引用本身不被允许"（绝对路径/.. /协议前缀/符号链接）是**入参非法**，仍走 PARAM_INVALID(400)：
    // 拦截越界引用不是"资源缺失"，把两者混成一个码会让调用方分不清"路径写错了"和"文件还没生成"。
    public static final String DRY_RUN_SAMPLE_NOT_FOUND = "DRY_RUN_SAMPLE_NOT_FOUND";
    public static final String DRY_RUN_REPORT_NOT_FOUND = "DRY_RUN_REPORT_NOT_FOUND";

    // 真实采集映射域错误码（S2-02，加法式新增；状态映射仍由 GlobalExceptionHandler.mapStatus 独有）：
    //   MAPPING_PROFILE_INVALID → 409（登记画像缺失/不可读/装载失败/与登记源不一致；profile_path 违规时值脱敏）
    //   MAPPING_PROFILE_BLOCKED → 409（画像可装载但被激活门阻止：关键目标未映射，禁止拿它跑真实采集）
    // 语义：与 SOURCE_PROFILE_INVALID 同族——不是"参数写错了"（400），而是**当前状态不满足采集前提**，
    // 与 SOURCE_NOT_BOUND 一样归 409。两者都在任何写入之前抛出（连批次行都不产生），
    // 调用方拿到码即可判定"这一轮根本没开始"，不存在半成品批次。
    public static final String MAPPING_PROFILE_INVALID = "MAPPING_PROFILE_INVALID";
    public static final String MAPPING_PROFILE_BLOCKED = "MAPPING_PROFILE_BLOCKED";

    // 批次重放口径错误码（S2-02B，加法式新增；状态映射仍由 GlobalExceptionHandler.mapStatus 独有）：
    //   INGEST_BATCH_INPUT_CONFLICT → 409（同一批次已 LANDED 该文件，而本轮该文件又有新内容）
    // 语义：这不是"这一行数据坏了"（那是隔离，批次 QUARANTINED），而是**批次与输入的绑定被破坏**
    // ——同一批次不得二次消费同一输入。判据是既有唯一键 uk_batch_file(batch_id, file_path) 写下的
    // ingestion_batch_file 行，不新造指纹；已 LANDED 且无新内容属同批次同输入的幂等重放，不算冲突。
    // 处理：该文件记 errorCount ⇒ 批次 FAILED ⇒ 不产出 READY 清单（半成品不可交付），重放须开新批次。
    // 与 SOURCE_NOT_BOUND / MAPPING_* 同族：都是"当前状态不满足继续采集的前提"，故归 409。
    public static final String INGEST_BATCH_INPUT_CONFLICT = "INGEST_BATCH_INPUT_CONFLICT";

    // 映射激活生命周期错误码（S2-03，加法式新增；状态映射仍由 GlobalExceptionHandler.mapStatus 独有）：
    //   MAPPING_ACTIVATION_INELIGIBLE          → 409（报告存在但不满足激活前置：画像未通过装载 /
    //                                                有违例 / 有系统异常 / activationBlocks / capabilityGaps）
    //   MAPPING_ACTIVATION_CHECKSUM_MISMATCH   → 409（expectedProfileChecksum ≠ 该报告的 profileChecksum）
    //   MAPPING_CONTRACT_DRIFT                 → 409（当前 canonical 契约字节 ≠ dry-run 时的契约字节）
    //   MAPPING_PROFILE_CHANGED                → 409（当前待激活画像字节的 sha256 ≠ 报告的 profileChecksum）
    //   MAPPING_NOT_ACTIVE                     → 409（采集侧：该源没有已激活映射，拒绝拿"可执行画像"冒充已激活）
    //   MAPPING_ACTIVE_PROFILE_DRIFT           → 409（采集侧：已激活画像的字节与激活时钉住的 checksum 不一致）
    // 语义：六个都是**状态不满足前提**（不是参数写错 400，也不是数据坏行），与 SOURCE_NOT_BOUND /
    // INGEST_BATCH_INPUT_CONFLICT 同族，故归 409。关键取舍：激活用"比对 dry-run 时钉住的 hash"表达
    // 「预览的内容就是激活的内容」，**不重新执行一遍 dry-run 再判"差不多一样"**——重跑会引入第二个判据。
    //
    // MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE → 501（**本次装配没有激活指针持久化能力**：
    // 正常装配装 JdbcActiveMappingPointerStore（表 source_mapping_active，V21 迁移，S2-03.1 已交付），
    // 只有在元数据 mapper 扫描缺包、容器里拿不到 SourceMappingActiveMapper 时才退 fail-closed 兜底并报此码）。
    // 为什么不塞进 500：500 的语义是"服务端出错了"，而这里是"能力缺口已知且刻意 fail-closed"，
    // 必须与真实故障可区分；也不用 409（这不是调用方状态冲突，重试无意义）。
    public static final String MAPPING_ACTIVATION_INELIGIBLE = "MAPPING_ACTIVATION_INELIGIBLE";
    public static final String MAPPING_ACTIVATION_CHECKSUM_MISMATCH = "MAPPING_ACTIVATION_CHECKSUM_MISMATCH";
    public static final String MAPPING_CONTRACT_DRIFT = "MAPPING_CONTRACT_DRIFT";
    public static final String MAPPING_PROFILE_CHANGED = "MAPPING_PROFILE_CHANGED";
    public static final String MAPPING_NOT_ACTIVE = "MAPPING_NOT_ACTIVE";
    public static final String MAPPING_ACTIVE_PROFILE_DRIFT = "MAPPING_ACTIVE_PROFILE_DRIFT";
    public static final String MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE =
            "MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE";

    // M1-6 顺带清理（2026-09-11，AE-04）：这里原先还有 8 个**商城域**错误码
    // （PRODUCT_NOT_FOUND / PRODUCT_OFF_SALE / INSUFFICIENT_STOCK / ORDER_NOT_FOUND /
    //  ORDER_OWNER_MISMATCH / ORDER_STATE_ILLEGAL / REFUND_EXCEEDS_PAID / REFUND_NOT_FOUND），
    // 是整改前"平台与商城同进程"的残留：全仓引用计数为 0（平台从不抛它们），商城程序另有自己的
    // 同名常量。平台不复制、不映射具体商城的业务词汇——这类"原始 code → 规范语义"的映射属
    // 每源配置（见《分析平台商城无关化改造设计》P3 语义注册表），故直接删除而不是留在平台侧。
}