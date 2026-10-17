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
 * <p>arrival_batch 的运行期写路径（F-63 起为三处，此前长期只有后两处）：
 * ① {@code AdminServiceImpl#departArrival}——运营端「发车」建批次（status 直接 arriving，
 * 这是批次<b>创建</b>与 {@code arriving} 态进入的唯一运行期入口；平台统一配货模型给了它业务资格）；
 * ② {@code ArrivalBatchMapper#markArriving}——对既有 planned/arriving 批次幂等翻态（发车重算明细前）；
 * ③ {@code ArrivalBatchMapper#confirmById}——主理人确认到货，把 planned/arriving 推到 confirmed。
 * 另有建库种子（init.sql 与 m18-migration.sql 各一条 {@code INSERT IGNORE}，灌 store 1 / 明日 / planned，
 * 只在重建库或重放迁移时执行，<b>不是运行期能力</b>）。
 * {@code short} 至今没有任何运行期写入方，仍是只能靠平台直接写库造出来的纸面状态。
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
