package com.fresh.service;

import com.fresh.dto.MerchantProfileUpdateDTO;

import java.util.Map;

/**
 * 商家端服务（F-12/G-01 Step 4d）：登录与 role=merchant 判定已在 AuthInterceptor 完成
 * （/api/merchant/** 前缀分支 + 查库定角色 + ATTR_MERCHANT_ID 注入），此处不重复角色校验，
 * 但每个以 merchantId 为参的方法自守卫（ATTR_MERCHANT_ID 理论上可为 NULL，m15 列可空）。
 */
public interface MerchantService {

    /** 商家资料 + 资质状态：出参 contactPhone 脱敏（R4） */
    Map<String, Object> getMe(Long merchantId);

    /** 更新可编辑字段：merchant.contact_name 与 merchant_profile 三列，一律 WHERE merchant_id=? 强制归属（R3） */
    void updateProfile(Long merchantId, MerchantProfileUpdateDTO dto);
}
