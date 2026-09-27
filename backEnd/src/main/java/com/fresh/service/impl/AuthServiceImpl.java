package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.common.JwtUtil;
import com.fresh.common.PasswordUtil;
import com.fresh.entity.User;
import com.fresh.mapper.UserMapper;
import com.fresh.service.AuthService;
import com.fresh.service.InviteService;
import com.fresh.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务（T-M2-01）：
 * - send-code：验证码存内存（phone → code+过期时间），5 分钟有效，登录成功即作废（一次性）；
 *   TODO 云短信接入点：接入后删除 auth.dev-code 配置，改为短信服务商 SDK 发送随机码并落存储。
 * - login：验证码校验（不存在/过期/不匹配统一"验证码错误"，不给枚举探测信号）
 *   + 静默注册（phone UNIQUE，并发落库冲突回读）+ 签发 JWT + 认领存量订单。
 *   邀请新人（F-01.2）：新建账号分支在同一事务内完成 邀请码生成写回 + 绑定 invite_relation
 *   + 发新人见面礼（newbie_gift）；响应 data 加 isNew（向后兼容）。
 * - login-password / setPassword（T-M5）：PBKDF2 校验与落库（PasswordUtil），token 与 login 同源（JwtUtil）。
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    /** 验证码有效期 5 分钟（契约 M2-1） */
    private static final long CODE_TTL_MILLIS = 5 * 60 * 1000L;

    /** 内存验证码存储：key=手机号；量级为活跃登录手机号，登录成功/过期时清理，无需持久化 */
    private final Map<String, CodeEntry> codeStore = new ConcurrentHashMap<>();

    /** dev 固定验证码（auth.dev-code）；为空表示短信通道未接入（prod 配置为空串） */
    @Value("${auth.dev-code:}")
    private String devCode;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderService orderService;
    @Autowired
    private InviteService inviteService;
    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public void sendCode(String phone) {
        if (devCode == null || devCode.isBlank()) {
            // TODO 云短信：接入后此分支替换为真实发送，验证码改为随机 6 位
            throw new BizException("短信通道未接入，请联系管理员");
        }
        codeStore.put(phone, new CodeEntry(devCode, System.currentTimeMillis() + CODE_TTL_MILLIS));
        log.info("验证码已发送: phone={}（dev 固定验证码）", mask(phone));
    }

    @Override
    @Transactional // F-01.2：建号 + 邀请码写回 + 绑定 + 发新人券同事务，任一失败整体回滚
    public Map<String, Object> login(String phone, String code, String inviteCode) {
        // —— 验证码校验：一次性，命中即作废；过期清理 ——
        CodeEntry entry = codeStore.get(phone);
        if (entry == null || entry.expireAt < System.currentTimeMillis()) {
            codeStore.remove(phone);
            throw new BizException("验证码错误");
        }
        if (!entry.code.equals(code == null ? "" : code.trim())) {
            throw new BizException("验证码错误");
        }
        codeStore.remove(phone);

        // —— 静默注册：user.phone UNIQUE，并发首登唯一约束冲突后回读 ——
        boolean isNew = false;
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            try {
                userMapper.insert(user);
                isNew = true;
            } catch (DuplicateKeyException e) {
                user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
            }
        }

        if (isNew) {
            // —— 邀请新人（F-01.2 新用户分支，同一事务内）：① 邀请码生成查重写回（存量用户
            //    由 GET /api/invite/me 惰性生成，此处失败不影响登录，惰性链路兜底）；
            //    ② inviteCode 有效（能查到邀请人且≠自己）→ insert invite_relation + 发新人券；
            //    无效/重复绑定静默忽略（见 InviteServiceImpl#bindOnRegister）
            inviteService.ensureInviteCode(user);
            inviteService.bindOnRegister(user, inviteCode);
        }
        // 老用户（含并发冲突回读）带邀请码一律忽略（F-01.0 裁定），isNew=false

        // —— 认领存量订单（契约 M2-4）：登录成功把该手机号历史订单归属到本用户 ——
        int claimed = orderService.claimOrdersByPhone(phone, user.getId());
        if (claimed > 0) {
            log.info("登录认领存量订单: phone={}, claimed={}", mask(phone), claimed);
        }

        String token = jwtUtil.createToken(user.getId());
        return Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "phone", user.getPhone()),
                "hasPassword", hasPassword(user),
                "isNew", isNew);
    }

    @Override
    public Map<String, Object> loginPassword(String phone, String password) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        // 未注册与未设置密码统一引导验证码登录：不给"手机号是否注册"的枚举探测信号（与 login 验证码话术同思路）
        if (user == null || !hasPassword(user)) {
            throw new BizException("该手机号尚未设置密码，请先使用验证码登录");
        }
        if (!PasswordUtil.verify(password == null ? "" : password, user.getPasswordHash())) {
            throw new BizException("手机号或密码错误");
        }
        String token = jwtUtil.createToken(user.getId());
        return Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "phone", user.getPhone()),
                "hasPassword", true);
    }

    @Override
    public void setPassword(Long userId, String password) {
        int updated = userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getPasswordHash, PasswordUtil.encode(password)));
        if (updated <= 0) {
            // userId 来自已验证 token，正常必命中；兜底防静默失败
            throw new BizException("用户不存在，请重新登录");
        }
        log.info("密码已设置: userId={}", userId);
    }

    /** hasPassword 口径：password_hash 非空非空白（迁移前的存量用户该列为 NULL） */
    private boolean hasPassword(User user) {
        return user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
    }

    /** 日志脱敏 138****1234 */
    private String mask(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /** 验证码条目：code + 过期时间戳 */
    private record CodeEntry(String code, long expireAt) {
    }
}
