package com.fresh.task;

import com.fresh.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 未支付订单超时自动关单调度（T-超时关单，@EnableScheduling 在 FreshApplication 开启）：
 * - fixedDelay：上轮跑完再计时，轮次不重叠；initialDelay 等一个间隔再首扫，避开启动期竞争；
 * - 扫描/关单/回补口径全部在 OrderServiceImpl#closeTimeoutOrders（状态机条件更新保证
 *   与手动取消/支付并发下只关一次，已支付订单绝不触碰），本类只负责调度与结果日志；
 * - 异常不打断调度：Spring 默认 ErrorHandler 会记录 ERROR 日志，下一轮照常执行。
 */
@Slf4j
@Component
public class OrderTimeoutTask {

    /** 扫描间隔（毫秒）：60 秒；生产可调大（如 300_000=5 分钟，足以覆盖 30 分钟超时口径），按运营节奏改这里 */
    private static final long SCAN_INTERVAL_MS = 60_000L;

    @Autowired
    private OrderService orderService;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = SCAN_INTERVAL_MS)
    public void closeTimeoutOrders() {
        int closed = orderService.closeTimeoutOrders();
        if (closed > 0) {
            log.info("超时关单扫描完成: closed={}", closed);
        }
    }
}
