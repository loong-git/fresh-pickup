package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录入参（T-M2-01）：手机号 + 验证码；新手机号由服务端静默注册，无需注册接口。
 * inviteCode（F-01.2，可选）：仅验证码登录"新建账号"分支生效（同事务绑定+发新人券）；
 * 老用户/密码登录携带一律忽略（F-01.0 裁定），无效邀请码静默忽略不报错，故不加格式校验。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "请填写手机号")
    @Pattern(regexp = "1[3-9]\\d{9}", message = "请输入正确的手机号")
    private String phone;

    @NotBlank(message = "请输入验证码")
    @Pattern(regexp = "\\d{4,6}", message = "验证码格式不正确")
    private String code;

    /** 邀请码（可选）：8 位去混淆字符集，来自邀请链接 #/pages/login/login?invite=<code> */
    private String inviteCode;
}
