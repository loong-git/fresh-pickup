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
     * 拦截器链只挂 /api/**（:38-39 零改动），故本映射**匿名可读**；写入权在 POST /api/merchant/uploads
     * （登录 + role=merchant + 限流 + 停业闸门）。
     * 【F-13.10 #7 更正原注释的事实错误】此前这里写「商品图/执照图本就是展示件」，前提已被证伪：
     * 营业执照与商品图经同一个 sink 落进同一目录、URL 形态同构（MerchantController:97-103 无 scene 参数），
     * 所以执照图当前与商品图共用这条匿名映射、凭不可猜的 UUID 可读——唯一防线是 UUID。
     * 处置方案（分 scene 子目录 / 走带归属的下载端点）待拍板，见 F-13.6 追加条目；本类映射行为一字未改。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // BE-8（F-13.10 #10）：fresh.upload-dir 为空串时 dir.replaceAll 得 ""、location 退化成 "file:/"，
        // 静态映射直接指向文件系统根（../ 穿越守卫随之失效）。今天 yml 有非空默认值所以不可达，
        // 但 deploy/fresh.service 里一行 Environment=FRESH_UPLOAD_DIR= 的失误即可踩中。
        // 与 dev「空值凑合跑」相比，AdminKeyProdGuard:47-71 的 fail-fast 姿势才是本仓口径：拒绝启动。
        if (uploadDir == null || uploadDir.isBlank()) {
            throw new IllegalStateException("fresh.upload-dir 必须是非空目录路径：空值会让商家上传件的静态映射"
                    + "退化成 file:/（文件系统根），上传件与执照图将匿名可读且可被 ../ 穿越；"
                    + "dev 取 application.yml 的 ../front/static/images/uploads/，prod 由环境变量 FRESH_UPLOAD_DIR 注入");
        }
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
                // 追加 5175/5176（F-18.15 §5，D-12 同口径的加字段式改动，既有三项一字未改）：
                // 主理人端 front-partner 的 dev 端口。端口不保证——三端（front / front-merchant /
                // front-partner）同跑时谁先起谁占 5173，最后一个会顺延到 5175 或 5176，
                // 两个都登记才不会让顺延的那一端所有 XHR 被 CORS 拦成「网络异常」假故障。
                // 只加不改：5173=C 端、5174=商家端、8080=本地直跑口径照旧。
                .allowedOrigins("http://localhost:5173", "http://localhost:5174", "http://localhost:8080", "http://localhost:5175", "http://localhost:5176", "http://localhost:5177")
                // PUT：T-M3-09 管理端薄版（/api/admin/** 核销/改价/补库存/上下架）
                .allowedMethods("GET", "POST", "PUT")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
