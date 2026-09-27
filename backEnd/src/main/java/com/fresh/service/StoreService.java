package com.fresh.service;

import com.fresh.entity.Store;

import java.util.List;

/** 自提点门店服务（T-M4-01） */
public interface StoreService {

    /** 全部门店列表（含停业，前端置灰用），id 升序稳定输出 */
    List<Store> listAll();
}
