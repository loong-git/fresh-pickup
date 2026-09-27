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
        // 停业门店一并返回（前端 pickup 页置灰展示），id 升序稳定输出
        return storeMapper.selectList(new LambdaQueryWrapper<Store>().orderByAsc(Store::getId));
    }
}
