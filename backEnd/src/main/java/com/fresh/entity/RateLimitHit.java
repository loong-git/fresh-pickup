package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 限流命中日志 rate_limit_hit（F-121/m29 建表，纯观测旁路轻表）：
 * 写路径只有 {@code RateLimitInterceptor} 命中分支的旁路 INSERT（现有 log.warn 同点，插入失败只
 * log.warn 绝不影响拦截语义——观测是旁路，绝不因观测挂限流）与每日清理任务 DELETE，无业务链路涉及；
 * 读路径只有运营聚合端点 GET /api/admin/rate-limit/hits（只读，审计不适用）。
 * route = method + 空格 + URI（参数化 URI 按实落库，接口身份维度由 bucketKey 的控制器类名与方法名段承载）；
 * bucketKey = {@code RateLimitInterceptor#bucketKey} 产物原样；retryAfter = 429 下发的 retryAfterSeconds。
 */
@Data
@TableName("rate_limit_hit")
public class RateLimitHit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** method + 空格 + URI（如 POST /api/orders） */
    private String route;

    /** 限流维度键原样（ip:1.2.3.4:控制器类名与方法名 / user:11:控制器类名与方法名） */
    private String bucketKey;

    /** 429 下发的 retryAfterSeconds（桶回速最早可再试整秒数，F-22 口径） */
    private Integer retryAfter;

    /** 命中时刻（旁路写入；idx_hit_time 支撑清理 DELETE 与运营聚合 WHERE） */
    private LocalDateTime hitTime;
}
