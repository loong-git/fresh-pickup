package com.fresh.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * prod 启动期密钥 fail-fast 校验（ADMIN_KEY / JWT_SECRET / DB_PASSWORD 三项）：
 * - 三项都由 deploy/fresh.service 的 Environment= 行注入，而该 unit 文件在仓库里公开可读，
 *   里面三个占位值（replace-with-admin-key / replace-with-strong-random-secret / replace-with-real-db-password）
 *   照抄进生产照样能启动——application-prod.yml 的「无默认值占位符」只挡得住「完全没注入」，挡不住「注入占位值」。
 *   其中 JWT_SECRET 保持公开默认串 = 任何人可离线伪造全量用户 token，比 admin key 泄漏更严重。
 * - ADMIN_KEY 的 @Value 表达式带默认值 admin-dev-key（AdminController / AdminServiceImpl 共用），
 *   dev 不注入也能起，故其判定条件（空白/短于 8 位/两个公开默认串）在此维持一字不改。
 * - JWT_SECRET / DB_PASSWORD 用空串默认值 ${...:} 读取：prod yml 里它们本就无默认值，空串只是让
 *   「未注入」也落到本类的明确中文报错上，而不是笼统的 placeholder 解析异常；不给它们编造真实默认值。
 * - 仅 @Profile("prod") 生效，dev 行为零改动：dev 下本类根本不注册，dev 的默认值语义一行未动；
 *   不改任何 yml 键值，不新增配置键。
 * - 前提：prod 部署必须显式 --spring.profiles.active=prod，否则本组件不注册（@Profile 语义），
 *   fail-fast 的强度取决于 profile 纪律。
 */
@Component
@Slf4j
@Profile("prod")
public class AdminKeyProdGuard {

    /** 与 AdminController#adminKey / AdminServiceImpl#adminKey 同一表达式，只读不改 */
    @Value("${ADMIN_KEY:admin-dev-key}")
    private String adminKey;

    /** 空串默认只为让「完全未注入」也给出中文报错；prod 真实值仍必须来自环境变量（application-prod.yml:12） */
    @Value("${JWT_SECRET:}")
    private String jwtSecret;

    /** 同上，对应 application-prod.yml:8 的 spring.datasource.password；不编造任何真实默认值 */
    @Value("${DB_PASSWORD:}")
    private String dbPassword;

    /** 启动期校验：三项任一为空白、仍是 deploy/fresh.service 的公开占位值，或长度低于下限（JWT_SECRET 32 位、ADMIN_KEY 与 DB_PASSWORD 各 8 位），即拒绝启动（不降级为 warn，弱密钥不允许带病上线） */
    @PostConstruct
    public void verifyAdminKey() {
        // ADMIN_KEY：判定条件维持原样一字不改（null / 空白 / 短于 8 / 两个公开默认串）
        if (adminKey == null || adminKey.isBlank() || adminKey.length() < 8
                || "admin-dev-key".equals(adminKey) || "replace-with-admin-key".equals(adminKey)) {
            throw new IllegalStateException("生产环境必须通过环境变量 ADMIN_KEY 注入管理密钥，禁止使用默认值；"
                    + "请在 deploy/fresh.service 中把占位值 replace-with-admin-key 替换为强随机密钥");
        }
        // JWT_SECRET：null 判定排在最前，后续 isBlank()/length()/equals 都不会 NPE
        // 长度下限 32（=256bit，HS256 的理论强度上限；JwtUtil 内部做 SHA-256 派生，故短值也能起、只是弱）
        // 注意：本类读的是裸环境变量名 JWT_SECRET（${JWT_SECRET:}），不是生效值 jwt.secret——这是刻意的，
        // 读 jwt.secret 会重新触发 prod yml 嵌套占位符解析，把笼统的 placeholder 异常又请回来。
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.length() < 32
                || "replace-with-strong-random-secret".equals(jwtSecret)) {
            throw new IllegalStateException("生产环境必须通过环境变量 JWT_SECRET 注入不少于 32 位的强随机签名密钥；"
                    + "请在 deploy/fresh.service 中把占位值 replace-with-strong-random-secret 替换为真实密钥，"
                    + "否则任何人都能离线伪造全量用户 token；"
                    + "提示：若以 --jwt.secret 命令行直接注入生效密钥，请同时设置同名环境变量 JWT_SECRET"
                    + "（本守卫读取的是环境变量名，不是生效值）");
        }
        // DB_PASSWORD：长度下限 8（与 ADMIN_KEY 同档，更高的密码强度策略不由本类裁定）；null 判定排在最前
        if (dbPassword == null || dbPassword.isBlank() || dbPassword.length() < 8
                || "replace-with-real-db-password".equals(dbPassword)) {
            throw new IllegalStateException("生产环境必须通过环境变量 DB_PASSWORD 注入不少于 8 位的 fresh_app 账号真实数据库密码；"
                    + "请在 deploy/fresh.service 中把占位值 replace-with-real-db-password 替换为真实密码");
        }
        // 运维核对用的摘要留痕（R8 敏感清单纪律）：只打 SHA-256 前 8 位，绝不输出任何明文值本身。
        // 只打 ADMIN_KEY / JWT_SECRET 这两项高熵随机串——截断到 32bit 的哈希对低熵口令就是离线爆破预言机，
        // 故 DB_PASSWORD 的指纹不进日志（DB 口令本就该由 DBA 掌握，不靠运维日志核对身份）。
        log.info("已启用 prod 密钥启动校验：ADMIN_KEY 摘要 {}、JWT_SECRET 摘要 {}（DB_PASSWORD 已注入但按策略不打指纹）",
                digest(adminKey), digest(jwtSecret));
    }

    /** 密钥摘要（供 ADMIN_KEY / JWT_SECRET 两项日志留痕）：SHA-256 十六进制取前 8 位，与 AdminServiceImpl#adminKeyDigest 同姿势但各自私有（不跨类复用） */
    private String digest(String secret) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必备 SHA-256，走不到；兜底也不回退成明文密钥
            return "unknown";
        }
    }
}
