package com.fresh.controller;

import com.fresh.common.R;
import com.fresh.entity.Dish;
import com.fresh.service.DishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀场次接口（T-M4-03，匿名可读）：
 * - dishes = seckill=1 的商品，直接返回 DB 真实字段（seckillPrice/soldCount/limitBuy/tags/goodRate，JSON 驼峰）；
 * - 场次固定 start=00:00 / end=23:00（契约），serverTime 取服务端时钟（ISO-8601），
 *   前端倒计时以 serverTime+end 为基准，end 过点后重新拉取——改本机时钟不再影响倒计时。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    /** 场次开始/结束（契约固定值） */
    private static final String SESSION_START = "00:00";
    private static final String SESSION_END = "23:00";

    @Autowired
    private DishService dishService;

    @GetMapping("/current")
    public R<Map<String, Object>> current() {
        List<Dish> dishes = dishService.listSeckill();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverTime", LocalDateTime.now()); // Jackson 序列化为 ISO-8601 本地时间
        data.put("start", SESSION_START);
        data.put("end", SESSION_END);
        data.put("dishes", dishes);
        return R.ok(data);
    }
}
