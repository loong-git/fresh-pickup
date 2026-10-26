package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 邀请现金封顶原子闸门（F-04.1 评审修订）：cash_rewarded 计数列条件自增，
     * cash_rewarded &lt; maxCount 不满足时影响行数为 0，并发注册由行锁串行化判定——
     * 替代原「SELECT COUNT(*) &lt; 10 → INSERT」check-then-act（COUNT 与 INSERT 无锁无原子性，可被并发绕过）。
     * 影响行数=1 → 上层写冻结流水；=0 → 已满 CASH_REWARD_MAX 封顶，记日志跳过（绑定照常）。
     */
    @Update("UPDATE user SET cash_rewarded = cash_rewarded + 1 WHERE id = #{id} AND cash_rewarded < #{maxCount}")
    int increaseCashRewarded(@Param("id") Long id, @Param("maxCount") int maxCount);

    /** 邀请现金入账（F-04.2 入账挂钩）：新人首单支付后原子累加，与 cash_flow status 0→1 置位同事务防半态 */
    @Update("UPDATE user SET balance = balance + #{amount} WHERE id = #{id}")
    int addBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 提现原子清零（F-04.2 修订③ 定稿口径：满 20 提现全部余额）：balance >= minBalance 才置 0，
     * 并发双提第二笔影响行数 0 → 上层不写流水、不透支；行锁串行化保证扣减与流水一致。
     */
    @Update("UPDATE user SET balance = 0 WHERE id = #{id} AND balance >= #{minBalance}")
    int clearBalanceIfEnough(@Param("id") Long id, @Param("minBalance") BigDecimal minBalance);

    /**
     * 头像写入（F-06.7.4 R8）：avatar 传 null 即恢复默认（列置 NULL）；
     * 值的格式（data:image/(jpeg|png|webp);base64, 前缀）与长度（≤60000 字符，与列 TEXT 上限对齐）
     * 由 UserController 入口校验，本语句不做条件更新（本人操作幂等覆盖）
     */
    @Update("UPDATE user SET avatar = #{avatar} WHERE id = #{id}")
    int updateAvatar(@Param("id") Long id, @Param("avatar") String avatar);

    /**
     * 运营端用户列表的筛选 WHERE（F-122 §3.4.1，页与 COUNT 两条 SQL 逐字共用）：
     * 三参为 null 即短路为全部；phone 是<b>精确等值</b>（不做 LIKE、不在 SQL 里掩码），
     * 非 null 值由 Service 校验/归一后才绑定。表名按契约 §3.4.3 用反引号包住 {@code `user`}。
     */
    String USER_LIST_WHERE = "WHERE (#{userId} IS NULL OR id = #{userId})"
            + " AND (#{phone} IS NULL OR phone = #{phone})"
            + " AND (#{status} IS NULL OR status = #{status})";

    /**
     * 运营端用户列表的一页（F-122 §3.4.1，{@code GET /api/admin/users}）：
     * 只 SELECT 白名单六列——{@code password_hash/invite_code/balance/cash_rewarded/avatar}
     * 一律<b>不查询</b>（把「不输出」写成「不查询」）；phone 原文交 Service 过 {@code PhoneMask.mask}。
     * 本工程无 MP 分页插件（{@code selectPage}/{@code Page<T>} 会静默回全量且 total=0），页面与
     * COUNT 走两条手写 SQL；{@code ORDER BY id DESC} 由自增主键保证分页全序。
     */
    @Select("SELECT id AS id, phone AS phone, role AS role, merchant_id AS merchantId, status AS status, "
            + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt "
            + "FROM `user` " + USER_LIST_WHERE + " ORDER BY id DESC LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectUsersPage(@Param("userId") Long userId,
                                              @Param("phone") String phone,
                                              @Param("status") String status,
                                              @Param("offset") long offset,
                                              @Param("size") int size);

    /** 运营端用户总数（独立 COUNT）：WHERE 与 {@link #selectUsersPage} 逐字相同 */
    @Select("SELECT COUNT(*) FROM `user` " + USER_LIST_WHERE)
    long countUsers(@Param("userId") Long userId,
                    @Param("phone") String phone,
                    @Param("status") String status);
}
