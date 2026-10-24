package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.entity.CouponTemplate;
import com.fresh.entity.UserCoupon;
import com.fresh.mapper.CouponTemplateMapper;
import com.fresh.mapper.UserCouponMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-113 券模板表化契约 §3 防资损对照断言（不起 Spring 容器、不落库）。
 *
 * <p><b>两轮节奏（05 册 B3④ 红线的解法，顺序不许倒置）</b>：
 * ① 本类先对<b>未改动的旧 TEMPLATES 常量代码</b>跑到绿 = 基线快照（迁移前语义）；
 * ② 生产代码切表后<b>复跑同一组断言</b>到绿 = 对照完成（语义未漂移）。
 * 基线对照组的方法体在两轮之间一字不改，可变的只有类头部的<b>夹具桥接区</b>
 * （模板来源从「常量不可变」换成「内存表行」），断言全部落在行为层：
 * 异常文案 / 拒与放 / user_coupon 落库行的快照值。
 *
 * <p><b>断言分两组（主会话 2026-10-24 裁决口径）</b>：
 * <ul>
 *   <li>【基线对照】A-1 used 后再领 400 / A-2 过期不重领 / B 发放时快照 /
 *       C total_quota=NULL 不拦——旧语义存在的四块，基线绿 → 表实现复跑绿；</li>
 *   <li>【新增语义】A-3 limit_per_user=0 可重领 / C total_quota=2 三领二拒一拒 +
 *       issued_count 恰额 / 取消与失败路径不双扣 / 改表后新领得新面额——
 *       表化新增能力（旧代码无此行为、无对照对象），仅表实现后绿。</li>
 * </ul>
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：selectCount/条件 UPDATE 的真 SQL 形状、
 * 配额 UPDATE 与 insert 的真事务回滚、DECIMAL 列宽——本类用内存计数/内存表行模拟，
 * 断言锁的是 service 层行为；真库行为由主会话 mvn 门禁与 §5 verify 脚本覆盖。
 * lifetime 的「含 used/expired」在 mock 层体现为 selectCount 返回全量计数（不筛状态），
 * 与旧代码的 LambdaQueryWrapper 同口径（eq userId + eq couponKey，无 status/expire 条件）。
 */
class CouponTemplateGuardTest {

    private final UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
    private final CouponTemplateMapper couponTemplateMapper = mock(CouponTemplateMapper.class);

    /** 内存 user_coupon 行库：insert 落行、selectById 回行（getUsable 走真行快照） */
    private final Map<Long, UserCoupon> couponRows = new HashMap<>();
    private long idSeq = 100;

    /** 内存 coupon_template 表（键序=回填顺序）；tryIssueQuota 在其上模拟条件 UPDATE 的配额语义 */
    private final Map<String, CouponTemplate> templateRows = new LinkedHashMap<>();

    /**
     * lifetime 全量计数的桥接值：测试按场景手动拨（0=无历史行，1=已领过一张）。
     * 不解析 LambdaQueryWrapper 的参数——断言锁行为不锁 SQL 形状。
     */
    private Long lifetimeCount = 0L;

    private CouponServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CouponServiceImpl();
        ReflectionTestUtils.setField(service, "userCouponMapper", userCouponMapper);
        ReflectionTestUtils.setField(service, "couponTemplateMapper", couponTemplateMapper);
        when(userCouponMapper.selectCount(any())).thenAnswer(inv -> lifetimeCount);
        doAnswer(inv -> {
            UserCoupon coupon = inv.getArgument(0);
            coupon.setId(++idSeq);
            couponRows.put(coupon.getId(), coupon);
            return 1;
        }).when(userCouponMapper).insert(any(UserCoupon.class));
        when(userCouponMapper.selectById(any())).thenAnswer(inv -> couponRows.get(inv.getArgument(0)));
        // markUsed/restoreByOrderId 的条件 UPDATE：mock 返回 1（影响 1 行 = 核销/回补成功）
        when(userCouponMapper.update(any(), any())).thenReturn(1);

