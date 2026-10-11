package com.fresh.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境管理密钥 fail-fast 校验（S-2）：
 * - ADMIN_KEY 的 @Value 表达式带默认值 admin-dev-key（AdminController / AdminServiceImpl 共用），
 *   dev 不注入也能起；但 deploy/fresh.service 只留占位 replace-with-admin-key，生产漏注入时
 *   该公开默认值即成万能钥匙（可核销他人订单、改价、删评价、代开通商家）。
 * - 姿势对齐 application-prod.yml 里 DB_PASSWORD/JWT_SECRET 的「无默认值占位符 → 缺则启动即失败」防呆；
 *   ADMIN_KEY 因表达式已带默认值做不到，故在 prod profile 下用本组件启动期补一道校验。
 * - 仅 @Profile("prod") 生效，dev 行为零改动；不改任何 yml 键值，也不改 @Value 默认值语义。
 */
@Component
@Profile("prod")
public class AdminKeyProdGuard {

    /** 与 AdminController#adminKey / AdminServiceImpl#adminKey 同一表达式，只读不改 */
    @Value("${ADMIN_KEY:admin-dev-key}")
    private String adminKey;

    /** 启动期校验：空白或仍是公开默认值即拒绝启动（不降级为 warn，弱密钥不允许带病上线） */
    @PostConstruct
    public void verifyAdminKey() {
        if (adminKey == null || adminKey.isBlank() || "admin-dev-key".equals(adminKey)) {
            throw new IllegalStateException("生产环境必须通过环境变量 ADMIN_KEY 注入管理密钥，禁止使用默认值");
        }
    }
}
