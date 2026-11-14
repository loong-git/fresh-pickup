package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.entity.CashFlow;
import com.fresh.entity.InviteRelation;
import com.fresh.entity.Order;
import com.fresh.entity.User;
import com.fresh.mapper.CashFlowMapper;
import com.fresh.mapper.InviteRelationMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-2 审计轮 3 P1 缺口 #2：邀请现金资金域全链补测（InviteServiceImpl 全文实读，资金语义此前零测试）。
 *
 * <p>照 OrderCreateIdempotentOwnerTest 的桩范式：不起容器、不落库、{@code @Autowired} 字段逐个灌 mock；
 * onOrderPaid 的 0→1 置位与 creditFrozenCash 的冻结入账都带 {@code LambdaUpdateWrapper.set()}，
 * 列名解析依赖实体 TableInfo，{@code @BeforeAll} 手工灌内存缓存（User/InviteRelation/Order/CashFlow）。
 *
 * <p>覆盖面（8 例）：
 * ① freezeCashToInviter 原子封顶：increaseCashRewarded 条件 UPDATE 0 行（cash_rewarded≥10 闸门）
 *    → 不写冻结流水、绑定关系照常落（绑定不受封顶影响）；
 * ② 冻结成功落流水：type=1/status=0/amount=5.00/balanceAfter=当时余额快照/inviteeId 挂钩，
 *    且注册只冻结不入账（addBalance never）；invitee_phone 落库即脱敏；
 * ③ withdraw 差额校验：余额 15.00 → 400「还差 5.00 元可提现」（两位小数差额文案），零写库；
 * ④ clearBalanceIfEnough rows!=1（并发双提窗口）→ 400「余额已变动，请刷新后重试」，不写流水；
 * ⑤ 提现成功清零 + 全链两条流水：绑定冻结流水①（type=1 冻结）与提现流水②（type=2 已入账、
 *    amount=全部余额、balanceAfter=0、remark=「提现」且不含内部词 mock）；
 * ⑥ bindOnRegister 自邀请拒绝（inviter==invitee）静默返回、零写库；
 * ⑦ 重复绑定拒绝：insert 撞 uk_invitee（DuplicateKeyException）→ 静默返回、不重复发激励；
 * ⑧ onOrderPaid 发放路径（最小可测面）：已支付订单数==1 → 关系 0→1 原子置位 → 冻结流水
 *    0→1 入账 → addBalance(邀请人, 5.00)。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库 uk_invitee/uk_user_invite_code 并发撞键、
 * 条件 UPDATE 的真库行数语义、事务边界（withdraw 的 @Transactional 真滚回）——由主会话终验兜底。
 */
class InviteCashServiceTest {

    private static final Long INVITER_ID = 100L;
    private static final Long INVITEE_ID = 200L;
    private static final String INVITE_CODE = "ABCD2345";
    private static final String INVITEE_PHONE = "13800001235";
    /** 与主代码 CASH_REWARD_MAX=10 一致（实读口径，闸门参数钉死在此） */
    private static final int CASH_REWARD_MAX = 10;
    private static final BigDecimal CASH_REWARD_AMOUNT = new BigDecimal("5.00");
    private static final BigDecimal WITHDRAW_MIN = new BigDecimal("20.00");

