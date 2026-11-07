package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 保证金流水 deposit_flow（P10-5 轨道 B D8，m33-migration.sql 同步建表；
 * DDL 逐字基线照 06 册 §4.6 m1x 草案，cash_flow 余额快照范式：balance_after=变动后已缴余额快照，
 * type 定方向、amount 恒正——对齐 cash_flow 惯例）。
 * <p><b>模拟轨唯一写路径</b>=生成账期事务尾部代扣步（type=2 应结款代扣，
 * {@code MerchantDepositServiceImpl#deductOnSettlement}）；扣款白名单四类（biz_type：
 * claim 理赔/act 违约/regulatory 监管连带/advance 垫付，04 册 §3.4）与退还/没收等方向留真实轨。
 * <p>{@code channel} 列（06 册草案无，m33 补列登记偏离）：契约红线「资金流水通道字段一律显式
 * MOCK/SIM」的落点——模拟轨代扣恒 MOCK，无真实资金流。
 */
@Data
@TableName("deposit_flow")
public class DepositFlow {

    /** 流水ID：数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商家ID（逻辑外键 merchant.id） */
    private Long merchantId;

    /** 方向：1缴存 2应结款代扣 3扣罚 4退还 5没收 6留存划转（type 定方向，amount 恒正） */
    private Integer type;

    /** 正数金额（方向由 type 承载） */
    private BigDecimal amount;

    /** 变动后余额快照（cash_flow 三范式之一） */
    private BigDecimal balanceAfter;

    /** 扣款白名单来源（claim/act/regulatory/advance）；type=2 代扣缴存不属白名单，恒 NULL */
    private String bizType;

    /** 来源单据号（代扣行=settlement.settleNo，任务 D8「来源= settlement.settleNo」） */
    private String bizId;

    /** 操作主体留痕（跑批代扣恒 SYSTEM） */
    private String operator;

    /** 通道标注（模拟轨恒 MOCK，无真实资金流） */
    private String channel;

    /** 备注/申诉窗口指引 */
    private String remark;

    private LocalDateTime createdAt;
}
