package com.graduation.mall.auth.config;

import com.graduation.mall.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层注册（§3.2）：AuthInterceptor 挂载到 /api/v1/**，排除白名单
 * —— 仅 /api/v1/auth/login（商城自身登录入口）。
 * 原白名单中的 GET /api/v1/metrics/health 属于分析平台指标端点，已随平台复制代码移出本模块。
 * BCryptPasswordEncoder 在 PasswordEncoderConfig 独立提供（避免配置类环依赖）。
 */
@Configuration
@RequiredArgsConstructor
public class AuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/login");
    }
}