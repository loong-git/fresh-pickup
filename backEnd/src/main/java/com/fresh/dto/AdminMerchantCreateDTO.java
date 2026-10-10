package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 平台代开通商家入参（F-12/G-01 M-05 一期，决策点②）：body {name, phone, contactName?}。
 * 一期 phone 同时落 merchant.contact_phone 并用于绑定登录 user（社区团购小店主联系号=登录号是常态）；
 * name/contactName 长度对齐 merchant 表列宽（VARCHAR(100)/VARCHAR(50)）。
 */
@Data
public class AdminMerchantCreateDTO {

    @NotBlank(message = "商家名不能为空")
    @Size(max = 100, message = "商家名过长")
    private String name;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式错误")
    private String phone;          // 联系手机号=绑定登录手机号（一期同号，决策点②）

    @Size(max = 50, message = "联系人过长")
    private String contactName;    // 选填
}
