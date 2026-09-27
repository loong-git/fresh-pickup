package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设置密码入参（T-M5）：需登录态调用；密码 6-20 位，校验消息即对用户的提示文案。
 */
@Data
public class SetPasswordDTO {

    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 20, message = "密码长度为 6-20 位")
    private String password;
}
