package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.service.MerchantFulfillService;
import com.fresh.service.OpsLogWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家侧缺货登记实现（F-18.8 第 5 条/D-15）。
 *
 * <p>归属：条件 UPDATE 恒带 {@code merchant_id = #{merchantId}}，值只来自
 * {@code AuthInterceptor.ATTR_MERCHANT_ID}（前缀分支查库定 role=merchant + merchant_id 为空即 403 后注入），
 * 本类不重复角色判定；首行自守卫判空回 403（照 MerchantOrderServiceImpl#assertMerchant 的纵深防御）。
 *
 * <p>定性只有两种出口，且都不泄漏他人行存在性：
 * 影响行数 &gt;0 → 成功 + ops_log；影响行数 =0 → selectById，
 * 行不存在或 merchant_id 不是自己（含历史行 merchant_id 为 NULL）→ 404；是自己的行 → 已是 shortage → 幂等成功、不写日志。
 *
 * <p>【已登记偏差】条件 UPDATE 的 WHERE 里比契约 F-18.8 第 5 条多写了
 * {@code AND fulfill_status != 'shortage'}：本仓数据源 URL 没带 {@code useAffectedRows=true}
 * （application-dev.yml:5），Connector/J 默认按 **matched rows** 回影响行数，照契约原文
 * 「WHERE 只有 id + merchant_id」的话，重复登记会回 1 行、进而<b>再写一条 ops_log</b>，
 * 与 D-6「幂等成功不重复写日志」和 S3-G7「重复登记幂等」直接冲突。加上这一条后，
 * 「已登记」回 0 行 → 走 selectById 定性 → 幂等成功且不落第二行日志；三种定性结果（200/404/幂等）与契约完全一致。
 */
@Slf4j
@Service
public class MerchantFulfillServiceImpl implements MerchantFulfillService {

    /** 404 文案复用 MockPayService:49 那条「订单不存在」（明细行也归在「订单不存在」口径下，不新增句子、不泄漏他人行） */
    private static final String NOT_FOUND_MSG = "订单不存在";

    /** fulfill_status 旁路列取值（建列见 m17-migration.sql:70-81、init.sql:140）：normal/shortage，一期唯一写入方就是本类 */
    private static final String FULFILL_STATUS_SHORTAGE = "shortage";

    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private OpsLogWriter opsLogWriter;

    @Override
    @Transactional
    public void registerShortage(Long userId, Long merchantId, Long orderItemId) {
        assertMerchant(merchantId);

        int updated = orderItemMapper.markShortage(orderItemId, merchantId);
        if (updated > 0) {
            // 现场动作审计（D-5）：detail 只放状态迁移，不放手机号/地址/订单号（RD）
            opsLogWriter.write(userId, "register_shortage", String.valueOf(orderItemId),
                    "{\"from\":\"normal\",\"to\":\"" + FULFILL_STATUS_SHORTAGE + "\"}");
            log.info("缺货登记成功: orderItemId={}, merchantId={}, operatorUserId={}",
                    orderItemId, merchantId, userId);
            return;
        }

        OrderItem row = orderItemMapper.selectById(orderItemId);
        if (row == null || row.getMerchantId() == null || !row.getMerchantId().equals(merchantId)) {
            // 他人行与不存在行同码同文案：不给枚举者「这行存在」的信号
            throw new BizException(R.CODE_NOT_FOUND, NOT_FOUND_MSG);
        }
        // 是自己的行却零改动 = 已经是 shortage（见类注释的 WHERE 说明）→ 幂等成功，不写第二行日志
        log.info("缺货登记幂等命中: orderItemId={}, merchantId={}", orderItemId, merchantId);
    }

    /** 归属自守卫（纵深防御，非可达分支）：与商家侧既有 403 同文案，绝不「参数为空即不加条件」 */
    private void assertMerchant(Long merchantId) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
    }
}
