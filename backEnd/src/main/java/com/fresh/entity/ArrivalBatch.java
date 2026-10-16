package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 到货批次 arrival_batch（F-18.10 第 3 条建表 / F-18.12 第 6 条读写，m18-migration.sql ⑧）。
 *
 * <p>D-19：一期只有「读 + 确认」，故本表只有演示种子一行。F-60 订正：这里与 m18/init.sql 曾写「批次生成器属
 * 运营端 G-05」，那是双重指错——G-05 是商家端营销参与态+数据看板，运营端是 G-06，两期都没有批次生成页；
 * 本表在代码里**至今没有任何写入方**（只有 ArrivalBatchMapper 的一条按店+日期查询），批次只能由平台直接写库。
 * （store 1 / 明日 / planned）+ 主理人确认动作。
 * {@code uk_store_date (store_id, batch_date)} 既钉死「一店一日一批次」，也是种子 INSERT IGNORE 的幂等依据。
 *
 * <p>{@code supplier_type} 是 D-9 的预留位（一期恒 NULL）——列位照草案留着，本批不写不读语义。
 */
@Data
@TableName("arrival_batch")
public class ArrivalBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 自提点ID（逻辑外键 store.id）：归属判定列，条件 UPDATE 恒带 {@code store_id IN (归属集合)}（RF） */
    private Long storeId;

    /** 批次日期（一期=到货日；查询缺省取明日） */
    private LocalDate batchDate;

    /** 批次状态 planned/arriving/confirmed/short（VARCHAR(20) NOT NULL DEFAULT 'planned'） */
    private String status;

    /** 主理人确认到货时间：NULL=未确认，确认端点与 status 同一条 UPDATE 写入 */
    private LocalDateTime confirmTime;

    /** 批次备注 VARCHAR(255)（一期只读，不开放给主理人改） */
    private String remark;

    /** 供货来源（D-9 预留位，一期恒 NULL） */
    private String supplierType;

    /** 批次创建时间：DB DEFAULT CURRENT_TIMESTAMP 落值，实体不填、无 fill 注解（照 OpsLog:46） */
    private LocalDateTime createdAt;
}
