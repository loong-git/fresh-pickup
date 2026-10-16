package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("orders")
public class Order {
    /** 订单号：服务端雪花生成（T-M1-01），不可猜测的 19 位数字串；实体注解优先级高于 yml 全局 id-type:auto */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private BigDecimal totalPrice;
    private String address;
    private String phone;
    /** 自提日期（T-M3-04）：下单服务端按服务器时间裁决（<23:00 明天 / >=23:00 后天）落库并随下单响应返回 */
    private LocalDate pickupDate;
    /** 订单状态三态（T-M1-07）：pending_pickup 待自提 / completed 已完成 / cancelled 已取消 */
    private String status;
    /** 客户端幂等标识（T-M1-09），uk_client_request_id 唯一约束 */
    private String clientRequestId;
    /** 下单用户ID（T-M2-03）：服务端从 JWT token 解析落库，idx_user_id；登录时认领 user_id IS NULL 的存量单 */
    private Long userId;
    /** 自提点ID（T-M4-01，可选）：下单服务端按 storeId 查 store 落快照，未传为 NULL */
    private Long storeId;
    /** 自提点名称快照（T-M4-01）：下单时按 storeId 查 store 落库 */
    private String storeName;
    /** 自提点地址快照（T-M4-01）：下单时按 storeId 查 store 落库 */
    private String storeAddress;
    /** 支付状态（T-M2-05）：0 未支付 / 1 已支付 */
    private Integer payStatus;
    /** 支付时间（T-M2-05） */
    private LocalDateTime payTime;
    /**
     * 取货码（G-04/D-3/D-4/m17）：6 位数字、uk_pickup_code 全局唯一，支付置态事务内生成（未支付无码=NULL，是设计不是缺陷）；
     * <b>只发给单主本人</b>——{@code GET /api/orders} 按 userId 过滤，出参带它是有意的：主理人核销时用户必须口播这 6 位码；
     * 跨用户/跨商家一律不出（partner/merchant 侧走各自白名单出参，不共享这个实体）。核销成功后不清空（幂等判定依据）。
     */
    private String pickupCode;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableField(exist = false)
    private List<OrderItem> items;
    /**
     * 「货已到自提点」派生态（F-62，<b>非 DB 列</b>）：本单的 {@code (store_id, pickup_date)} 命中
     * 一条 {@code arrival_batch.status='confirmed'}（主理人已确认到货）时为 {@code true}。
     *
     * <p><b>为什么不落列</b>：到货是「门店 × 日期」的事实（{@code uk_store_date} 一店一日一批次），不是订单
     * 自己的事实。落列就得在批次确认时反查该店该日全部待取单批量 UPDATE，于是「先确认后下单」「改批次日期」
     * 「回填历史单」三类空洞全要另写代码兜；派生读一次算一次，批次状态一变，C 端口径同步变。
     *
     * <p><b>只在 {@code GET /api/orders} 里填</b>（{@code OrderServiceImpl#markGoodsArrived}），其它出口
     * （下单响应、商家/主理人/运营各自的白名单出参）一律 {@code null}——本仓无全局 {@code NON_NULL} 配置，
     * {@code null} 会真的出现在 JSON 里，故消费方判据必须写 {@code === true}，不可写 truthy 之外的宽松比较。
     * 赋值范围刻意收窄到「{@code pending_pickup} 且已支付且 {@code store_id} 非空」三类条件同时成立的单：
     * 已完成/已取消的单显示「去拿货」是假指引，无自提点的单无从归因批次（该空洞见 2026-10-16 登记的
     * 8 单 {@code store_id IS NULL}，本字段把它们如实判为「未到货」而不是替它们编一个）。
     */
    @TableField(exist = false)
    private Boolean goodsArrived;
}
