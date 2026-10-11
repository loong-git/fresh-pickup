package com.fresh.service;

import com.fresh.dto.MerchantProfileUpdateDTO;

import java.util.Map;

/**
 * 商家端服务（F-12/G-01 Step 4d）：登录与 role=merchant 判定已在 AuthInterceptor 完成
 * （/api/merchant/** 前缀分支 + 查库定角色 + ATTR_MERCHANT_ID 注入），此处不重复角色校验，
 * 但每个以 merchantId 为参的方法仍自守卫：拦截器已在归属为空时收口 403（ATTR_MERCHANT_ID 为有条件注入），
 * 故进到本服务的 merchantId 恒非 null，此处判空属纵深防御，不是可回收的死代码（m15 列可空）。
 */
public interface MerchantService {

    /** 商家资料 + 资质状态：出参 contactPhone 脱敏（R4） */
    Map<String, Object> getMe(Long merchantId);

    /**
     * 更新可编辑字段：merchant.contact_name 与 merchant_profile 三列，一律 WHERE merchant_id=? 强制归属（R3）。
     * userId 只用于审计 actor='merchant:&lt;userId&gt;'（R6'），不参与任何归属判定（归属永远只看 merchantId）。
     */
    void updateProfile(Long userId, Long merchantId, MerchantProfileUpdateDTO dto);

    /**
     * 商家端写闸门（F-13 决策④「商家端**所有**写端点」，BE-5 / F-13.10 #5）：
     * 主体不存在 → 404「商家不存在」（同本类 getMe 与 MerchantDishServiceImpl 既有口径）；
     * status != active（suspended / terminated）→ 403 R.MSG_FORBIDDEN，与拦截器的角色 403 同文案（R2 不新增串）。
     * 读路径（getMe / dishes 列表与详情）不调本方法——停业商家仍要能看自己的货（F-13.5 DoD⑥）。
     * 首行 merchantId 判空是纵深防御（拦截器已保证非 null，一旦有绕过 /api/merchant/** 前缀的内部调用即兜住）。
     */
    void assertActive(Long merchantId);

    /**
     * 上传件审计（BE-5，action='merchant.upload'）：detail 只记落盘文件名与字节数，
     * 绝不记文件内容/base64（R5），也不记原始文件名（用户可控、无留痕价值）。
     * 审计是旁路，写失败只 log.warn，不打断上传主流程（同 AdminServiceImpl#writeAudit 纪律）。
     */
    void auditUpload(Long userId, Long merchantId, String savedFileName, long bytes);
}
