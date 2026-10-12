package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运营账号 admin_user（F-20/G-06 Step 1，建表见 m19-migration.sql ① 与 init.sql 同步段，列口径=F-20.2 D-4 逐字）。
 *
 * <p><b>本类是本期唯一的实体出口，且本期没有任何出口</b>：Step 1 只建表 + 实体 + Mapper，
 * 登录端点 / 双轨校验 / 前端都在 Step 2~4，故这里不预置任何 DTO 或出参约定。
 *
 * <p><b>D-4 物理隔离</b>：运营域与 C 端域互不相通——零外键、无手机号、无邮箱，也不给 {@code user} 加 role。
 * {@code id} 与 {@code user.id} 都是 BIGINT 自增、小 id 必然并存，所以任何鉴权校验
 * <b>绝不允许只看 JWT subject</b>（D-6；须 {@code typ=admin} claim + 本表存在性 + status=active 三重判定，Step 2 落地）。
 *
 * <p><b>passwordHash 挂 {@code @JsonIgnore} 是纵深防御</b>（先例：{@link Store}:43 的 ownerUserId、
 * {@link StoreApply}:58 的 contactPhone——本仓唯一「直接返回实体」的 {@code GET /api/stores} 就是靠它兜住的）：
 * 本表本期没有任何「直接返回实体」的出口，但先例证明「靠调用方自觉」迟早失手，把「不出参」写在实体上才是零泄露面（RD）。
 * 读哈希的唯一通道是 {@code AdminUserMapper.selectByUsername} 那一条 {@code @Select}（只给 Service 校验用），
 * 出参路径按 F-20.4 走白名单 Map，永不带本字段。
 *
 * <p>{@code createTime}/{@code updateTime} 是<b>裸声明</b>、不加 {@code @TableField(fill = ..)}：
 * 两列由 DB {@code DEFAULT CURRENT_TIMESTAMP} / {@code ON UPDATE CURRENT_TIMESTAMP} 落值
 * （照 {@link OpsLog}:46 与 {@link Store}:73 的先例——{@code MybatisPlusConfig} 的 {@code strictInsertFill}
 * 只填带 fill 注解的字段，裸声明不会被 MP 覆盖，也不会把 null 写进列）。
 */
@Data
@TableName("admin_user")
public class AdminUser {

    /** 运营账号ID（BIGINT 自增）；与 user.id 同域不同表，绝不可互相代入（D-6） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 运营登录名 VARCHAR(50)，uk_admin_username 唯一；只用于登录定位账号，非 PII（D-4） */
    private String username;

    /** 口令哈希 VARCHAR(128) NOT NULL：PasswordUtil 的 PBKDF2WithHmacSHA256 现串，恒不明文（RD）；见类注释的读取通道约束 */
    @JsonIgnore
    private String passwordHash;

    /** 展示名 VARCHAR(50) NULL：管理界面显示用，NULL 时前端回退 username；不含 PII（D-4/RD） */
    private String displayName;

    /** 账号状态：active 可用 / disabled 停用（VARCHAR(20) NOT NULL DEFAULT 'active'，两态无第三态）；D-2 双轨只放行 active */
    private String status;

    /** 最近登录成功时间 DATETIME NULL：由 AdminUserMapper.touchLastLogin 在登录成功链路写入；NULL=从未成功登录 */
    private LocalDateTime lastLoginAt;

    /** 创建时间：DB DEFAULT CURRENT_TIMESTAMP 落值，实体不填、无 fill 注解（照 OpsLog:46 / Store:73） */
    private LocalDateTime createTime;

    /** 更新时间：DB ON UPDATE CURRENT_TIMESTAMP 自动维护，应用侧不显式赋值 */
    private LocalDateTime updateTime;
}
