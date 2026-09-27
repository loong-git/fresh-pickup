package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.dto.InviteCashVO;
import com.fresh.entity.CashFlow;
import com.fresh.entity.InviteRelation;
import com.fresh.entity.Order;
import com.fresh.entity.User;
import com.fresh.mapper.CashFlowMapper;
import com.fresh.mapper.InviteRelationMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.service.CouponService;
import com.fresh.service.InviteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 邀请新人服务实现（F-01 券轨 + F-04 现金轨，共用同一套邀请关系）：
 * - 邀请码：8 位去混淆字符集（去除 0/O/1/I），惰性生成（新用户注册分支与首次 GET /api/invite/me），
 *   查重写回 + DB uk_user_invite_code 唯一键兜底并发重码（F-01.0/F-01.2）；
 * - 绑定：验证码登录建新号同一事务内写 invite_relation + 发新人券（newbie_gift）+ 冻结邀请人现金
 *   （freezeCashToInviter，F-04.2 修订②：注册只冻结不入账，堵脚本批量注册薅现金），
 *   uk_invitee 一人仅可被邀请一次，重复/无效邀请码静默忽略（老用户与密码登录不进此链路，F-01.0）；
 * - 奖励（券轨）：新人首单支付（已支付订单数==1）→ status 0→1 原子置位（rows==1 才发券，防并发重复发券）
 *   → 邀请人发 invite_reward，累计奖励 INVITE_REWARD_MAX 封顶（超限照常置位仅跳过发券，F-01.0）；
 * - 入账（现金轨，F-04.2）：同一置位点后把冻结现金流水 status 0→1 入账（rows==1 才 balance+5），
 *   现金封顶在注册冻结时已由 cash_rewarded 原子闸门把守，与券轨 20 人封顶互不影响；
 * - 提现（F-04.2 修订③）：满 WITHDRAW_MIN 提现全部余额，条件 UPDATE 原子清零 rows==1 才写 type=2 流水；
 * - 展示名：invite_reward=邀请奖励券、newbie_gift=新人见面礼（与 CouponServiceImpl.TEMPLATES 注释一致）；
 *   手机号一律脱敏快照（invitee_phone 落库即脱敏，表内无原值，F-01.0 攻击面裁定）；
 *   提现文案/流水 remark 不出现内部词 mock（F-04.2 修订④）。
 */
@Slf4j
@Service
public class InviteServiceImpl implements InviteService {

    /** 邀请奖励封顶（F-01.0 裁定）：邀请人累计奖励 20 人后再有新人完成首单，status 照常置 1 但不再发券 */
    private static final int INVITE_REWARD_MAX = 20;

    /** 邀请现金单人次金额（F-04.1）：5.00 元/新人 */
    private static final BigDecimal CASH_REWARD_AMOUNT = new BigDecimal("5.00");

    /** 邀请现金封顶（F-04.0/F-04.1 修订）：cash_rewarded 计数列原子闸门上限，满 10 人后再有新人注册只绑定不发冻结流水 */
    private static final int CASH_REWARD_MAX = 10;

    /** 提现门槛（F-04.0 修订定稿）：满 20 可提现全部余额 */
    private static final BigDecimal WITHDRAW_MIN = new BigDecimal("20.00");

    /** 提现流水备注（F-04.2 修订④：不出现内部词 mock，模拟到账语义仅存后端注释） */
    private static final String REMARK_WITHDRAW = "提现";

    /** 邀请码长度（F-01.0：8 位） */
    private static final int CODE_LENGTH = 8;
    /** 去混淆字符集（F-01.0：去除 0/O/1/I，32 字符） */
    private static final String CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    /** 生成重试次数：查重+唯一键兜底下的竞态重试上界（32^8 组合，撞满 5 次概率可忽略） */
    private static final int CODE_MAX_RETRY = 5;

    /** 奖励券模板 key（面额定义唯一真源 = CouponServiceImpl.TEMPLATES，F-01.0 裁定） */
    private static final String COUPON_INVITE_REWARD = "invite_reward";
    private static final String COUPON_NEWBIE_GIFT = "newbie_gift";

    /** 邀请链接相对路径（契约 F-01.2：后端拼相对路径，前端补 origin） */
    private static final String INVITE_URL_TEMPLATE = "#/pages/login/login?invite=";

