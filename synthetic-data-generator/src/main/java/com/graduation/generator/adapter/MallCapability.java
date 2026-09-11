package com.graduation.generator.adapter;

/**
 * 目标商城"能被生成器用起来"的能力词汇（S4a）。
 *
 * <p><b>词汇从哪来</b>：前五个字面量直接取自生成器自己的迁移脚本
 * {@code db/generator/V1__generator_meta.sql} 对 {@code generator_target.capabilities} 的注释
 * （{@code product,user,behavior,order,refund}），不另造一套命名。{@code reset_state} 来自
 * 看板 §5.1 对 B-04 的裁决（"生成器以 capability 声明 {@code RESET_STATE}，仅支持的测试商城可经受保护的
 * 管理员 API 重置"）；{@code admin} 是本切片补的，因为"写商品/改价/改库存"走的是受保护的管理员接口，
 * 与公开目录读取不是同一件事。</p>
 *
 * <p><b>这不是契约</b>：契约 {@code GeneratorTarget.capabilities} 的取值明确标注未冻结
 * （{@code x-unspecified}），因此这里的字面量属于生成器侧实现词汇，**不写回** {@code contract-specs}。</p>
 */
public enum MallCapability {

    /** 公开商品目录可读（参考商城 {@code GET /api/v1/mall/products}） */
    PRODUCT("product"),

    /** 公开用户注册可用（{@code POST /api/v1/mall/users}） */
    USER("user"),

    /** 公开下单/支付/取消可用（{@code POST /api/v1/mall/orders}、{@code /orders/{id}/pay|cancel}） */
    ORDER("order"),

    /** 公开退款申请与完成可用（{@code POST /api/v1/mall/orders/{id}/refunds}、{@code /refunds/{id}/complete}） */
    REFUND("refund"),

    /**
     * 公开<b>行为埋点</b>接口可用（浏览/收藏等纯行为事件的写入接口）。
     *
     * <p>该接口在参考商城<b>当前不存在</b>（B-04），路径也未冻结，因此它的判据只能是
     * {@code config_json.behavior_path} 的声明值 + 对该路径的实测，不能由生成器猜。</p>
     */
    BEHAVIOR("behavior"),

    /** 受保护的重置接口可用（把测试商城的库存/价格等状态复位，B-04 要求只在声明支持时使用） */
    RESET_STATE("reset_state"),

    /** 受保护的管理员接口可用且凭据有效（建商品、改价、改库存） */
    ADMIN("admin");

    private final String key;

    MallCapability(String key) {
        this.key = key;
    }

    /** 对外 JSON 键名（与迁移脚本注释里的小写词汇一致） */
    public String key() {
        return key;
    }
}
