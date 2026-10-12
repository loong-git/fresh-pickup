package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 到货批次明细 arrival_item（F-18.10 第 4 条建表，m18-migration.sql ⑨）。
 *
 * <p>本表<b>无唯一键</b>（演示种子的幂等靠 SQL 里的 {@code WHERE NOT EXISTS} 守卫，不是 INSERT IGNORE），
 * 也<b>没有 created_at 列</b>——照 m18 建表原文，实体别多写一列。
 *
 * <p>D-19 一期只读：{@code actual_qty}/{@code short_reason} 是「短少联动」的列位，
 * 一期主理人确认到货<b>不写</b>这两列（短少登记只在商家侧 fulfill_status 那条链上，见 D-9）。
 */
@Data
@TableName("arrival_item")
public class ArrivalItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 批次ID（逻辑外键 arrival_batch.id；KEY idx_batch） */
    private Long batchId;

    /** 菜品ID（逻辑外键 dish.id）；NULL=非菜品项预留 */
    private Long dishId;

    /** 预计到货数量（INT NOT NULL DEFAULT 0） */
    private Integer expectedQty;

    /** 实际到货数量；NULL=尚未清点（一期不写，见类注释） */
    private Integer actualQty;

    /** 短少原因（status=short 时填写；一期不写） */
    private String shortReason;
}
