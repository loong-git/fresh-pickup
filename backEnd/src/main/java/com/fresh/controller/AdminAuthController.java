package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.AdminLoginDTO;
import com.fresh.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运营登录端点（F-20/G-06 Step 2，D-5）：{@code POST /api/admin/auth/login} → 一枚 {@code typ=admin} 运营 JWT。
 *
 * <p><b>本类是 Admin 面上唯一匿名可达的端点</b>：{@code AuthInterceptor#requiresLogin} 里为这一条路径写了
 * <b>精确匹配</b>的放行（F-20.6 第 4 条；那里刻意不许写 {@code startsWith("/api/admin")}，否则 5 个写端点与
 * 后续 v2 全变免登录面）。除本条之外的 {@code /api/admin/**} 一律走
 * {@code AdminController#checkKeyOrOperator} 双轨闸门。
 *
 * <p>限流两层（R6 随建随挂）：端点上这道 {@code @RateLimit(IP, 5, 60)} 是<b>令牌桶</b>，挡的是
 * 「同一来源无节制地撞网关」；Service 里那道 5 次/10 分钟成对键锁挡的是「针对某个用户名的口令枚举」。
 * 两者文案各自独立（429 走 {@code R.MSG_TOO_MANY_REQUESTS}，401 走 Service 那句防枚举文案），
 * 但都<b>不泄漏用户名是否存在</b>。
 *
 * <p>出参 {@code {token, displayName, adminUserId}}（白名单 Map，由 Service 逐键重建，<b>绝不回实体</b>）；
 * 异常统一交 {@code GlobalExceptionHandler} 转信封（HTTP 恒 200，业务码只看 body.code）。
 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    @Autowired
    private AdminAuthService adminAuthService;

    /**
     * 运营口令登录（D-5）：body {@code {username, password}} → 200 {@code {token, displayName, adminUserId}}；
     * 失败（无此账号 / 口令错 / 已停用 / 锁定中）一律 401 同一句「用户名或密码错误」，防枚举。
     *
     * <p>不加 {@code @Valid}：校验注解会产生分支文案（见 {@link AdminLoginDTO} 类注释），
     * 形状不合格由 Service 收敛成同一枚 401。
     * <p>{@code HttpServletRequest} 由 Spring 直接注入给 Service 取客户端 IP（成对键与审计 ip 的唯一来源），
     * 本层不解析、不转字符串，避免把 IP 变成第二条可被误用的入参。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 5, windowSeconds = 60)
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody AdminLoginDTO dto, HttpServletRequest request) {
        return R.ok(adminAuthService.login(
                dto == null ? null : dto.getUsername(),
                dto == null ? null : dto.getPassword(),
                request), "登录成功");
    }
}
