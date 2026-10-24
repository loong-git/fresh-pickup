package com.fresh.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端秒杀商品集更新入参（F-112 §1.2，{@code PUT /api/admin/seckill/dishes/{id}}）：
 * body {@code {seckill: 0|1, seckillPrice?: number}}。
 *
 * <p>三种语义：进秒杀（seckill=1 + 必填价）/ 调价（seckill=1 + 新价）/ 移出（seckill=0）。
 * {@code seckill=0} 时 {@code seckillPrice} 即使传了也不生效——<b>移出不回清秒杀价</b>
 * （两列解耦是既有语义：C 端读路径按 seckill=1 才生效，历史价保留）。
 *
 * <p>DTO 刻意不带校验注解：契约 §1.2 钉死校验顺序（404 → seckill 值域 → 价形状 →
 * 价与库内现价比），全部在 Service 内按序判，@Valid 的全局文案插不进这条链
 * （同 F-110 AdminAliasUpdateDTO 的姿势）。
 */
@Data
public class AdminSeckillUpdateDTO {

    /** 秒杀标记：0（移出）/ 1（进秒杀或调价）；null 与其余值由 Service 判 400 */
    private Integer seckill;

    /** 秒杀价（seckill=1 时必填、&gt;0、最多两位小数、须低于库内现价；seckill=0 时忽略） */
    private BigDecimal seckillPrice;
}
