package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户实体（T-M2-01）：手机号登录账号，新手机号登录时静默注册（user 表 phone UNIQUE）。
 */
@Data
@TableName("user")
public class User {

    /** 用户ID：数据库自增（user.id AUTO_INCREMENT） */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;

    /** 密码哈希（T-M5）：PBKDF2 存储串（PasswordUtil 编码），NULL=未设置过密码（仅验证码登录） */
    private String passwordHash;

    /**
     * 邀请码（F-01，m9-migration.sql 加列）：8 位去混淆字符集（无 0/O/1/I），uk_user_invite_code UNIQUE；
     * NULL=尚未生成（存量用户不回填），惰性生成写回（新用户注册分支 / 首次 GET /api/invite/me，F-01.0 裁定）
     */
    private String inviteCode;

    /**
     * 邀请现金余额（F-04，m11-migration.sql 加列，独立钱包不与下单抵扣打通 F-04.5）：
     * 注册只冻结不入账（F-04.2 修订②，不产生任何可提现金额），仅在新人首单支付后由入账挂钩
     * 原子累加（UserMapper#addBalance）；提现按「满 20 提全部余额」原子清零（UserMapper#clearBalanceIfEnough）
     */
    private BigDecimal balance;

    /**
     * 已发邀请现金人数计数（F-04，m11-migration.sql 加列，封顶原子闸门）：
     * 冻结发放时条件自增（UserMapper#increaseCashRewarded，WHERE cash_rewarded<10），
     * 替代 COUNT→INSERT 的 check-then-act，并发注册绕不过 10 人上限（F-04.1 修订）
     */
    private Integer cashRewarded;

    /**
     * 头像（F-06.7.4 R8，m14-migration.sql 加列）：data URI（data:image/jpeg|png|webp;base64,...），
     * NULL=未设置（前端兜默认 SVG 头像）；写入仅经 PUT /api/user/avatar（前缀+长度校验， UserController）
     */
    private String avatar;
    private String role;
    private Long merchantId;

    private LocalDateTime createdAt;
}
