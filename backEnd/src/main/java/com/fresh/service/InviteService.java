package com.fresh.service;

import com.fresh.dto.InviteCashVO;
import com.fresh.entity.User;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 邀请新人服务（F-01，双边拉新）：被邀新人注册即得新人券，邀请人在新人首单支付成功后得奖励券。
 * 奖励时点与绑定时机见 F-01.0 裁定（docs/上线任务文档.md 迭代任务 F-01）。
 */
public interface InviteService {

    /**
     * 邀请主页数据（契约 F-01.2 GET /api/invite/me）：
     * 惰性生成邀请码（首次调用生成写回，存量用户不回填）+ 统计 + 记录列表。
     *
     * @return {inviteCode, inviteUrl, invitedCount, completedCount,
     *         records:[{phoneMasked, statusText, createdAt, rewardedAt}]}（records 按 created_at desc 上限 50）
     */
    Map<String, Object> inviteInfo(Long userId);

    /**
     * 邀请码惰性生成：已生成则原样返回；否则生成 8 位去混淆码（无 0/O/1/I）查重写回
     * （DB uk_user_invite_code 唯一键兜底并发重码）。新用户注册分支与 /api/invite/me 共用。
     *
     * @param user 当前用户（实体上的 inviteCode 会被同步刷新，便于调用方直接使用）
     * @return 生效的邀请码；连续冲突等极端场景返回 null（调用方按需兜底）
     */
    String ensureInviteCode(User user);

    /**
     * 新用户注册绑定（契约 F-01.2，必须在验证码登录建号的同一事务内调用）：
     * 邀请码有效（能查到邀请人且非本人）→ insert invite_relation + 给新人发 newbie_gift 券；
     * 无效邀请码/老用户/已被邀请过（uk_invitee 冲突）一律静默忽略，正常注册不报错。
     */
    void bindOnRegister(User invitee, String inviteCode);

    /**
     * 支付成功挂钩（契约 F-01.2，在支付置 pay_status=1 的同一事务内调用）：
     * 该 userId 已支付订单数==1（首单）→ 找 invite_relation(invitee_id, status=0)
     * → 原子置位 status 0→1（rows==1 才发券，防并发重复发券）→ 邀请人发 invite_reward 券；
     * 邀请人累计奖励超 INVITE_REWARD_MAX 封顶则照常置位但不发券（仅日志）。
     * F-04.2 入账挂钩扩展：置位成功后同时把该新人名下冻结现金流水 status 0→1 入账
     * （rows==1 才给邀请人 balance+5，注册只冻结不入账，F-04.2 修订②）。
     */
    void onOrderPaid(Long inviteeUserId);

    /**
     * 邀请赚现金主页（契约 F-04.2 GET /api/invite/cash）：
     * data = {balance, frozenTotal, totalEarned, canWithdraw, withdrawMin:20,
     * flows:[{type,status,amount,balanceAfter,inviteeId,remark,createdAt}]}（流水 created_at 倒序上限 50）。
     * 注册只冻结不入账（F-04.2 修订②）：balance 仅含已入账现金，冻结部分在 frozenTotal 单列。
     */
    InviteCashVO cashInfo(Long userId);

    /**
     * 提现（契约 F-04.2 POST /api/invite/cash/withdraw，登录态 + @RateLimit 1 次/分钟）：
     * 定稿口径=满 WITHDRAW_MIN(20) 可提现**全部余额**（F-04.2 修订③）——事务内先读余额，
     * 条件 UPDATE 清零（WHERE balance&gt;=20）rows==1 才写 type=2 流水（amount=全部余额、balance_after=0）；
     * 不足 20 抛 BizException 差额文案（「还差 X 元可提现」）；并发双提由条件 UPDATE 防住（第二笔 rows==0 不写流水）。
     *
     * @return 本次提现金额（提现时的全部余额，BigDecimal）
     */
    BigDecimal withdraw(Long userId);
}
