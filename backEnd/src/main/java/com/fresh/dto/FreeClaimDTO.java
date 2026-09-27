package com.fresh.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 免费领商品入参（M8）：用户从候选池自选商品；候选池白名单与满额门槛校验在服务端
 * （FreeServiceImpl#claim），手机号/地址/金额等归属字段仍按 token 服务端落库，不信任前端任何字段。
 * M9 契约升级：items=用户当前购物车内容（dishId+quantity），满额门槛按服务端以 dish 表价格
 * 重算的合计判定，前端传入的任何金额字段不绑定、直接忽略（OrderCreateDTO 同思路）。
 */
@Data
public class FreeClaimDTO {

    /** 候选池内商品ID（池外/缺省由服务端转"无效的免费商品"人话文案） */
    @NotNull(message = "请选择要领取的免费商品")
    private Long dishId;

    /** 用户当前购物车内容（M9）：门槛重算合计的数据源，须非空且每项数量 ≥1 */
    @NotEmpty(message = "请先挑选商品再领取")
    @Valid
    private List<ClaimItemDTO> items;

    /** 购物车条目（内嵌静态类对照 OrderCreateDTO.OrderItemDTO）：只收 dishId+quantity 白名单字段 */
    @Data
    public static class ClaimItemDTO {

        @NotNull(message = "商品信息不完整，请重新选择")
        private Long dishId;

        @NotNull(message = "请选择商品数量")
        @Min(value = 1, message = "商品数量至少为1")
        private Integer quantity;
    }
}
