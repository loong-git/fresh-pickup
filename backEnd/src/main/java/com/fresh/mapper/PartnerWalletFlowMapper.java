package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.PartnerWalletFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 主理人佣金流水 mapper（F-119）：BaseMapper 承担 INSERT（跑批记账）与本人流水分页读；
 * 唯一追加查询是运营列表的按人流水计数（一次分组聚合，无 N+1）。
 * uk_order_biz 的撞键跳过语义在 Service 层（预查 + catch DuplicateKeyException 双保险），本面不持有
 * 任何写方法之外的特化语句。
 */
@Mapper
public interface PartnerWalletFlowMapper extends BaseMapper<PartnerWalletFlow> {

    /**
     * 运营侧全量钱包列表的流水数（F-119 §3「user/店名/余额/流水数」）：按属主分组计数，一次聚合
     * 无 N+1。无流水的主理人不回行（调用方以 0 兜底）；COUNT 回 Long，SUM 类别名照本仓聚合
     * Map 出参惯例（userId/flowCount）。
     */
    @Select("SELECT user_id AS userId, COUNT(*) AS flowCount FROM partner_wallet_flow GROUP BY user_id")
    List<Map<String, Object>> countFlowsByUser();
}
