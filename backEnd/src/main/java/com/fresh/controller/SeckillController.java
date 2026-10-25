package com.fresh.controller;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.Dish;
import com.fresh.service.DishService;
import com.fresh.service.DictService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 秒杀场次接口（T-M4-03，匿名可读）：
 * - dishes = 打了秒杀标、且在售/有库存/已填秒杀价的商品，直接返回 DB 真实字段（seckillPrice/soldCount/limitBuy/tags/goodRate，JSON 驼峰）；
 * - 场次 start/end 自 F-115 起读字典组 seckill_session（m25 回填，运营改词条写后自动 evict 即刻生效），
 *   回退语义照契约 §2.1：组不存在（getGroup 抛 BizException）/ 词条缺失 / definition 非 HH:MM
 *   → 该侧回退 00:00/23:00，恒 200 不抛错，配置错误绝不挂 C 端秒杀页；
 * - serverTime 取服务端时钟（ISO-8601），前端倒计时以 serverTime+end 为基准，end 过点后重新拉取——
 *   改本机时钟不再影响倒计时；出参四键形状与常量时代逐字一致。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    /** 字典组代码（F-115 §2.1；词条 word 固定 start/end，由 m25 回填、运营端 §2.2 特判校验写入） */
    private static final String SESSION_GROUP = "seckill_session";

    /** 回退默认值（与常量时代逐字一致）：start→00:00 / end→23:00 */
    private static final String DEFAULT_START = "00:00";
    private static final String DEFAULT_END = "23:00";

    /** 场次时刻合法格式（契约 §2.1 逐字）：HH:MM，00~23 时 / 00~59 分 */
    private static final Pattern SESSION_TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    @Autowired
    private DishService dishService;

    /** 场次配置源（F-115 起替代常量；读走 getGroup 的本地优先 + Caffeine 缓存） */
    @Autowired
    private DictService dictService;

    @GetMapping("/current")
    public R<Map<String, Object>> current() {
        List<Dish> dishes = dishService.listSeckill();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverTime", LocalDateTime.now()); // Jackson 序列化为 ISO-8601 本地时间
        data.put("start", sessionTime("start", DEFAULT_START));
        data.put("end", sessionTime("end", DEFAULT_END));
        data.put("dishes", dishes);
        return R.ok(data);
    }

    /**
     * 场次单侧取值（F-115 §2.1）：取字典组 seckill_session 里 word=start/end 词条的 definition，
     * 按契约回退顺序降级——组不存在（getGroup 对未知组抛 BizException，catch 住视同组不存在）、
     * 词条缺失、definition 不匹配 HH:MM 正则 → 该侧回退默认值；正常出 200。
     * start ≥ end 不在此拦（写入口 §2.2 已拦；此处只保形状，照常透出）。
     */
    private String sessionTime(String word, String fallback) {
        List<Map<String, Object>> items;
        try {
            items = dictService.getGroup(SESSION_GROUP);
        } catch (BizException e) {
            return fallback; // 组不存在/本地空且无上游来源：该侧回退默认值
        }
        for (Map<String, Object> item : items) {
            if (word.equals(item.get("word"))) {
                Object definition = item.get("definition");
                String value = definition == null ? "" : String.valueOf(definition);
                return SESSION_TIME.matcher(value).matches() ? value : fallback;
            }
        }
        return fallback; // 词条缺失
    }
}