    private final UserMapper userMapper = mock(UserMapper.class);
    private final InviteRelationMapper inviteRelationMapper = mock(InviteRelationMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final CashFlowMapper cashFlowMapper = mock(CashFlowMapper.class);

    private InviteServiceImpl inviteService;

    /**
     * onOrderPaid/creditFrozenCash 的 LambdaUpdateWrapper.set() 列名解析依赖实体 TableInfo
     * （生产由 MP 启动扫 mapper 时装配），本测试不起容器也不连库，手工灌内存缓存。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, InviteRelation.class);
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, CashFlow.class);
    }

    @BeforeEach
    void setUp() {
        inviteService = new InviteServiceImpl();
        ReflectionTestUtils.setField(inviteService, "userMapper", userMapper);
        ReflectionTestUtils.setField(inviteService, "inviteRelationMapper", inviteRelationMapper);
        ReflectionTestUtils.setField(inviteService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(inviteService, "cashFlowMapper", cashFlowMapper);
    }

    // ---------------------------------------------------------------- fixtures

    private static User invitee() {
        User user = new User();
        user.setId(INVITEE_ID);
        user.setPhone(INVITEE_PHONE);
        return user;
    }

    private static User inviter(BigDecimal balance) {
        User user = new User();
        user.setId(INVITER_ID);
        user.setPhone("13900001000");
        user.setInviteCode(INVITE_CODE);
        user.setBalance(balance);
        return user;
    }

    /** ① 冻结原子闸门 0 行（cash_rewarded 已满 10）→ 不发冻结流水，但绑定关系照常落、不报错 */
    @Test
    void 封顶闸门0行_不发冻结流水_绑定照常() {
        when(userMapper.selectOne(any())).thenReturn(inviter(new BigDecimal("0.00")));
        when(userMapper.increaseCashRewarded(INVITER_ID, CASH_REWARD_MAX)).thenReturn(0);

        inviteService.bindOnRegister(invitee(), INVITE_CODE); // 绑定照常，静默

        verify(userMapper, times(1)).increaseCashRewarded(INVITER_ID, CASH_REWARD_MAX);
        verify(cashFlowMapper, never()).insert(any(CashFlow.class)); // 封顶后不产生任何冻结流水
        verify(inviteRelationMapper, times(1)).insert(any(InviteRelation.class)); // 关系照常落
        verify(userMapper, never()).addBalance(any(), any()); // 注册只冻结不入账
    }

    /** ② 冻结成功：流水字段逐项取证（type/status/amount/balanceAfter 快照/inviteeId）+ 关系快照脱敏落库 */
    @Test
    void 冻结成功_落冻结流水与脱敏关系快照_注册不入账() {
        when(userMapper.selectOne(any())).thenReturn(inviter(new BigDecimal("12.34")));
        when(userMapper.increaseCashRewarded(INVITER_ID, CASH_REWARD_MAX)).thenReturn(1);
        when(userMapper.selectById(INVITER_ID)).thenReturn(inviter(new BigDecimal("12.34")));

        inviteService.bindOnRegister(invitee(), INVITE_CODE);

        ArgumentCaptor<InviteRelation> relCaptor = ArgumentCaptor.forClass(InviteRelation.class);
        verify(inviteRelationMapper, times(1)).insert(relCaptor.capture());
        assertEquals(INVITER_ID, relCaptor.getValue().getInviterId());
        assertEquals(INVITEE_ID, relCaptor.getValue().getInviteeId());
        assertEquals("138****1235", relCaptor.getValue().getInviteePhone(), "invitee_phone 落库即脱敏（表内无原值）");
        assertEquals(InviteRelation.STATUS_PENDING, relCaptor.getValue().getStatus());

        ArgumentCaptor<CashFlow> flowCaptor = ArgumentCaptor.forClass(CashFlow.class);
        verify(cashFlowMapper, times(1)).insert(flowCaptor.capture());
        CashFlow flow = flowCaptor.getValue();
        assertEquals(INVITER_ID, flow.getUserId());
        assertEquals(CashFlow.TYPE_REWARD, flow.getType());
        assertEquals(CashFlow.STATUS_FROZEN, flow.getStatus(), "注册只冻结：status=0");
        assertEquals(0, CASH_REWARD_AMOUNT.compareTo(flow.getAmount()), "单人次冻结 5.00");
        assertEquals(0, new BigDecimal("12.34").compareTo(flow.getBalanceAfter()), "balanceAfter=当时余额快照（未变动）");
        assertEquals(INVITEE_ID, flow.getInviteeId(), "inviteeId 挂钩：首单支付后按它定位冻结流水入账");
        verify(userMapper, never()).addBalance(any(), any()); // 绝不在注册时入账
    }

    /** ③ withdraw 差额校验：余额不足 → 400「还差 X.XX 元可提现」，零写库 */
    @Test
    void 余额不足_差额文案400_零写库() {
        when(userMapper.selectById(INVITER_ID)).thenReturn(inviter(new BigDecimal("15.00")));

        BizException ex = assertThrows(BizException.class, () -> inviteService.withdraw(INVITER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("还差 5.00 元可提现", ex.getMessage(), "差额文案两位小数与全站金额口径一致");
        verify(userMapper, never()).clearBalanceIfEnough(any(), any());
        verify(cashFlowMapper, never()).insert(any(CashFlow.class));
    }

    /** ④ clearBalanceIfEnough rows!=1（并发双提窗口内余额被提走）→ 400 且不写流水（不透支不半态） */
    @Test
    void 并发清零0行_400余额已变动_不落流水() {
        when(userMapper.selectById(INVITER_ID)).thenReturn(inviter(new BigDecimal("25.00")));
        when(userMapper.clearBalanceIfEnough(INVITER_ID, WITHDRAW_MIN)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> inviteService.withdraw(INVITER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("余额已变动，请刷新后重试", ex.getMessage());
        verify(cashFlowMapper, never()).insert(any(CashFlow.class)); // rows!=1 绝不给成功语义
    }

    /** ⑤ 提现成功清零 + 全链两条流水：绑定冻结流水① + 提现流水②（amount=全部余额、清零快照、文案合规） */
    @Test
    void 提现成功_清零原子条件_全链两条流水() {
        User inviter = inviter(new BigDecimal("25.00"));
        // 前半：注册绑定冻结（流水①）
        when(userMapper.selectOne(any())).thenReturn(inviter);
        when(userMapper.increaseCashRewarded(INVITER_ID, CASH_REWARD_MAX)).thenReturn(1);
        when(userMapper.selectById(INVITER_ID)).thenReturn(inviter);
        inviteService.bindOnRegister(invitee(), INVITE_CODE);
        // 后半：满 20 提现全部余额（流水②）
        when(userMapper.clearBalanceIfEnough(INVITER_ID, WITHDRAW_MIN)).thenReturn(1);

        BigDecimal withdrawn = inviteService.withdraw(INVITER_ID);

        assertEquals(0, new BigDecimal("25.00").compareTo(withdrawn), "满 20 提全部余额");
        verify(userMapper, times(1)).clearBalanceIfEnough(INVITER_ID, WITHDRAW_MIN); // 原子清零是唯一余额写路径

        ArgumentCaptor<CashFlow> flowCaptor = ArgumentCaptor.forClass(CashFlow.class);
        verify(cashFlowMapper, times(2)).insert(flowCaptor.capture());
        List<CashFlow> flows = flowCaptor.getAllValues();
        assertEquals(CashFlow.TYPE_REWARD, flows.get(0).getType());
        assertEquals(CashFlow.STATUS_FROZEN, flows.get(0).getStatus());
        assertEquals(CashFlow.TYPE_WITHDRAW, flows.get(1).getType());
        assertEquals(CashFlow.STATUS_CREDITED, flows.get(1).getStatus());
        assertEquals(0, new BigDecimal("25.00").compareTo(flows.get(1).getAmount()), "提现流水=提现时全部余额（账面对得上）");
        assertEquals(0, BigDecimal.ZERO.compareTo(flows.get(1).getBalanceAfter()), "清零后快照=0");
        assertEquals("提现", flows.get(1).getRemark());
        assertFalse(flows.get(1).getRemark().contains("mock"), "提现文案不出现内部词 mock（F-04.2 修订④）");
    }

    /** ⑥ bindOnRegister 自邀请拒绝：邀请码为本人所有 → 静默返回，关系与激励零写入 */
    @Test
    void 自邀请拒绝_静默返回_零写库() {
        User self = invitee();
        self.setInviteCode(INVITE_CODE); // 查码命中本人
        when(userMapper.selectOne(any())).thenReturn(self);

        inviteService.bindOnRegister(self, INVITE_CODE);

        verify(inviteRelationMapper, never()).insert(any(InviteRelation.class));
        // increaseCashRewarded 第二参是原生 int：匹配器必须 anyInt()（any() 拆箱 null 会 NPE，
        // 且打断 verify 注册、残留未完成验证状态污染同线程后续测试）
        verify(userMapper, never()).increaseCashRewarded(any(), anyInt());
        verify(cashFlowMapper, never()).insert(any(CashFlow.class));
    }

    /** ⑦ 重复绑定拒绝：insert 撞 uk_invitee（一人仅可被邀请一次）→ 静默返回、不重复发激励 */
    @Test
    void 重复绑定_ukInvitee撞键_静默忽略不发激励() {
        when(userMapper.selectOne(any())).thenReturn(inviter(new BigDecimal("0.00")));
        when(inviteRelationMapper.insert(any(InviteRelation.class)))
                .thenThrow(new DuplicateKeyException("uk_invitee"));

        inviteService.bindOnRegister(invitee(), INVITE_CODE);

        verify(inviteRelationMapper, times(1)).insert(any(InviteRelation.class)); // 尝试过一次即止
        verify(userMapper, never()).increaseCashRewarded(any(), anyInt()); // 第二参原生 int 必须 anyInt()（见 ⑥ 注释）
        verify(cashFlowMapper, never()).insert(any(CashFlow.class));
    }

    /** ⑧ onOrderPaid 发放路径：已支付订单数==1 → 关系 0→1 原子置位 → 冻结流水入账 → 邀请人 balance+5 */
    @Test
    void 首单支付_关系置位_冻结入账加余额() {
        when(orderMapper.selectCount(any())).thenReturn(1L); // 支付事务内已置 pay_status=1，数==1 即首单
        InviteRelation relation = new InviteRelation();
        relation.setId(77L);
        relation.setInviterId(INVITER_ID);
        relation.setInviteeId(INVITEE_ID);
        relation.setStatus(InviteRelation.STATUS_PENDING);
        when(inviteRelationMapper.selectOne(any())).thenReturn(relation);
        when(inviteRelationMapper.update(any(), any())).thenReturn(1); // 0→1 原子置位成功
        when(cashFlowMapper.update(any(), any())).thenReturn(1); // 冻结流水 0→1 入账成功

        inviteService.onOrderPaid(INVITEE_ID);

        verify(userMapper, times(1)).addBalance(INVITER_ID, CASH_REWARD_AMOUNT); // 入账恰一次、金额恰 5.00
        verify(inviteRelationMapper, times(1)).update(any(), any());
        verify(cashFlowMapper, times(1)).update(any(), any());
    }
}
