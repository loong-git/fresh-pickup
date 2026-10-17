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
 * <p>D-19：一期只有「读 + 确认」。全系统对本表的写路径经 2026-10-17 穷尽列举，只有两处：
 * ① 建库种子（init.sql 与 m18-migration.sql 各一条 {@code INSERT IGNORE}，灌 store 1 / 明日 / planned，
 * 只在重建库或重放迁移时执行，<b>不是运行期能力</b>）；② {@code ArrivalBatchMapper#confirmById} 那一条
 * UPDATE（把 planned/arriving 推到 confirmed）。<b>由此得两条硬结论</b>：没有任何端点能<b>创建</b>批次，
 * 也没有任何端点能把状态写成 {@code arriving} 或 {@code short}——这两个值在四态字典与确认白名单里
 * 都活着，却是运行期永远观测不到的纸面状态，只能靠平台直接写库造出来。
 *
 * <p>F-60 订正：这里与 m18/init.sql 曾写「批次生成器属运营端 G-05」，那是双重指错——G-05 是商家端
 * 营销参与态+数据看板，运营端是 G-06，两期都没有批次生成页。
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
