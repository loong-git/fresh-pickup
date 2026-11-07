package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.DepositFlow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 保证金流水 mapper（P10-5 D8，m33 建表）：唯一写路径=BaseMapper#insert（生成账期事务尾部代扣步，
 * type=2 应结款代扣、channel 恒 MOCK）；无 UPDATE/DELETE（流水只增不改，对齐 partner_wallet_flow 的
 * F-119 纪律）；读路径只有商家端/运营端展示（后续按需加 SELECT，本片无独立流水查询端点——
 * 商家端展示读账户与 settlement.deposit_amount，运营端列表读账户）。
 */
@Mapper
public interface DepositFlowMapper extends BaseMapper<DepositFlow> {
}
