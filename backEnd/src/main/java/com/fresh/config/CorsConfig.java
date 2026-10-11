package com.fresh.config;

import com.fresh.interceptor.AuthInterceptor;
import com.fresh.interceptor.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 配置（T-M1-10）+ MVC 拦截器注册（T-M2-02/T-M2-06）
 * CORS：替代各 Controller 上的 @CrossOrigin（默认 * 通配，等于对所有来源开放）。
 * 白名单仅放行开发前端来源；生产域名上线时在下方 TODO 处追加，切勿改回 "*"。
 * allowedHeaders 含 Authorization（前端登录态携带 Bearer token，T-M2-02）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 商家上传落盘目录（F-12/G-01 §2.8）：与 MerchantController 的 @Value 同源配置，静态映射指向同一处 */
    @Value("${fresh.upload-dir:./uploads/}")
    private String uploadDir;

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

    /**
     * 商家上传件静态映射（F-12/G-01 §2.8 + F-13/G-02 决策①方案 A）：同一物理目录注册两条路径。
     * - /uploads/** → file:${fresh.upload-dir}/：商家端 dev 预览用（商家端跑 5174，图片文件不在其静态目录下，只能回源后端）；
     * - /static/images/uploads/** → 同一目录：与入库的 dish.image 值（/static/images/uploads/&lt;名&gt;）同构，
     *   便于后端直读同一份文件；prod 下该前缀由 nginx alias 命中，本映射不参与。
     * 两条映射指的是同一处，故不存在「改了一处忘另一处」的漂移面。
     * 拦截器链只挂 /api/**（:38-39 零改动），故本映射匿名可读——商品图/执照图本就是展示件，
     * 写入权在 POST /api/merchant/uploads（登录 + role=merchant + 限流）。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String dir = uploadDir.replaceAll("/+$", "");
        String location = "file:" + dir + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
        registry.addResourceHandler("/static/images/uploads/**")
                .addResourceLocations(location);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 生产域名上线后在此追加，例如 https://fresh.example.com
                .allowedOrigins("http://localhost:5173", "http://localhost:5174", "http://localhost:8080")
                // PUT：T-M3-09 管理端薄版（/api/admin/** 核销/改价/补库存/上下架）
                .allowedMethods("GET", "POST", "PUT")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
