package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 免费领商品活动（M7，M8 起满额赠）：单活动模型（种子固定 id=1），每日 0 元限量领候选池商品。
 * daily_date/daily_claimed 为"当日限量计数器"：跨日首次领取由
 * {@link com.fresh.mapper.FreeActivityMapper#tryClaimQuota} 条件 UPDATE 原子重置，
 * 无 COUNT+INSERT 竞态；status=offline 下线后领取与展示均按"活动暂未开始"处理。
 */
@Data
@TableName("free_activity")
public class FreeActivity {

    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_OFFLINE = "offline";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 今日免费商品ID（须 on_sale=1，领取时校验） */
    private Long dishId;
    /** 每日限量份数（>0 才可领，置 0 等效暂停） */
    private Integer dailyQuota;
    /** 满额门槛（M8）：当日实付（非免费单）累计满此金额方可领取，DECIMAL(10,2) 与 total_price 同精度 */
    private BigDecimal threshold;
    /** 计数器所属日期：与当日一致才累计，NULL/过去日期在首次领取时原子重置 */
    private LocalDate dailyDate;
    /** 当日已领份数（原子条件 UPDATE 自增） */
    private Integer dailyClaimed;
    /** 活动状态：online 进行 / offline 下线 */
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
