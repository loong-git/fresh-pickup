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
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableField(exist = false)
    private List<OrderItem> items;
}
