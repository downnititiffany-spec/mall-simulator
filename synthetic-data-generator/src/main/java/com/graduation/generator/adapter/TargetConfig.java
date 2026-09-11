package com.graduation.generator.adapter;

/**
 * 探测适配器的输入：目标商城配置里与"能不能用"有关的那几列（§4.2 {@code generator_target}）。
 *
 * <p>{@code credentialRef} 只是<b>引用</b>（环境变量名/密钥别名）——适配器自己按引用去取值，
 * 本记录绝不承载明文凭据，也绝不被回显。</p>
 */
public record TargetConfig(
        long id,
        String adapterType,
        String baseUrl,
        String credentialRef,
        String configJson) {
}
