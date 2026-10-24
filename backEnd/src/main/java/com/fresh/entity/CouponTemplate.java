package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 券模板（F-113 表化）：从 CouponServiceImpl.TEMPLATES 代码常量迁 coupon_template 表，
 * 表为唯一真源（常量与 isLifetimeOnce 已删，双源必漂移）。
 * 发放时 threshold/amount/expireAt(=now+validDays) 全取表行快照落 user_coupon，
 * 核销走 user_coupon 行上快照不回读模板——运营改面额只影响之后的新领取（三道防线的第二道）。
 */
@Data
@TableName("coupon_template")
public class CouponTemplate {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 券模板标识（C 端 claim/center 的 key）：coupon_1/coupon_2/coupon_3/newbie_gift + 运营上新 */
    private String couponKey;
    /** 展示名；NULL 时前端按满X减Y拼（center 出参原样透传，C 端不读不影响） */
    private String title;
    /** 使用门槛（满 X 元可用） */
    private BigDecimal threshold;
    /** 抵扣金额（减 Y 元）；运营端校验 amount &lt; threshold（抵扣≥门槛=负价单） */
    private BigDecimal amount;
    /** 领取后有效天数：claim 写 expire_at = now + valid_days（不再引用 UserCoupon.VALID_DAYS） */
    private Integer validDays;
    /** 每人全史限领 N 张（含 used/expired）；0=不限——1 时代码路径与常量时代 lifetime 语义逐字等价 */
    private Integer limitPerUser;
    /** 总量配额；NULL=不限 */
    private Integer totalQuota;
    /** 已发计数（配额原子扣减用：条件 UPDATE issued_count+1 WHERE issued_count&lt;total_quota） */
    private Integer issuedCount;
    /** active 启用 / disabled 停用（claim/center 均不可见；停用即运营语义，无 DELETE） */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
