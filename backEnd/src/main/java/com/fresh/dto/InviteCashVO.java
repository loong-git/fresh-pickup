package com.fresh.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 邀请赚现金接口响应体（契约 F-04.2，GET /api/invite/cash）：
 * 金额一律 BigDecimal（DECIMAL(10,2) 两位小数）；canWithdraw = balance >= withdrawMin
 * （提现口径 F-04.2 修订③：满 20 可提现全部余额）；flows 为现金流水倒序上限 50 条，
 * type/status 由前端映射标签（冻结中|已入账|提现），接口只下发原始值不透出内部词（F-04.2 修订④）。
 */
@Data
public class InviteCashVO {

    /** 当前可提现余额（= user.balance，仅含已入账现金；注册冻结部分不入此列，F-04.2 修订②） */
    private BigDecimal balance;

    /** 冻结中金额（type=1 AND status=0 SUM，新人完成首单后到账） */
    private BigDecimal frozenTotal;

    /** 累计已入账（type=1 AND status=1 SUM） */
    private BigDecimal totalEarned;

    /** 是否可提现（balance >= withdrawMin） */
    private Boolean canWithdraw;

    /** 提现门槛（WITHDRAW_MIN=20.00，满 20 可提现全部余额） */
    private BigDecimal withdrawMin;

    /** 现金流水（created_at 倒序上限 50，id 倒序兜底同秒排序稳定） */
    private List<Flow> flows;

    /** 单条现金流水（契约 F-04.2：{type,status,amount,balanceAfter,remark,createdAt}，另加 inviteeId 供 F-04.4 DoD#2 新人对应关系核验） */
    @Data
    public static class Flow {

        /** 1=邀请奖励 2=提现 */
        private Integer type;

        /** 1=已入账 0=冻结（type=2 恒为 1；前端映射：冻结中|已入账|提现） */
        private Integer status;

        /** 正数金额；type=2 提现为支出方向（前端按类型显示 ±） */
        private BigDecimal amount;

        /** 变动后余额快照（type=1 冻结时=当时余额未变动；type=2 提现后=0） */
        private BigDecimal balanceAfter;

        /** type=1 时的新人 user.id（提现为 null） */
        private Long inviteeId;

        /** 备注（提现=「提现」，不透出内部词，F-04.2 修订④） */
        private String remark;

        /** 创建时间 */
        private LocalDateTime createdAt;
    }
}
