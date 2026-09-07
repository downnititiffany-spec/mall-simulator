package com.graduation.analytics.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码编码器（§3.2）：独立配置类提供 BCryptPasswordEncoder，
 * 避免 AuthConfig（WebMvcConfigurer）与拦截器/服务形成环依赖。
 * 仅引入 spring-security-crypto，无任何 Security 过滤器链。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}