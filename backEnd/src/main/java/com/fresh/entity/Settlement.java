package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商家结算单 settlement（F-118 三期第二片启用读写；建表=F-117/m27 照 06 册 §4.5 纸面定稿，
 * 列清单与 init.sql:447 起的建表体逐字一致——本类不新增任何列映射）。
 * <p><b>只增不改</b>（F-118 契约 §3 红线）：冲销走<b>下一期</b> refund_amount 列承载（06 册 §4.5 定稿），
 * status 恒 accounted（08 册 §2.2-5）——<b>P10-5 轨道 B（本契约 D5）解除 08 册挂账</b>：新增
 * paid_time/pay_channel 两列（m32），status 增 paid 打款态且唯一写路径=运营「确认打款」条件 UPDATE
 * （{@code SettlementMapper#markPaid}，幂等），其余列语义照 F-118 §1.1 不动；
 * 生成账期/对账逻辑零改动。金额口径本片落法（F-118 §1.1 为唯一实现依据）：
 * gmv_amount=gross=Σ(supply_price×qty)（供货价基数，覆盖 06 册列注释的成交价口径）；
 * coupon_deduct/commission/deduction 恒 0；retain_amount 轨道 B 留存抵扣才有值，本片恒 0；
 * refund_amount=冲销（正数存储，net 里减）；payable=net=gross−refund−retain。
 */
@Data
@TableName("settlement")
public class Settlement {

    /** 结算单ID：数据库自增（m27 DDL AUTO_INCREMENT，无雪花——与 merchant/store 同款） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 结算单号（对账凭证；uk_settle_no）：06 册只定列与唯一键未给明文格式，
     *  本实现按列宽 VARCHAR(32) 取 "SET"+19 位雪花（≤22 字符，全局唯一由 ASSIGN_ID 同源算法与 uk 兜底） */
    private String settleNo;

    /** 商家ID（逻辑外键 merchant.id） */
    private Long merchantId;

    /** 账期起（uk_merchant_period 三列之一） */
    private LocalDate periodStart;

    /** 账期止（uk_merchant_period 三列之一） */
    private LocalDate periodEnd;

    /** 含该商家明细的去重订单数（一单多商家不重复计，06 册列注释原口径） */
    private Integer orderCount;

    /** 本片 gross（F-118 §1.1）：Σ(supply_price×qty)，聚合基数排除 shortage 缺货明细 */
    private BigDecimal gmvAmount;

    /** 券抵扣分摊：本片恒 0（列位保留） */
    private BigDecimal couponDeduct;

    /** 平台佣金（F-119 起：生成账期事务内在挂锚成功后按去重订单 Σ(total_price×3%)±冲回的净值回填；
     *  逐单明细在 partner_wallet_flow（settlement_id 锚），Σ 与本列可互核；F-118 本片恒 0） */
    private BigDecimal commission;

    /** 应结款留存划转（保证金替代物）：轨道 B 留存抵扣才有值，本片恒 0（列位保留） */
    private BigDecimal retainAmount;

    /** 本期代扣入保证金额（P10-5 轨道 B D8/m33 新列）：生成账期事务尾部代扣步写入——
     *  无档位商家恒 0（列 DEFAULT 0）；代扣后 payable=net−deposit_amount（同一条回填 UPDATE 落两列），
     *  net（gmv−refund−retain）口径零改动 */
    private BigDecimal depositAmount;

    /** 扣罚/理赔抵扣：本片恒 0（列位保留，联动 deposit_flow） */
    private BigDecimal deduction;

    /** 退款冲销（正数存储；net 里减）：上一账期止之后发生退款（pay_status 1→2）的已挂锚明细负向计入本期 */
    private BigDecimal refundAmount;

    /** 应结=本片 net=gross−refund−retain（其余四项本片恒 0，公式退化为两元，与 06 册列公式兼容） */
    private BigDecimal payable;

    /** accounted 记账态（m27 DDL DEFAULT 'accounted'；08 册 §2.2-5「status 停在 accounted」的挂账由
     *  P10-5 轨道 B（m32）解除——paid 打款态唯一写路径=运营「确认打款」条件 UPDATE，见 {@code markPaid}） */
    private String status;

    /** 打款时间：模拟轨（P10-5 D5/m32）由「确认打款」写入；06 册纸面预留列（打款通道未接时代建列），
     *  与新列 paidTime 分立——paid_at/payout_voucher 留真实轨（微信商家转账）不启用，两套列不混写 */
    private LocalDateTime paidAt;

    /** 打款凭证号：06 册纸面预留列，留真实轨恒 null（轨道 B 模拟打款不写本列） */
    private String payoutVoucher;

    /** 模拟轨打款时间（P10-5 轨道 B D5/m32 新列）：「确认打款」条件 UPDATE 写 NOW()；
     *  与纸面预留列 paidAt 语义分立（见其注释） */
    private LocalDateTime paidTime;

    /** 打款通道（P10-5 轨道 B D5/m32 新列）：模拟轨恒 'MOCK_SIM'——显式 MOCK 标注，无真实资金流，
     *  防「演示冒充结算」；真实轨另行登记通道值 */
    private String payChannel;

    private LocalDateTime createdAt;

    private LocalDateTime updateTime;
}
