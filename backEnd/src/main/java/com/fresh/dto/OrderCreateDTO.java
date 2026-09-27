package com.fresh.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 下单入参白名单（T-M1-03）：实体直收已取消——id/status/createTime/totalPrice 等字段
 * 全部由服务端掌控；金额按 dish 现价服务端重算，前端传入的任何金额字段不绑定、直接忽略。
 * 校验消息即对用户的提示文案（GlobalExceptionHandler 原样透出）。
 */
@Data
public class OrderCreateDTO {

    /** 客户端幂等标识（T-M1-09）：同值重复提交返回首次订单 */
    @NotBlank(message = "下单标识不能为空")
    @Size(max = 64, message = "下单标识过长")
    private String clientRequestId;

    /** 提货人手机号（订单归属与查询凭证） */
    @NotBlank(message = "请填写手机号")
    @Pattern(regexp = "1[3-9]\\d{9}", message = "请输入正确的手机号")
    private String phone;

    @NotEmpty(message = "请先选择商品")
    @Size(max = 50, message = "单笔订单商品种类过多")
    @Valid
    private List<OrderItemDTO> items;

    /** 自提点地址（可选；T-M4-01 起自提信息以 storeId 快照为准，本字段仅兼容旧链路，服务端不参与计价） */
    @Size(max = 500, message = "地址过长")
    private String address;

    /** 自提点ID（T-M4-01，可选）：服务端按 id 查 store 落 name/address 快照；缺省兼容不落 */
    @Min(value = 1, message = "自提点参数不合法")
    private Long storeId;

    /** 优惠券ID（T-M4-02，可选）：服务端校验归属/available/threshold 后核销重算 */
    @Min(value = 1, message = "优惠券参数不合法")
    private Long couponId;

    @Data
    public static class OrderItemDTO {

        @NotNull(message = "商品信息不完整，请重新选择")
        private Long dishId;

        /** 单笔数量硬上界 8 = 限购表 [2,5,8] 的最大值；各商品精确限购由服务端按 dishId 复刻前端规则校验 */
        @NotNull(message = "请选择商品数量")
        @Min(value = 1, message = "商品数量至少为1")
        @Max(value = 8, message = "超出限购数量")
        private Integer quantity;
    }
}
