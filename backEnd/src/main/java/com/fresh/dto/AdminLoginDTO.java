package com.fresh.dto;

import lombok.Data;
import lombok.ToString;

/**
 * 运营登录入参（F-20/G-06 Step 2，D-5）：{@code {username, password}} 两个键，别的都不收。
 *
 * <p><b>刻意一个校验注解都不挂</b>（对照 {@link PasswordLoginDTO} 的 {@code @NotBlank/@Size/@Pattern} 形态）：
 * Bean Validation 一失败就经 {@code GlobalExceptionHandler#handleValid} 回「请填写用户名」这类<b>分支文案（400）</b>，
 * 而 D-5 要的是「空值 / 超长 / 无此账号 / 口令错 / 已停用」五种情况<b>同一枚 401 同一句话</b>——
 * 分支文案等于把「这个用户名能不能通过形状关」泄漏出去，防枚举就白做了。
 * 故形状校验放在 {@code AdminAuthServiceImpl#login} 第 2 步做（同句同码），本类只是搬运工。
 *
 * <p>{@code @ToString(exclude = "password")}：{@code @Data} 默认生成的 toString 会带明文口令，
 * 一旦将来有人在日志或异常里 {@code dto.toString()} 就直接把口令写进日志（RD 红线）。
 * 显式排除后 Lombok 不再生成含口令的 toString；getter/setter 照常（Jackson 绑定走 setter）。
 */
@Data
@ToString(exclude = "password")
public class AdminLoginDTO {

    /** 运营登录名（admin_user.username，uk_admin_username 等值命中；空/超长由 Service 收敛成同一句 401） */
    private String username;

    /** 明文口令：只流到 {@code PasswordUtil.verify} 与 PBEKeySpec，不出现在出参、日志、审计、异常消息里（RD） */
    private String password;
}
