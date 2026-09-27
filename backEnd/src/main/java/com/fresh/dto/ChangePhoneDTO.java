package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 更换手机号入参（F-08）：当前手机号验证码 + 新手机号 + 新手机号验证码。
 * 校验消息即对用户的提示文案（GlobalExceptionHandler 原样透出）。
 * 注意：不接收旧手机号——服务端从 token 的 userId 反查当前 phone（不信任前端传旧号）。
 */
@Data
public class ChangePhoneDTO {

    /** 当前手机号的验证码（send-code 发到现有登录手机号），一次性，命中即作废 */
    @NotBlank(message = "请输入当前手机号验证码")
    private String oldPhoneCode;

    /** 新手机号：格式在此校验；未注册校验在服务端（uk_phone 唯一键兜底，F-08 安全闸） */
    @NotBlank(message = "请填写新手机号")
    @Pattern(regexp = "1[3-9]\\d{9}", message = "请输入正确的手机号")
    private String newPhone;

    /** 新手机号的验证码（send-code 发到新号），一次性，命中即作废 */
    @NotBlank(message = "请输入新手机号验证码")
    @Pattern(regexp = "\\d{4,6}", message = "验证码格式不正确")
    private String newPhoneCode;
}