    /** 记录列表上限（契约 F-01.2：records 按 created_at desc 上限 50） */
    private static final int RECORDS_LIMIT = 50;

    /** status 文案（契约 F-01.2/F-01.4：待完成首单 / 已完成） */
    private static final String STATUS_TEXT_PENDING = "待完成首单";
    private static final String STATUS_TEXT_REWARDED = "已完成";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private InviteRelationMapper inviteRelationMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private CashFlowMapper cashFlowMapper;
    @Autowired
    private CouponService couponService;

    @Override
    public Map<String, Object> inviteInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // userId 来自已验证 token，正常必命中；兜底防静默失败
            throw new BizException("用户不存在，请重新登录");
        }
        // 惰性发码（契约 F-01.2）：存量用户首次调用时生成写回，此后幂等复用
        String inviteCode = ensureInviteCode(user);
        if (inviteCode == null) {
            // 唯一键兜底下连续冲突概率可忽略（32^8），触发即提示重试，不静默返回空码
            throw new BizException("邀请码生成失败，请稍后重试");
        }

        // 统计全量（records 仅展示前 50 条，计数不受列表截断影响）
        Long invitedCount = inviteRelationMapper.selectCount(
                new LambdaQueryWrapper<InviteRelation>().eq(InviteRelation::getInviterId, userId));
        Long completedCount = inviteRelationMapper.selectCount(new LambdaQueryWrapper<InviteRelation>()
                .eq(InviteRelation::getInviterId, userId)
                .eq(InviteRelation::getStatus, InviteRelation.STATUS_REWARDED));
        List<InviteRelation> relations = inviteRelationMapper.selectList(
                new LambdaQueryWrapper<InviteRelation>()
                        .eq(InviteRelation::getInviterId, userId)
                        .orderByDesc(InviteRelation::getCreatedAt)
                        .last("LIMIT " + RECORDS_LIMIT));

        List<Map<String, Object>> records = new ArrayList<>();
        for (InviteRelation relation : relations) {
            Map<String, Object> record = new HashMap<>();
            record.put("phoneMasked", relation.getInviteePhone());
            record.put("statusText", relation.getStatus() != null
                    && relation.getStatus() == InviteRelation.STATUS_REWARDED
                    ? STATUS_TEXT_REWARDED : STATUS_TEXT_PENDING);
            record.put("createdAt", relation.getCreatedAt());
            record.put("rewardedAt", relation.getRewardedAt());
            records.add(record);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("inviteCode", inviteCode);
        data.put("inviteUrl", INVITE_URL_TEMPLATE + inviteCode);
        data.put("invitedCount", invitedCount == null ? 0 : invitedCount);
        data.put("completedCount", completedCount == null ? 0 : completedCount);
        data.put("records", records);
        return data;
    }

    @Override
    public String ensureInviteCode(User user) {
        if (user.getInviteCode() != null && !user.getInviteCode().isBlank()) {
            return user.getInviteCode();
        }
        for (int i = 0; i < CODE_MAX_RETRY; i++) {
            String code = randomCode();
            // 查重写回（契约 F-01.2）；并发竞态由 uk_user_invite_code 唯一键兜底（DuplicateKey 后重试）
            if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getInviteCode, code)) > 0) {
                continue;
            }
            try {
                userMapper.update(null, new LambdaUpdateWrapper<User>()
                        .eq(User::getId, user.getId())
                        .set(User::getInviteCode, code));
                user.setInviteCode(code);
                return code;
            } catch (DuplicateKeyException e) {
                log.info("邀请码并发冲突，重新生成: userId={}", user.getId());
            }
        }
        log.warn("邀请码生成连续冲突，留待下次调用重试: userId={}", user.getId());
        return null;
    }

    @Override
    public void bindOnRegister(User invitee, String inviteCode) {
        String code = inviteCode == null ? "" : inviteCode.trim();
        if (code.isEmpty()) {
            return;
        }
        User inviter = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getInviteCode, code));
        if (inviter == null) {
            // 无效邀请码：正常注册不绑定不报错（F-01.4 验收 9）
            log.info("邀请码无效，忽略绑定: inviteePhone={}, inviteCode={}", mask(invitee.getPhone()), code);
            return;
        }
        if (inviter.getId().equals(invitee.getId())) {
            log.info("邀请码为本人所有，忽略绑定: userId={}", invitee.getId());
            return;
        }
        InviteRelation relation = new InviteRelation();
        relation.setInviterId(inviter.getId());
        relation.setInviteeId(invitee.getId());
        // 落库即脱敏（F-01.0 攻击面裁定）：表内无原手机号，无枚举面
        relation.setInviteePhone(mask(invitee.getPhone()));
        relation.setStatus(InviteRelation.STATUS_PENDING);
        try {
            inviteRelationMapper.insert(relation);
        } catch (DuplicateKeyException e) {
            // uk_invitee 一人仅可被邀请一次（F-01.0）：重复携带邀请码登录不重复绑定、不重复发券
            log.info("该用户已被邀请过，忽略重复绑定: inviteeId={}", invitee.getId());
            return;
        }
        // 新人见面礼（F-01.0 奖励时点裁定：注册即发），expire_at=now+7d，同 bindOnRegister 调用事务；
        // 展示名约定：newbie_gift=新人见面礼（与前端 mine 页券名映射一致）
        couponService.grant(invitee.getId(), COUPON_NEWBIE_GIFT);
        // 邀请赚现金（F-04.2 评审修订②，绑定成功后同事务执行）：注册只冻结不入账——
        // 不动 user.balance、不产生任何可提现金额，新人首单支付后由 onOrderPaid 挂钩入账
        freezeCashToInviter(inviter.getId(), invitee.getId());
        log.info("邀请绑定成功，已发新人见面礼(newbie_gift): inviterId={}, inviteePhone={}",
                inviter.getId(), mask(invitee.getPhone()));
    }

    @Override
    public void onOrderPaid(Long inviteeUserId) {
        // 首单判定（契约 F-01.2）：支付事务内当前单已置 pay_status=1，故已支付订单数==1 即首单；
        // 第二笔起订单数>1 不触发（幂等）
        Long paidCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, inviteeUserId)
                .eq(Order::getPayStatus, 1));
        if (paidCount == null || paidCount != 1) {
            return;
        }
        InviteRelation relation = inviteRelationMapper.selectOne(new LambdaQueryWrapper<InviteRelation>()
                .eq(InviteRelation::getInviteeId, inviteeUserId)
                .eq(InviteRelation::getStatus, InviteRelation.STATUS_PENDING));
        if (relation == null) {
            return;
        }
        // 原子置位防并发重复发券（契约 F-01.2 幂等条款）：status 0→1 仅一次，rows==1 才发券
        int updated = inviteRelationMapper.update(null, new LambdaUpdateWrapper<InviteRelation>()
                .eq(InviteRelation::getId, relation.getId())
                .eq(InviteRelation::getStatus, InviteRelation.STATUS_PENDING)
                .set(InviteRelation::getStatus, InviteRelation.STATUS_REWARDED)
                .set(InviteRelation::getRewardedAt, LocalDateTime.now()));
        if (updated != 1) {
            return;
        }
        // 邀请现金入账挂钩（F-04.2，同一置位点）：冻结流水 status 0→1 入账 rows==1 才给邀请人 balance+5。
        // 置于券轨封顶判定之前：现金封顶在注册冻结时已由 cash_rewarded 闸门把守（有冻结流水即当时未封顶），
        // 邀请人券轨超 20 人封顶不影响其应得现金入账
        creditFrozenCash(relation.getInviterId(), inviteeUserId);
        // 封顶（F-01.0）：统计含本条，累计奖励已达上限即第 MAX+1 人起不再发券（仅日志，无独立提示）
        Long rewardedCount = inviteRelationMapper.selectCount(new LambdaQueryWrapper<InviteRelation>()
                .eq(InviteRelation::getInviterId, relation.getInviterId())
                .eq(InviteRelation::getStatus, InviteRelation.STATUS_REWARDED));
        if (rewardedCount != null && rewardedCount > INVITE_REWARD_MAX) {
            log.info("邀请人奖励已达上限{}，跳过发券: inviterId={}, inviteeId={}",
                    INVITE_REWARD_MAX, relation.getInviterId(), inviteeUserId);
            return;
        }
        couponService.grant(relation.getInviterId(), COUPON_INVITE_REWARD);
        // 展示名约定：invite_reward=邀请奖励券（与前端 mine 页券名映射一致）
        log.info("首单邀请奖励发放完成，已发邀请奖励券(invite_reward): inviteeId={}, inviterId={}",
                inviteeUserId, relation.getInviterId());
    }

    @Override
    public InviteCashVO cashInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // userId 来自已验证 token，正常必命中；兜底防静默失败
            throw new BizException("用户不存在，请重新登录");
        }
        // 余额一律 BigDecimal（DECIMAL(10,2)，映射不会为 NULL，兜底防御）
        BigDecimal balance = user.getBalance() == null ? BigDecimal.ZERO : user.getBalance();
        List<CashFlow> flows = cashFlowMapper.selectList(new LambdaQueryWrapper<CashFlow>()
                .eq(CashFlow::getUserId, userId)
                .orderByDesc(CashFlow::getCreatedAt)
                .orderByDesc(CashFlow::getId)
                .last("LIMIT " + RECORDS_LIMIT));
        InviteCashVO vo = new InviteCashVO();
        vo.setBalance(balance);
        vo.setFrozenTotal(cashFlowMapper.sumAmount(userId, CashFlow.TYPE_REWARD, CashFlow.STATUS_FROZEN));
        vo.setTotalEarned(cashFlowMapper.sumAmount(userId, CashFlow.TYPE_REWARD, CashFlow.STATUS_CREDITED));
        vo.setCanWithdraw(balance.compareTo(WITHDRAW_MIN) >= 0);
        vo.setWithdrawMin(WITHDRAW_MIN);
        vo.setFlows(flows.stream().map(InviteServiceImpl::toFlow).toList());
        return vo;
    }

    @Override
    @Transactional // 提现清零与流水写入同事务：流水写失败整体回滚，防"钱扣了没流水"半态
    public BigDecimal withdraw(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在，请重新登录");
        }
        BigDecimal balance = user.getBalance() == null ? BigDecimal.ZERO : user.getBalance();
        if (balance.compareTo(WITHDRAW_MIN) < 0) {
            // 差额文案（契约 F-04.2）：「还差 X 元可提现」，两位小数与全站金额口径一致
            BigDecimal gap = WITHDRAW_MIN.subtract(balance).setScale(2, RoundingMode.HALF_UP);
            throw new BizException("还差 " + gap.toPlainString() + " 元可提现");
        }
        // 条件 UPDATE 原子清零（F-04.2 修订③ 定稿口径：满 20 提现全部余额）：balance>=20 才置 0，
        // rows==1 才写流水；并发双提第二笔 rows==0 不写流水（行锁串行化），余额不透支
        int rows = userMapper.clearBalanceIfEnough(userId, WITHDRAW_MIN);
        if (rows != 1) {
            // 同事务先读后写窗口内余额被并发提走/变动：不给成功语义，引导刷新重试
            throw new BizException("余额已变动，请刷新后重试");
        }
        CashFlow flow = new CashFlow();
        flow.setUserId(userId);
        flow.setType(CashFlow.TYPE_WITHDRAW);
        flow.setStatus(CashFlow.STATUS_CREDITED);
        flow.setAmount(balance); // 提现时全部余额（满 20 提全部，F-04.2 修订③，账面与流水对得上）
        flow.setBalanceAfter(BigDecimal.ZERO);
        flow.setRemark(REMARK_WITHDRAW);
        cashFlowMapper.insert(flow);
        // 演示环境为模拟到账（立即成功、不发生实际打款，F-04.5）：该语义仅存后端注释与环境判断，
        // remark 与接口文案不出现内部词 mock（F-04.2 修订④），到账明示由前端页面承担（F-04.3）；
        // TODO wx 商家转账：接入后此处替换为调微信商家转账 API（发起转账 → 用户确认收款，异步打款），
        //      打款受理/失败状态需回写流水并与余额快照对账，与 WxPayService 骨架占位风格一致；
        //      接入前须把「提现入口开放状态与到账明示」列入上线 Checklist 核对（F-04.5）
        log.info("提现成功: userId={}, amount={}, balanceAfter=0", userId, balance);
        return balance;
    }

    /**
     * 冻结邀请现金到邀请人（F-04.2 评审修订②，原 rewardCashToInviter 更名）：
     * 原子闸门 UPDATE user SET cash_rewarded=cash_rewarded+1 WHERE id=? AND cash_rewarded&lt;10
     * （cash_rewarded 计数列为封顶原子闸门，替代原「COUNT&lt;10 → INSERT」check-then-act——COUNT 与 INSERT
     * 无锁无原子性，并发注册可绕过 10 人上限）；rows==1 → INSERT cash_flow(type=1, amount=5.00,
     * status=0 冻结, balance_after=当前余额快照（未变动）, invitee_id)；rows==0 记日志跳过（绑定照常）。
     * 注册时不加 balance、不产生任何可提现金额；入账延后至新人首单支付（onOrderPaid 挂钩，对齐 F-01.0 防刷裁定）。
     * 事务边界：随 AuthServiceImpl#login 注册事务（REQUIRED）执行，本方法不单开事务。
     */
    private void freezeCashToInviter(Long inviterId, Long inviteeId) {
        int rows = userMapper.increaseCashRewarded(inviterId, CASH_REWARD_MAX);
        if (rows != 1) {
            log.info("邀请现金发放已达上限{}人，跳过冻结（绑定照常）: inviterId={}, inviteeId={}",
                    CASH_REWARD_MAX, inviterId, inviteeId);
            return;
        }
        User inviter = userMapper.selectById(inviterId);
        BigDecimal balanceAfter = inviter == null || inviter.getBalance() == null
                ? BigDecimal.ZERO : inviter.getBalance();
        CashFlow flow = new CashFlow();
        flow.setUserId(inviterId);
        flow.setType(CashFlow.TYPE_REWARD);
        flow.setStatus(CashFlow.STATUS_FROZEN);
        flow.setAmount(CASH_REWARD_AMOUNT);
        flow.setBalanceAfter(balanceAfter);
        flow.setInviteeId(inviteeId);
        cashFlowMapper.insert(flow);
        log.info("邀请现金已冻结（新人首单支付后入账）: inviterId={}, inviteeId={}, amount={}",
                inviterId, inviteeId, CASH_REWARD_AMOUNT);
    }

    /**
     * 新人首单支付后入账冻结现金（F-04.2 入账挂钩，复用 F-01 onOrderPaid 置位点）：
     * UPDATE cash_flow SET status=1 WHERE invitee_id=? AND type=1 AND status=0 条件置位防并发重复入账
     * （uk_invitee 下每个新人至多一条冻结流水，rows∈{0,1}，rows!=1 直接返回，范式同 invite_relation 0→1 置位）；
     * rows==1 → UPDATE user SET balance=balance+5（UserMapper#addBalance 原子累加）。
     * 事务边界：随 MockPayService#payResult 支付事务（REQUIRED）执行，本方法不单开事务。
     */
    private void creditFrozenCash(Long inviterId, Long inviteeId) {
        int rows = cashFlowMapper.update(null, new LambdaUpdateWrapper<CashFlow>()
                .eq(CashFlow::getInviteeId, inviteeId)
                .eq(CashFlow::getType, CashFlow.TYPE_REWARD)
                .eq(CashFlow::getStatus, CashFlow.STATUS_FROZEN)
                .set(CashFlow::getStatus, CashFlow.STATUS_CREDITED));
        if (rows != 1) {
            // 无冻结流水（未走邀请注册/已入账/并发已处理）一律静默返回，券轨照常
            return;
        }
        userMapper.addBalance(inviterId, CASH_REWARD_AMOUNT);
        log.info("邀请现金已入账: inviterId={}, inviteeId={}, amount={}", inviterId, inviteeId, CASH_REWARD_AMOUNT);
    }

    /** 实体 → 流水展示项（契约 F-04.2 字段：type/status/amount/balanceAfter/remark/createdAt + inviteeId） */
    private static InviteCashVO.Flow toFlow(CashFlow flow) {
        InviteCashVO.Flow item = new InviteCashVO.Flow();
        item.setType(flow.getType());
        item.setStatus(flow.getStatus());
        item.setAmount(flow.getAmount());
        item.setBalanceAfter(flow.getBalanceAfter());
        item.setInviteeId(flow.getInviteeId());
        item.setRemark(flow.getRemark());
        item.setCreatedAt(flow.getCreatedAt());
        return item;
    }

    /** 随机 8 位邀请码（去混淆字符集，SecureRandom 防猜测） */
    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** 138****1235（口径同 AuthServiceImpl#mask：phone 恒 11 位，substring 安全） */
    private String mask(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
