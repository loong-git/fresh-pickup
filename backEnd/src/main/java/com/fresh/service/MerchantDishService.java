package com.fresh.service;

import com.fresh.dto.MerchantDishCreateDTO;
import com.fresh.dto.MerchantDishOnSaleDTO;
import com.fresh.dto.MerchantDishStockDTO;
import com.fresh.dto.MerchantDishUpdateDTO;
import com.fresh.entity.Dish;

import java.util.List;

/**
 * 商家端商品服务（F-13/G-02 Step 1 归属读写地基，契约 F-13.0.3 Step 1 + F-13.2）。
 * 与 MerchantService 同一套鉴权前提：登录与 role=merchant 判定已在 AuthInterceptor 收口
 * （/api/merchant/** 前缀分支 + 查库定角色 + merchant_id 为空即 403 + ATTR_MERCHANT_ID 注入），
 * 本接口不重复角色校验，但每个以 merchantId 为参的方法自守卫（纵深防御，m16 列可空）。
 * R3' 铁律：本组方法不存在任何不带 merchant_id 条件的读写路径——读列表恒以 merchant_id 等值开头，
 * 读单行与全部写路径的 WHERE 同时含 id 与 merchant_id，他商家商品与不存在的商品同判 404「商品不存在」。
 */
public interface MerchantDishService {

    /**
     * 商品列表：tab=on_sale 在售 / off_sale 已下架 / all（含未知取值）不限；category、keyword（名称 LIKE）
     * 非空才加条件；id 升序、一期不分页（F-13.6①）。
     */
    List<Dish> list(Long merchantId, String tab, String category, String keyword);

    /** 商品详情：id + merchant_id 双条件，查无 → 404「商品不存在」 */
    Dish detail(Long merchantId, Long id);

    /** 新增商品：写闸门 → 归属由服务端注入 → INSERT → 审计 dish.create → 带归属条件回查返回（DB 默认列一并带出） */
    Dish create(Long userId, Long merchantId, MerchantDishCreateDTO dto);

    /** 部分更新：仅 set 传入的非 null 列，全空 → 400；WHERE id + merchant_id，0 行 → 404。库存列不在本路径（R7'） */
    void update(Long userId, Long merchantId, Long id, MerchantDishUpdateDTO dto);

    /** 库存增量调整（R7'：DB 侧 stock = stock + delta，不做读-改-写；减成负数 → 400），返回调整后的真实库存 */
    Integer adjustStock(Long userId, Long merchantId, Long id, MerchantDishStockDTO dto);

    /** 上下架：WHERE id + merchant_id 精确 set on_sale，0 行 → 404 */
    void setOnSale(Long userId, Long merchantId, Long id, MerchantDishOnSaleDTO dto);
}
