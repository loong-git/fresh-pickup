package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家保证金账户 merchant_deposit（P10-5 轨道 B D7/D8，m33-migration.sql 同步建表；
 * DDL 逐字基线照 docs/商家端规划/06-冲突分析与数据模型演进.md §4.6 m1x 草案，一主体一账户 uk_merchant）。
 * <p><b>写路径只有两处</b>（对齐全库「写路径收口」纪律）：
 * ① 运营端设置档位（D7 模拟触发器：{@code MerchantDepositServiceImpl#setRequired}——
 * 真实触发器①②③（营销白名单/风险阈值/信用分级）依赖外部状态，模拟轨由运营手动置等价）；
 * ② 生成账期事务尾部代扣步（D8：{@code MerchantDepositServiceImpl#deductOnSettlement}）。
 * <p><b>模拟轨不启用的列</b>（登记，见 m33 头注）：现金预缴 {@code deposit_amount}（兜底形态不做，
 * 无预缴端点，恒 0）；留存划转 {@code retained_amount}（留真实轨，恒 0）——代扣实缴额由
 * {@code paid_amount} 承载（06 册草案无此列，为 D8 代扣闭环补列并登记偏离）。
 * <p>状态机 {@code status}：none 未启用 / active 正常 / arrears 欠费待补 / forfeited 已没收 /
 * closed 已清退退还（06 册 §4.6 原文枚举；模拟轨只落 none/active/arrears 三态——熔断/没收/清退留真实轨）。
 */
@Data
@TableName("merchant_deposit")
public class MerchantDeposit {

    /** 账户ID：数据库自增（m33 DDL AUTO_INCREMENT，与 merchant/settlement 同款） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商家ID（逻辑外键 merchant.id，一主体一账户） */
    private Long merchantId;

    /** 类目档位键（对齐 dish.category 11 类：meat/vegetable/fastfood/wine/fruit/fresh/frozen/snack/dairy/grain/general） */
    private String category;

    /** 当前档位应缴额；NULL=未启用触发式缴存（06 册 §4.6 列注释原口径） */
    private BigDecimal requiredAmount;

    /** 现金预缴余额（兜底形态；模拟轨无预缴端点恒 0——登记） */
    private BigDecimal depositAmount;

    /** 应结款留存累计（优先形态，与 settlement.retain_amount 联动；模拟轨不启用恒 0——登记） */
    private BigDecimal retainedAmount;

    /** 已缴累计（D8/m33 补列）：代扣实缴额累计；代扣判断与累加的唯一余额列 */
    private BigDecimal paidAmount;

    /** 账户状态（none/active/arrears 三态运行期；forfeited/closed 留真实轨不落） */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updateTime;
}
