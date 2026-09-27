package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.ChangePhoneDTO;
import com.fresh.entity.User;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.mapper.UserMapper;
import com.fresh.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户资料接口（F-06.7.4 R8）：头像查看与设置；F-08 更换手机号。
 * 权限矩阵：/api/user/** 整体需登录（AuthInterceptor 前缀拦截，AuthServiceImpl 登录响应亦透传 avatar）。
 * POST /avatar 入参必须显式携带 avatar 键：缺键=畸形请求直接 400；键存在且为 null/空串=恢复默认
 * （列置 NULL，前端兜默认 SVG 头像）；非空必须 data:image/(jpeg|png|webp);base64, 前缀且主体仅为
 * base64 合法字符 [A-Za-z0-9+/=]+（防任意字符串入库），UTF-8 字节数 ≤60000（与 user.avatar TEXT 列
 * 65535 字节上限对齐；按字节而非字符数校验，防多字节字符以字符数口径骗过校验后超列容——
 * 60000 个 3 字节字符实达 179956 字节，128×128 JPEG 0.85 头像实测 5~30KB 远低于上限），
 * 违规一律 400 不落库。userId 不存在时影响 0 行，返回「用户不存在」。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    /**
     * 头像 data URI 格式白名单：data:image/(jpeg|png|webp);base64, 前缀 + 主体仅限 base64 合法字符集
     * [A-Za-z0-9+/=]+。String.matches 全串语义——主体不能沿用 .+（等价放行任意字符，
     * 实测 '!!!!not-base64!!!!' 会原样落库），必须整体匹配 base64 字符集。
     */
    private static final String AVATAR_FORMAT_REGEX = "^data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$";

    /**
     * 头像字节长度上限：UTF-8 编码后字节数 ≤60000，与 user.avatar TEXT 列 65535 字节上限对齐（预留余量）。
     * 按字节而非 String.length()（字符数）校验：多字节字符按字符数会低估落库体积
     * （60000 个 3 字节字符实达 ~18 万字节，超列容直接 SQL 异常 500）。
     */
    private static final int AVATAR_MAX_BYTES = 60000;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AuthService authService;

    /** 我的资料：手机号脱敏 + 头像（NULL 直出，前端兜默认） */
    @GetMapping("/me")
    public R<Map<String, Object>> me(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phone", maskPhone(user.getPhone()));
        data.put("avatar", user.getAvatar());
        return R.ok(data, "获取成功");
    }

    /**
     * 设置/恢复默认头像：限流 5 次/分钟/用户（防刷库）。
     * 入参必须显式携带 avatar 键：缺键=畸形请求，直接 400「缺少 avatar 参数」，
     * 不得落进「null=恢复默认」分支把用户头像静默清空；键存在且值为 null/空串才是恢复默认。
     */
    @RateLimit(limit = 5, windowSeconds = 60)
    @PostMapping("/avatar")
    public R<Map<String, Object>> updateAvatar(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                               @RequestBody Map<String, String> body) {
        if (body == null || !body.containsKey("avatar")) {
            throw new BizException("缺少 avatar 参数");
        }
        String avatar = body.get("avatar");
        if (avatar != null && !avatar.isBlank()) {
            String trimmed = avatar.trim();
            // 先按字节限长再校验格式：超限载荷（含主体非 base64 的超大多字节串）统一先报
            // 「头像过大」，体量合法的格式错误串才落到「头像格式不支持」
            if (trimmed.getBytes(StandardCharsets.UTF_8).length > AVATAR_MAX_BYTES) {
                throw new BizException("头像过大，请重新选择图片");
            }
            if (!trimmed.matches(AVATAR_FORMAT_REGEX)) {
                throw new BizException("头像格式不支持");
            }
            avatar = trimmed;
        } else {
            avatar = null;
        }
        int rows = userMapper.updateAvatar(userId, avatar);
        if (rows == 0) {
            // 与 me() 同口径：userId 不存在（已删除）不得假成功；连接串未开 useAffectedRows，
            // 返回 matched rows，值未变化也计 1，故 0 行只会是目标用户不存在
            throw new BizException("用户不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("avatar", avatar);
        return R.ok(data, avatar == null ? "已恢复默认头像" : "头像已更新");
    }

    /**
     * 更换手机号（F-08）：登录态换绑登录账号。校验链与换绑收在 AuthServiceImpl#changePhone 同一事务，
     * 此处只做转发；服务端从 token 反查当前手机号（不信任前端传旧号）。
     * 限流 3 次/分钟/用户（默认 USER 维度，防固定验证码环境下的验证码爆破）；
     * 成功返回 {phone: 新号原始值}，脱敏展示由前端负责。
     */
    @RateLimit(limit = 3, windowSeconds = 60)
    @PostMapping("/change-phone")
    public R<Map<String, Object>> changePhone(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                              @Valid @RequestBody ChangePhoneDTO dto) {
        String newPhone = authService.changePhone(userId, dto.getOldPhoneCode(), dto.getNewPhone(), dto.getNewPhoneCode());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phone", newPhone);
        return R.ok(data, "手机号已更换");
    }

    /** 手机号脱敏 138****1234（展示口径，与订单/邀请记录一致思路） */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
