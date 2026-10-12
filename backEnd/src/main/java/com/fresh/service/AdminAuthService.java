package com.fresh.service;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

/**
 * 运营账号登录服务（F-20/G-06 Step 2，D-5）：本仓第一条「运营域」口令校验链路。
 *
 * <p>与 {@code AuthService}（C 端验证码/口令登录）<b>物理分开</b>：不同表（admin_user vs user）、
 * 不同 token 轨（typ=admin vs 无 typ）、不同审计表（admin_audit_log vs 无），
 * 目的是让运营域与 C 端域没有任何共享判定代码（D-4/D-6 的物理隔离口径）。
 *
 * <p>鉴权闸门（X-Admin-Key 与运营 JWT 的双轨裁决）在 {@code AdminController}，不在本类——与
 * {@code AdminService} 的「鉴权在 Controller 层完成」同一分层口径；本类只负责「口令能不能换出一枚 token」。
 */
public interface AdminAuthService {

    /**
     * 运营口令登录（D-5）：成功返回<b>白名单出参</b> {@code {token, displayName, adminUserId}}，
     * 失败一律抛 {@code BizException(401, "用户名或密码错误")}（无此账号 / 口令不符 / 已停用 / 锁定中
     * 四种情况<b>同一枚码同一句话</b>，防枚举；锁定中额外在信封 data 里带 {@code retryAfterSeconds}）。
     *
     * <p>出参<b>永不含实体</b>：{@code passwordHash} 只在本方法内部流向 {@code PasswordUtil.verify}，
     * 既不出参也不落日志（RD/G4）。
     *
     * @param username 登录名（未做形状校验，空/超长由实现收敛成同一句 401）
     * @param password 明文口令（只进 verify 与 PBEKeySpec，绝不落库/落日志/入异常消息）
     * @param request  取客户端 IP 用：成对键 {@code clientIp|username} 与审计 ip 列的唯一来源
     *                 （取值口径镜像 RateLimitInterceptor，见实现类注释）
     * @return 白名单 Map：{@code token}（typ=admin 运营 JWT）、{@code displayName}（为空回退 username）、
     *         {@code adminUserId}
     */
    Map<String, Object> login(String username, String password, HttpServletRequest request);
}
