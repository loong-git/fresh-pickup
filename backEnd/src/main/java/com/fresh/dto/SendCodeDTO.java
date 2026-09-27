package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发送验证码入参（T-M2-01）。校验消息即对用户的提示文案（GlobalExceptionHandler 原样透出）。
 */
@Data
public class SendCodeDTO {

    @NotBlank(message = "请填写手机号")
    @Pattern(regexp = "1[3-9]\\d{9}", message = "请输入正确的手机号")
    private String phone;
}
