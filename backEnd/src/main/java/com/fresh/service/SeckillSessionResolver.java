package com.fresh.service;

import com.fresh.common.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 秒杀场次窗口判定唯一真源（P11-2 P2-D）：从字典组 {@code seckill_session} 读 start/end 词条
 * 并做窗口判定，供两处共用、杜绝两套口径漂移——
 * <ul>
 *   <li>{@code SeckillController#current}：C 端秒杀卡展示出参 start/end（原 Controller 私有
 *       {@code sessionTime} 逻辑整体迁入本类，出参行为逐字不变）；</li>
 *   <li>{@code OrderServiceImpl#createOrder} 计价处（P11-2 P2-D 用户拍板①）：秒杀件按服务端时钟
 *       判窗口，窗口外按原价计价——只影响计价口径，秒杀标识（dish.seckill）本身不动。</li>
 * </ul>
 * 读侧回退语义照 F-115 契约 §2.1 逐字：组不存在（getGroup 抛 BizException）/ 词条缺失 /
 * definition 非 HH:MM → 该侧回退默认值（start→00:00 / end→23:00），配置错误绝不抛错挂 C 端；
 * start ≥ end 不在读侧拦（写入口 AdminServiceImpl §2.2 已拦），此时 {@link #inWindow} 恒 false
 * ——窗口非法不授予秒杀价，保守按原价计价。
 */
@Component
public class SeckillSessionResolver {

    /** 字典组代码（F-115 §2.1；词条 word 固定 start/end，由 m25 回填、运营端 §2.2 特判校验写入） */
    public static final String SESSION_GROUP = "seckill_session";

    /** 回退默认值（与常量时代逐字一致）：start→00:00 / end→23:00 */
    public static final String DEFAULT_START = "00:00";
    public static final String DEFAULT_END = "23:00";

    /** 场次时刻合法格式（契约 §2.1 逐字）：HH:MM，00~23 时 / 00~59 分 */
    private static final Pattern SESSION_TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** 场次配置源（F-115 起替代常量；读走 getGroup 的本地优先 + Caffeine 缓存） */
    @Autowired
    private DictService dictService;

    /**
     * 场次单侧取值（F-115 §2.1）：取字典组 seckill_session 里 word=start/end 词条的 definition，
     * 按契约回退顺序降级——组不存在（getGroup 对未知组抛 BizException，catch 住视同组不存在）、
     * 词条缺失、definition 不匹配 HH:MM 正则 → 该侧回退默认值；正常出 200。
     * start ≥ end 不在此拦（写入口 §2.2 已拦；此处只保形状，照常透出）。
     */
    public String sessionTime(String word, String fallback) {
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

    /**
     * 秒杀计价窗口判定（P11-2 P2-D 用户拍板①）：当前时刻落在 [start, end) 内才按 seckill_price，
     * 否则按 price。含 start 不含 end——与 C 端展示口径一致（end 过点前端倒计时归零「已切明日场次」）；
     * 边界采样由调用方传入（服务端时钟同一采样点），本方法不做时钟读取、便于单测注入。
     * start ≥ end 的非法配置（写入口已拦、直改 DB 可现）：区间在同一天内恒为空 → 恒 false，
     * 全天按原价计价（窗口非法不授予折扣，保守侧）。
     */
    public boolean inWindow(LocalTime now) {
        LocalTime start = LocalTime.parse(sessionTime("start", DEFAULT_START));
        LocalTime end = LocalTime.parse(sessionTime("end", DEFAULT_END));
        return !now.isBefore(start) && now.isBefore(end);
    }
}
