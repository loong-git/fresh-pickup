package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.entity.Dish;
import com.fresh.service.DishService;
import com.fresh.service.SeckillSessionResolver;
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
 * - dishes = 打了秒杀标、且在售/有库存/已填秒杀价的商品，直接返回 DB 真实字段（seckillPrice/soldCount/limitBuy/tags/goodRate，JSON 驼峰）；
 * - 场次 start/end 自 F-115 起读字典组 seckill_session（m25 回填，运营改词条写后自动 evict 即刻生效），
 *   回退语义照契约 §2.1：组不存在（getGroup 抛 BizException）/ 词条缺失 / definition 非 HH:MM
 *   → 该侧回退 00:00/23:00，恒 200 不抛错，配置错误绝不挂 C 端秒杀页；
 * - serverTime 取服务端时钟（ISO-8601），前端倒计时以 serverTime+end 为基准，end 过点后重新拉取——
 *   改本机时钟不再影响倒计时；出参四键形状与常量时代逐字一致。
 * - P11-2 P2-D：场次取值/窗口判定逻辑整体迁入 {@link SeckillSessionResolver}（与下单计价窗口
 *   判定共用同一判定源，杜绝两处口径漂移），本类只透传出参、行为逐字不变。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    @Autowired
    private DishService dishService;

    /** 场次配置判定源（P11-2 P2-D 自私有 sessionTime 迁入；回退口径见该类） */
    @Autowired
    private SeckillSessionResolver seckillSessionResolver;

    /** P11-2 P3-J：匿名读补 IP 档 30/60s（照 StoreController:27 既有姿势；前端倒计时到点重拉，
     *  每次进页/重拉一秒一发也远低于 30/分，不误伤） */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    @GetMapping("/current")
    public R<Map<String, Object>> current() {
        List<Dish> dishes = dishService.listSeckill();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverTime", LocalDateTime.now()); // Jackson 序列化为 ISO-8601 本地时间
        data.put("start", seckillSessionResolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START));
        data.put("end", seckillSessionResolver.sessionTime("end", SeckillSessionResolver.DEFAULT_END));
        data.put("dishes", dishes);
        return R.ok(data);
    }
}
