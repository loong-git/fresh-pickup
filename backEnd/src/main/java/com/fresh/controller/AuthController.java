package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.LoginDTO;
import com.fresh.dto.PasswordLoginDTO;
import com.fresh.dto.SendCodeDTO;
import com.fresh.dto.SetPasswordDTO;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（T-M2-01，契约 M2-1；T-M5 增密码登录）。
 * 权限矩阵：/send-code、/login、/login-password 匿名可达；/set-password 需登录（AuthInterceptor 拦截并注入 userId）。
 * 异常统一交给 GlobalExceptionHandler（验证码错 → 400"验证码错误"，密码错 → 400 业务文案）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /** 发送验证码：dev 固定验证码 123456（auth.dev-code），云短信预留 */
    @PostMapping("/send-code")
    public R<Void> sendCode(@Valid @RequestBody SendCodeDTO dto) {
        authService.sendCode(dto.getPhone());
        return R.ok(null, "验证码已发送");
    }

    /** 登录：校验验证码 + 静默注册 + 签发 JWT，data = {token, user:{id, phone}, hasPassword, isNew}；
     *  inviteCode（F-01.2，可选）仅新建账号分支生效：同事务绑定邀请关系并发新人券 */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto) {
        return R.ok(authService.login(dto.getPhone(), dto.getCode(), dto.getInviteCode()), "登录成功");
    }

    /** 密码登录（T-M5）：PBKDF2 校验 + 复用现有签发逻辑，data = {token, user:{id, phone}, hasPassword:true}；
     *  未设置过密码 → 400"…请先使用验证码登录"。匿名接口按 IP 维度限流 5 次/分钟 */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 5, windowSeconds = 60)
    @PostMapping("/login-password")
    public R<Map<String, Object>> loginPassword(@Valid @RequestBody PasswordLoginDTO dto) {
        return R.ok(authService.loginPassword(dto.getPhone(), dto.getPassword()), "登录成功");
    }

    /** 设置/更新密码（T-M5）：当前登录态改密，密码 6-20 位（SetPasswordDTO 校验）；限流 5 次/分钟/用户 */
    @RateLimit(limit = 5, windowSeconds = 60)
    @PostMapping("/set-password")
    public R<Void> setPassword(@Valid @RequestBody SetPasswordDTO dto,
                               @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        authService.setPassword(userId, dto.getPassword());
        return R.ok(null, "密码设置成功");
    }
}
