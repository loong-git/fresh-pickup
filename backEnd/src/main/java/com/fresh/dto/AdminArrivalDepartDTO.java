package com.fresh.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 运营端「发车」入参（F-63）：body 只有 {@code {storeId, batchDate, remark?}} 三键。
 * 明细数字不收任何入参——应发件数由系统按该店该日订单明细汇总（决策点 2：运营不填一行数字）。
 *
 * <p><b>batchDate 用 String 而非 LocalDate</b>：形状闸门走本面新增的 parseBatchDate（先正则后真日期），
 * 400 文案与运营端其余脏参同一句；Jackson 绑定失败走的是全局「参数格式不正确」另一条出口，
 * 两种入参形状并存反而多一套口径。{@code @NotNull} 只管键缺失，形状与「早于今天」的判据都在服务层。
 */
@Data
public class AdminArrivalDepartDTO {

    @NotNull(message = "storeId 不能为空")
    @Min(value = 1, message = "storeId 不合法")
    private Long storeId;

    @NotNull(message = "batchDate 不能为空")
    private String batchDate;

    /**
     * 批次备注（可空）。上限与 arrival_batch.remark VARCHAR(255) 同值——严格模式下超长是 1406 而非 400，
     * 必须在形状层拦（同 auditStoreApply 对 audit_remark 的列宽纪律）。
     */
    @Size(max = 255, message = "remark 不能超过 255 字")
    private String remark;
}
