package com.fresh.config;

import com.fresh.interceptor.AuthInterceptor;
import com.fresh.interceptor.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 配置（T-M1-10）+ MVC 拦截器注册（T-M2-02/T-M2-06）
 * CORS：替代各 Controller 上的 @CrossOrigin（默认 * 通配，等于对所有来源开放）。
 * 白名单仅放行开发前端来源；生产域名上线时在下方 TODO 处追加，切勿改回 "*"。
 * allowedHeaders 含 Authorization（前端登录态携带 Bearer token，T-M2-02）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;
    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    /**
     * 拦截器注册（T-M2-02）：仅作用于 /api/**。
     * 顺序敏感——认证在前（解析 token 注入 userId attribute），限流在后（用户维度取桶）。
     * 两个拦截器内部均已放行 OPTIONS 预检，与 CORS 共存无冲突。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 生产域名上线后在此追加，例如 https://fresh.example.com
                .allowedOrigins("http://localhost:5173", "http://localhost:8080")
                // PUT：T-M3-09 管理端薄版（/api/admin/** 核销/改价/补库存/上下架）
                .allowedMethods("GET", "POST", "PUT")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
