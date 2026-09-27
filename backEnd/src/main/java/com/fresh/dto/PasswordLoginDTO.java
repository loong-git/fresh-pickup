package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 密码登录入参（T-M5）：手机号 + 密码；密码 6-20 位（与 SetPasswordDTO 同一口径）。
 */
@Data
public class PasswordLoginDTO {

    @NotBlank(message = "请填写手机号")
    @Pattern(regexp = "1[3-9]\\d{9}", message = "请输入正确的手机号")
    private String phone;

    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 20, message = "密码长度为 6-20 位")
    private String password;
}
