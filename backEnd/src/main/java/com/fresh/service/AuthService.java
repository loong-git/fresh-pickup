package com.fresh.service;

import java.util.Map;

public interface AuthService {

    /**
     * 发送验证码（T-M2-01，契约 M2-1）：dev 固定验证码（auth.dev-code）存内存，5 分钟有效；
     * 云短信接入点见实现类 TODO 注释。
     */
    void sendCode(String phone);

    /**
     * 登录（T-M2-01，契约 M2-1）：校验验证码 + 新手机号静默注册 + 签发 JWT；
     * 成功后认领该手机号的存量订单（user_id IS NULL → 落当前 userId，契约 M2-4）。
     * 邀请新人（F-01.2）：新建账号分支在同一事务内完成 邀请码绑定 + 发新人券，
     * 邀请码惰性生成写回；老用户/无效邀请码一律忽略。
     *
     * @param inviteCode 邀请码（F-01.2，可选）：仅新建账号分支生效
     * @return {token, user:{id, phone}, hasPassword, isNew}（isNew 为 F-01 新增 boolean，向后兼容）
     */
    Map<String, Object> login(String phone, String code, String inviteCode);

    /**
     * 密码登录（T-M5）：查用户 → 未设置过密码报"请先使用验证码登录"（未注册同话术，不给枚举探测信号）
     * → PBKDF2 校验（失败报"手机号或密码错误"）→ 复用验证码登录的签发逻辑。
     *
     * @return {token, user:{id, phone}, hasPassword:true}
     */
    Map<String, Object> loginPassword(String phone, String password);

    /**
     * 设置/更新密码（T-M5）：当前登录态用户改密，PBKDF2 编码落 user.password_hash。
     */
    void setPassword(Long userId, String password);
}
