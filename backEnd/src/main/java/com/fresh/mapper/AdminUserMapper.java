package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 运营账号 mapper（F-20/G-06 Step 1，表见 m19-migration.sql ① 与 init.sql 同步段）。
 *
 * <p><b>只有下面两条自定义语句，一条读、一条写，别的什么都不加</b>（本期 Step 1 只做表 + 实体 + Mapper，
 * 登录端点与双轨校验在 Step 2，前端在 Step 3~4）：
 * <ul>
 *   <li>{@link #selectByUsername} 是<b>全仓唯一显式读 {@code password_hash} 的一条手写 SQL，只给 Service 校验用</b>
 *       （D-5 的口令比对、D-2 的 status=active 判定），<b>任何出参路径不得带它</b>——列清单显式写死
 *       （不取 create_time/update_time，杜绝 {@code SELECT *}），出参由调用方重建白名单 Map
 *       （{@code {token, displayName, adminUserId}}，D-5），实体的哈希字段另有 {@code @JsonIgnore} 作第二道闸
 *       （照 Store:43 先例）；</li>
 *   <li>{@link #touchLastLogin} 只动 {@code last_login_at} 一列（D-5 登录成功链路），不碰其它列。</li>
 * </ul>
 *
 * <p><b>禁 {@code selectList}/{@code selectPage}/{@code Page<T>}</b>（RB 红线）：本工程未装 MP 分页插件，
 * 分页方法会静默返回全量且 {@code total=0}；本期也没有列表页，不预置任何批量读。
 *
 * <p><b>如实登记一条继承面</b>：继承 {@code BaseMapper} 后，{@code selectById}/{@code updateById} 一类通用方法
 * 生成的列清单里<b>也含 {@code password_hash}</b>（MP 按实体字段拼列，拦不住）。本表唯一的物理闸门因此是实体上的
 * {@code @JsonIgnore} + 「出参一律走白名单 Map」这条纪律；{@code insert} 保留给 Step 2 改密/建号的服务内路径，
 * <b>账号本身由主会话一次性 SQL 创建</b>——本仓不入库任何口令种子（见 m19 头部）。
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {

    /**
     * 按登录名取运营账号（<b>唯一允许读 password_hash 的一条，只给 Service 校验用，任何出参路径不得带它</b>）。
     *
     * <p>命中即返回，未命中返回 {@code null}——调用方（Step 2）须把「不存在」「口令不符」「status≠active」
     * 三种情况收敛为<b>同一句 401「用户名或密码错误」</b>（D-5 防枚举，绝不分支文案）。
     *
     * <p>走 {@code uk_admin_username} 唯一索引等值查，最多 1 行；{@code ORDER BY}/{@code LIMIT} 不需要。
     */
    @Select("SELECT id AS id, username AS username, password_hash AS passwordHash, display_name AS displayName, status AS status, last_login_at AS lastLoginAt FROM admin_user WHERE username = #{username}")
    AdminUser selectByUsername(@Param("username") String username);

    /**
     * 更新最近登录时间（D-5：登录<b>成功</b>链路调用，与 {@code admin_audit_log} 的 {@code admin.login} 同批）。
     *
     * <p>不做条件更新（本人本次登录已成功，重复执行结果同值=幂等覆盖）；返回影响行数（0=id 不存在，调用方记告警即可，
     * 审计与 last_login_at 都是登录的旁路，不得因为它失败而打断已签出的 token）。
     */
    @Update("UPDATE admin_user SET last_login_at = #{when} WHERE id = #{id}")
    int touchLastLogin(@Param("id") Long id, @Param("when") LocalDateTime when);
}
