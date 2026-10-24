package com.fresh.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.Store;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StoreMapper extends BaseMapper<Store> {

    /**
     * F-111 日容量拦截：按主键锁读 store 行（FOR UPDATE）。下单事务在「锁行 → COUNT 容量 → INSERT 订单」
     * 区间内对同一 store 行互斥，防两个并发事务都数到 N-1 后各插一单导致超发；
     * cancelled 单不占容量，取消/超时关单即自动释放，故取消链路零改动。
     * 写法照 OrderServiceImpl#selectByClientRequestId 的 wrapper.last("FOR UPDATE") 风格。
     */
    default Store selectByIdForUpdate(Long id) {
        QueryWrapper<Store> wrapper = new QueryWrapper<>();
        wrapper.eq("id", id);
        wrapper.last("FOR UPDATE");
        return selectOne(wrapper);
    }
}
