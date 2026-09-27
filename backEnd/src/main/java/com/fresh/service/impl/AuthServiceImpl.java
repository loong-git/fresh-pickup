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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务（T-M2-01）：
 * - send-code：验证码存内存（phone → code+过期时间），5 分钟有效，登录成功即作废（一次性）；
 *   TODO 云短信接入点：接入后删除 auth.dev-code 配置，改为短信服务商 SDK 发送随机码并落存储。
 * - login：验证码校验（不存在/过期/不匹配统一"验证码错误"，不给枚举探测信号）
 *   + 静默注册（phone UNIQUE，并发落库冲突回读）+ 签发 JWT + 认领存量订单。
 *   邀请新人（F-01.2）：新建账号分支在同一事务内完成 邀请码生成写回 + 绑定 invite_relation
 *   响应 data 加 isNew（向后兼容）；新人见面礼已改挂领券中心自行领取（F-05 券轨下线）。
 * - login-password / setPassword（T-M5）：PBKDF2 校验与落库（PasswordUtil），token 与 login 同源（JwtUtil）。
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    /** 验证码有效期 5 分钟（契约 M2-1） */
    private static final long CODE_TTL_MILLIS = 5 * 60 * 1000L;

    /** 内存验证码存储：key=手机号；量级为活跃登录手机号，登录成功/过期时清理，无需持久化 */
    private final Map<String, CodeEntry> codeStore = new ConcurrentHashMap<>();

    /** 同手机号发送时间戳（F-04.2 前置安全项自限）：60s 间隔 + 每日 10 次上限；内存计数重启清零，dev 可接受 */
    private final Map<String, List<Long>> sendTimestamps = new ConcurrentHashMap<>();

    /** 同手机号发送间隔 60s（F-04.2 前置安全项） */
    private static final long SEND_INTERVAL_MILLIS = 60 * 1000L;

    /** 同手机号每日发送上限 10 次（F-04.2 前置安全项） */
    private static final int SEND_DAILY_LIMIT = 10;

    /** 每日上限滑动窗 24h */
    private static final long DAILY_WINDOW_MILLIS = 24 * 60 * 60 * 1000L;

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
        // F-04.2 前置安全项（同手机号自限，防配合固定验证码无限造号刷邀请冻结现金）：
        // 60s 内重复发送拒绝（429）；24h 滑动窗内超 10 次拒绝（429）
        long now = System.currentTimeMillis();
        List<Long> stamps = sendTimestamps.computeIfAbsent(phone, k -> new ArrayList<>());
        synchronized (stamps) {
            stamps.removeIf(t -> now - t > DAILY_WINDOW_MILLIS);
            if (!stamps.isEmpty() && now - stamps.get(stamps.size() - 1) < SEND_INTERVAL_MILLIS) {
                throw new BizException(429, "发送太频繁，请 1 分钟后再试");
            }
            if (stamps.size() >= SEND_DAILY_LIMIT) {
                throw new BizException(429, "今日发送次数已达上限，请明日再试");
            }
            stamps.add(now);
        }
        codeStore.put(phone, new CodeEntry(devCode, now + CODE_TTL_MILLIS));
        log.info("验证码已发送: phone={}（dev 固定验证码）", mask(phone));
    }

    @Override
    @Transactional // F-01.2/F-04：建号 + 邀请码写回 + 绑定 + 冻结邀请人现金同事务，任一失败整体回滚
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
            //    ② inviteCode 有效（能查到邀请人且≠自己）→ insert invite_relation + 冻结邀请人现金；
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
        // R8：登录响应 user 透传 avatar（NULL=未设置，前端兜默认 SVG 头像）；
        // Map.of 不允许 null 值，avatar 可空故用 LinkedHashMap
        Map<String, Object> userVo = new java.util.LinkedHashMap<>();
        userVo.put("id", user.getId());
        userVo.put("phone", user.getPhone());
        userVo.put("avatar", user.getAvatar());
        return Map.of(
                "token", token,
                "user", userVo,
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
        // R8：同 login，user 透传 avatar（可空用 LinkedHashMap）
        Map<String, Object> userVo = new java.util.LinkedHashMap<>();
        userVo.put("id", user.getId());
        userVo.put("phone", user.getPhone());
        userVo.put("avatar", user.getAvatar());
        return Map.of(
                "token", token,
                "user", userVo,
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

    @Override
    @Transactional // F-08：旧码/新码校验 + 换绑 UPDATE 同一事务，UPDATE 失败整体回滚不留半态
    public String changePhone(Long userId, String oldPhoneCode, String newPhone, String newPhoneCode) {
        // ① 从 token 的 userId 反查当前用户，旧手机号以库里为准（不信任前端传旧号）
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在，请重新登录");
        }

        // ② 当前手机号验证码：与 login 同口径（不存在/过期/不匹配统一"验证码错误"，命中即作废）
        consumeCode(user.getPhone(), oldPhoneCode);

        // ③ 新号格式（DTO @Pattern 已在入口拦一道，此处兜底防绕过入口直调 service）
        if (newPhone == null || !newPhone.matches("1[3-9]\\d{9}")) {
            throw new BizException("请输入正确的手机号");
        }

        // ④ 新号验证码：send-code 发到新号的码，同样一次性命中作废
        consumeCode(newPhone, newPhoneCode);

        // ⑤ 新号未注册：user.phone UNIQUE，已有归属即拒绝（含新号=当前号的自杀式请求）
        User occupant = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, newPhone));
        if (occupant != null) {
            throw new BizException("该手机号已注册");
        }

        // ⑥ 换绑：密码/头像/订单/优惠券/邀请关系随 user 行保留（userId 不变）；
        //    ⑤→⑥ 并发窗口（他方此刻抢注新号）由 uk_phone 唯一键兜底，冲突转业务 400
        try {
            int updated = userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getPhone, newPhone));
            if (updated <= 0) {
                throw new BizException("用户不存在，请重新登录");
            }
        } catch (DuplicateKeyException e) {
            throw new BizException("该手机号已注册");
        }
        log.info("更换手机号成功: userId={}, {} -> {}", userId, mask(user.getPhone()), mask(newPhone));
        return newPhone;
    }

    /**
     * 验证码一次性消费（F-08，与 login 校验逻辑同口径）：codeStore 命中即作废 remove；
     * 不存在/过期/不匹配统一"验证码错误"，不给枚举探测信号。
     */
    private void consumeCode(String phone, String code) {
        CodeEntry entry = codeStore.get(phone);
        if (entry == null || entry.expireAt < System.currentTimeMillis()) {
            codeStore.remove(phone);
            throw new BizException("验证码错误");
        }
        if (!entry.code.equals(code == null ? "" : code.trim())) {
            throw new BizException("验证码错误");
        }
        codeStore.remove(phone);
    }

    /** 日志脱敏 138****1234 */
    private String mask(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /** 验证码条目：code + 过期时间戳 */
    private record CodeEntry(String code, long expireAt) {
    }
}
