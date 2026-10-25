package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.Settlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 商家结算单 mapper（F-118）：BaseMapper 承担 INSERT（generate）与 SELECT（列表/详情）；
 * 冲销金额与对账统计读 order_item（锚所在的家），归 OrderItemMapper，本面不复制第二份 order_item SQL。
 * <b>无 UPDATE/DELETE 方法</b>（F-118 契约 §3 红线：settlement 只增不改，冲销走下一期 refund 列承载）。
 */
@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {

    /**
     * 该商家既有账期的最晚止日（冲销窗口的左锚，F-118 §1.1「自上一账期 period_end 之后发生退款」）。
     * 无账期历史回 null（=首期，冲销恒 0）。MAX 聚合恒回 1 行、无行回 null，两类都由调用方按 null 判。
     */
    @Select("SELECT MAX(period_end) FROM settlement WHERE merchant_id = #{merchantId}")
    LocalDate selectMaxPeriodEnd(@Param("merchantId") Long merchantId);
}
