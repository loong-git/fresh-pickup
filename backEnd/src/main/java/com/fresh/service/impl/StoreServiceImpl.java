package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fresh.entity.Store;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.StoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StoreServiceImpl implements StoreService {

    @Autowired
    private StoreMapper storeMapper;

    @Override
    public List<Store> listAll() {
        // 停业门店一并返回（前端 pickup 页置灰展示），id 升序稳定输出。
        // BE-7（F-18.20 G 行，🔴 必做）：显式列清单——不选 photos 就不触发 JacksonTypeHandler 解析，
        // 库里一个脏 photos 值（非法/截断 JSON）从结构上不可能再把匿名 GET /api/stores 打成 500
        //（主会话实测原形态 500）；owner_user_id/contact_phone/daily_capacity/accepting 连 select 都没取，
        // D-12 从「7 个 @JsonIgnore 别漏挂」升级成结构性保证。实体上的 @JsonIgnore 保留作纵深，不删。
        return storeMapper.selectList(new LambdaQueryWrapper<Store>()
                .select(Store::getId, Store::getName, Store::getAddress, Store::getService,
                        Store::getStatus, Store::getLng, Store::getLat, Store::getCreatedAt)
                .orderByAsc(Store::getId));
    }
}
