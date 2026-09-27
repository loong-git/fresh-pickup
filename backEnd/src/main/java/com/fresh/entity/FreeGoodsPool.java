package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 免费领商品候选池（M8）：活动可领的免费商品白名单，uk_activity_dish(activity_id, dish_id)
 * 数据库级"一活动一商品一行"；用户领取的 dishId 必须在池内（claim 侧 selectCount 校验，
 * 不信任前端入参），展示侧默认免费商品=池内第一行（id 升序=插入序）。
 * 池不存商品快照，展示/领取联 dish 取在售数据（下架商品不进下发列表）。
 */
@Data
@TableName("free_goods_pool")
public class FreeGoodsPool {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属活动ID（free_activity.id，单活动模型种子固定 1） */
    private Long activityId;
    /** 候选商品ID（dish.id，须 on_sale=1，领取时校验） */
    private Long dishId;
}