        // 种四档模板（m24 回填口径逐字：limit_per_user=1、valid_days=7、total_quota=NULL、active）
        seedTemplate("coupon_1", "19.00", "4.00");
        seedTemplate("coupon_2", "30.00", "5.00");
        seedTemplate("coupon_3", "50.00", "21.00");
        seedTemplate("newbie_gift", "30.00", "8.00");
        when(couponTemplateMapper.selectByCouponKey(anyString()))
                .thenAnswer(inv -> templateRows.get(inv.getArgument(0, String.class)));
        // tryIssueQuota 的内存实现 = 契约 SQL 的条件语义：active + total_quota 非空 + issued<quota 才自增
        when(couponTemplateMapper.tryIssueQuota(anyString())).thenAnswer(inv -> {
            CouponTemplate template = templateRows.get(inv.getArgument(0, String.class));
            if (template == null || template.getTotalQuota() == null
                    || !CouponTemplate.STATUS_ACTIVE.equals(template.getStatus())) {
                return 0;
            }
            if (template.getIssuedCount() >= template.getTotalQuota()) {
                return 0;
            }
            template.setIssuedCount(template.getIssuedCount() + 1);
            return 1;
        });
    }

    /** 造模板行（issued_count 起 0；limit_per_user/valid_days/total_quota/status 用回填口径默认值） */
    private CouponTemplate seedTemplate(String couponKey, String threshold, String amount) {
        return seedTemplate(couponKey, threshold, amount, 1, null);
    }

    /** 造模板行（「测试内造模板」的出口：limit_per_user=0 / total_quota=2 的新增语义模板走这里） */
    private CouponTemplate seedTemplate(String couponKey, String threshold, String amount,
                                        int limitPerUser, Integer totalQuota) {
        CouponTemplate template = new CouponTemplate();
        template.setId((long) (templateRows.size() + 1));
        template.setCouponKey(couponKey);
        template.setThreshold(new BigDecimal(threshold));
        template.setAmount(new BigDecimal(amount));
        template.setValidDays(7);
        template.setLimitPerUser(limitPerUser);
        template.setTotalQuota(totalQuota);
        template.setIssuedCount(0);
        template.setStatus(CouponTemplate.STATUS_ACTIVE);
        templateRows.put(couponKey, template);
        return template;
    }

    // ---------------------------------------------------------------- 夹具桥接区（切表版在此换模板来源）

    /**
     * 金额断言按<b>数值</b>比（compareTo==0）而非 equals：表列 DECIMAL(10,2) 出 19.00（scale 2），
     * 常量时代 BigDecimal.valueOf(19) 是 19（scale 0）——表示不同、数值相同，防资损语义锁数值不锁 scale
     * （JSON 数字 19.00 与 19 对前端 Number 等价，出参形状无变化）。两轮对照共用此断言。
     */
    private static void assertAmount(String what, String expected, BigDecimal actual) {
        assertNotNull(actual, what + " 不得为 null");
        assertTrue(new BigDecimal(expected).compareTo(actual) == 0,
                what + " expected=" + expected + " but was=" + actual.toPlainString());
    }

    /**
     * 「运营改模板面额」的桥接：基线版（常量时代）模板源不可变 → no-op，
     * 断言「getUsable 仍按领取快照」在「模板源不变」下自然成立；
     * 表化后此方法改为拨内存表行的 amount（真实模拟运营改表），断言方法体一字不动。
     */
    private void mutateTemplateSourceAmount(String couponKey, BigDecimal newAmount) {
        CouponTemplate template = templateRows.get(couponKey);
        if (template != null) {
            template.setAmount(newAmount);
        }
    }

    /**
     * MP 的 {@code LambdaUpdateWrapper.set/getter} 即时解析列名依赖实体 TableInfo 缓存——
     * 生产里由 MyBatis-Plus 启动扫 mapper 装配，本测试不起容器不连库，照 PaymentSettlementTest
     * 的官方入口手工灌一次（只建内存缓存，不碰任何连接）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, UserCoupon.class);
        TableInfoHelper.initTableInfo(assistant, CouponTemplate.class);
    }

    // ================================================================ 【基线对照】防线一：lifetime 口径

    /**
     * A-1（基线对照）：领 coupon_1 → markUsed 置 used → 再领同 key →
     * 400「每人限领 1 张」（文案与常量时代逐字一致）。堵死「领→核销→再领」资损路径。
     */
    @Test
    void used后再领同key_400每人限领1张() {
        long user = 101L;
        lifetimeCount = 0L;                       // 首领前无历史行
        UserCoupon first = service.claim(user, "coupon_1");
        assertNotNull(first, "首领必须成功");
        assertEquals(1, service.markUsed(first.getId(), user, "ORDER-A"), "markUsed 置 used 成功");

        lifetimeCount = 1L;                       // 全史存在一行（used 同样计数）
        BizException e = assertThrows(BizException.class, () -> service.claim(user, "coupon_1"));
        assertEquals(400, e.getCode());
        assertEquals("每人限领 1 张", e.getMessage(), "文案必须与常量时代逐字一致");
        verify(userCouponMapper, times(1)).insert(any(UserCoupon.class)); // 只落过首领那一行
    }

    /**
     * A-2（基线对照）：存在 expire_at 已过期的历史行 → 再领 → 同样 400「每人限领 1 张」。
     * 过期不重领：lifetime 计数不看 expire_at。堵死「过期即重领」资损路径。
     */
    @Test
    void 过期历史行_再领同样拒_过期不重领() {
        long user = 102L;
        lifetimeCount = 1L;                       // 夹具：全史一行，expire_at 已过期（计数不筛过期）
        BizException e = assertThrows(BizException.class, () -> service.claim(user, "coupon_1"));
        assertEquals(400, e.getCode());
        assertEquals("每人限领 1 张", e.getMessage(), "过期行同样占 lifetime 名额");
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    // ================================================================ 【基线对照】防线二：发放时快照

    /**
     * B（基线对照）：甲领 coupon_3（threshold=50/amount=21）→ 运营改模板源 amount=50 →
     * 甲的 getUsable 返回的抵扣额<b>仍是 21</b>（下单核销计价即以此 amount 为输入）。
     * 快照语义锚在 user_coupon 行上，不回读模板源——这是 markUsed/restore 不动如山的物证。
     * 「乙新领得 50」（改表生效于新领取）属表化新增语义，见新增语义组。
     */
    @Test
    void 发放时快照_模板源变更后_getUsable仍按领取快照() {
        long user = 103L;
        lifetimeCount = 0L;
        UserCoupon claimed = service.claim(user, "coupon_3");

        ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).insert(captor.capture());
        assertAmount("门槛按模板快照落券", "50", captor.getValue().getThreshold());
        assertAmount("抵扣额按模板快照落券", "21", captor.getValue().getAmount());
        // 有效期快照：领取时间 + valid_days（回填口径 7 天），不再引用 UserCoupon.VALID_DAYS 常量
        LocalDateTime lower = LocalDateTime.now().plusDays(7).minusMinutes(1);
        LocalDateTime upper = LocalDateTime.now().plusDays(7).plusMinutes(1);
        assertTrue(claimed.getExpireAt().isAfter(lower) && claimed.getExpireAt().isBefore(upper),
                "expire_at = 领取时间 + 7 天（valid_days 表列的回填值）");

        mutateTemplateSourceAmount("coupon_3", new BigDecimal("50"));   // 表版：改表后甲的旧券不受影响
        UserCoupon usable = service.getUsable(user, claimed.getId(), new BigDecimal("59.90"));
        assertAmount("抵扣额仍是领取时快照 21，不回读模板源", "21", usable.getAmount());
        assertAmount("门槛仍是领取时快照", "50", usable.getThreshold());
    }

    // ================================================================ 【基线对照】防线三存量语义：无配额不拦

    /**
     * C-NULL（基线对照）：total_quota=NULL（回填四档的口径）→ 领取成功，不被任何总量计数拦。
     * 常量时代没有配额概念，此断言锁的是「表化后 NULL 路径与常量时代等价：只有 lifetime 一道闸」。
     */
    @Test
    void totalQuota为NULL_领取成功不被总量计数拦() {
        long user = 104L;
        lifetimeCount = 0L;
        UserCoupon coupon = service.claim(user, "coupon_1");
        assertNotNull(coupon, "total_quota=NULL 必须可领（不参与任何配额判定）");
        assertEquals("coupon_1", coupon.getCouponKey());
        assertAmount("门槛快照", "19", coupon.getThreshold());
        assertAmount("抵扣额快照", "4", coupon.getAmount());
        verify(userCouponMapper, times(1)).insert(any(UserCoupon.class));
    }

    // ================================================================ 【新增语义】防线一泛化：limit_per_user

    /**
     * A-3（新增语义，表化能力）：limit_per_user=0 的模板（测试内造）→ 可领第二张。
     * 常量时代全量 lifetime-once（A-1/A-2 已锁其口径），「不限领」是表化新增分支，无基线对照对象。
     */
    @Test
    void limitPerUser为0_可领第二张() {
        long user = 201L;
        seedTemplate("open_coupon", "10.00", "1.00", 0, null);   // limit_per_user=0 = 不限
        lifetimeCount = 1L;                       // 已领过一张（全史计数存在）
        UserCoupon second = service.claim(user, "open_coupon");
        assertNotNull(second, "limit_per_user=0 跳过 lifetime 闸门，第二张必须放行");
        assertEquals("open_coupon", second.getCouponKey());
        verify(userCouponMapper, times(1)).insert(any(UserCoupon.class));
    }

    // ================================================================ 【新增语义】防线三：配额原子

    /**
     * C-quota（新增语义，表化能力）：total_quota=2 的模板 → 三次领取：第 1/2 张成功、
     * 第 3 张 400「该券已领完」；issued_count 恰 =2（不多扣一张）。
     * 用户维度不受影响（两个用户各领一张 + 第三次同用户拦在配额前，lifetime=0 不拦）。
     * 真 SQL 的原子性/真库 issued_count 由主会话 §5 verify 脚本查库覆盖，本断言锁 service 行为与计数口径。
     */
    @Test
    void totalQuota为2_三领二拒一拒_issued恰等于2() {
        CouponTemplate template = seedTemplate("quota_coupon", "10.00", "2.00", 0, 2);
        lifetimeCount = 0L;                       // limit_per_user=0，计数不拦（隔离防线三单变量）

        assertNotNull(service.claim(301L, "quota_coupon"), "第 1 张成功");
        assertNotNull(service.claim(302L, "quota_coupon"), "第 2 张成功");

        BizException e = assertThrows(BizException.class, () -> service.claim(303L, "quota_coupon"));
        assertEquals(400, e.getCode());
        assertEquals("该券已领完", e.getMessage(), "第 3 张必须 400 且文案是运营语义那句");
        assertEquals(2, template.getIssuedCount(), "issued_count 恰=2，第 3 次失败不占额");
        verify(userCouponMapper, times(2)).insert(any(UserCoupon.class)); // 只有前两张落券
    }

    /**
     * C-双扣（新增语义，表化能力）：配额 UPDATE 与 insert 同事务、无独立扣减点——
     * ① 取消回补路径（restoreByOrderId 一行不改）不回补/不二次扣配额；
     * ② 领取失败（insert 抛错）时配额 UPDATE 只发生过一次，无补偿性二次扣减点
     * （真回滚由 @Transactional + 真库覆盖，本断言锁「claim 链路里 tryIssueQuota 只有一次调用点」）。
     */
    @Test
    void 取消回补与领取失败路径_配额不双扣() {
        seedTemplate("quota_coupon2", "10.00", "2.00", 0, 2);
        lifetimeCount = 0L;

        UserCoupon coupon = service.claim(401L, "quota_coupon2");
        service.markUsed(coupon.getId(), 401L, "ORDER-C");
        assertEquals(1, service.restoreByOrderId("ORDER-C"), "订单取消回补券（既有语义）");
        verify(couponTemplateMapper, times(1)).tryIssueQuota(anyString()); // 取消回补不触发配额 UPDATE（不回补不双扣）

        doThrow(new RuntimeException("insert 失败模拟")).when(userCouponMapper).insert(any(UserCoupon.class));
        assertThrows(RuntimeException.class, () -> service.claim(402L, "quota_coupon2"));
        verify(couponTemplateMapper, times(2)).tryIssueQuota(anyString()); // 两次领取各恰好一次，失败路径无第二个扣减点
    }

    // ================================================================ 【新增语义】防线二的表化生效面

    /**
     * B-乙（新增语义）：运营改表 amount=50 后，<b>新领取</b>得新面额——与 B（基线对照）合看：
     * 甲旧券仍 21（快照）、乙新券 50（表生效），两半合起来才是「快照语义 + 运营可运营」的完整闭环。
     */
    @Test
    void 改表后新领取_得新面额() {
        long yi = 501L;
        lifetimeCount = 0L;
        mutateTemplateSourceAmount("coupon_3", new BigDecimal("50"));
        UserCoupon fresh = service.claim(yi, "coupon_3");
        assertAmount("乙新领按改后的表面额", "50", fresh.getAmount());
        assertAmount("门槛不变仍按表", "50", fresh.getThreshold());
    }
}
