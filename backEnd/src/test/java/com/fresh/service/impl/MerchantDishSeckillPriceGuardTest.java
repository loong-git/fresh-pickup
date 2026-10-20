package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.dto.MerchantDishUpdateDTO;
import com.fresh.entity.Dish;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.service.MerchantService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家端不许改秒杀件的挂牌价（F-91，用户批准「同意 不是线上 顺便造」）。
 *
 * <p><b>要钉的事</b>：成交单价恒取 {@code dish.seckill_price}，但 C 端秒杀件的**划线价就是 {@code dish.price}**、
 * 折扣率按 {@code (price − seckill_price)/price} 换算。所以商家把 price 从 8.80 抬到 88，收款额一分不变，
 * 却等于给自己造出一个「一折」——受害面是顾客看到的折扣力度，属于价格展示真实性，不是商家内部数据。
 *
 * <p><b>只拦 price 一列</b>。把整条 update 关掉会顺手改掉商家改标题/换图的能力，那是另一个决策，不在本片。
 *
 * <p><b>顺序也是断言之一</b>：守卫必须发生在 {@code dishMapper.update} 之前，所以下面四条拒绝用例都
 * {@code verify(never())}。只断言"抛了 400"是不够的——若守卫误插到 update 之后，价格已经写进去了，
 * 文案照样对，行为照样错。
 *
 * <p><b>本类测不成什么</b>：400 经 {@code GlobalExceptionHandler} 装进信封后的出参形态、以及运营端
 * {@code AdminServiceImpl#updateDish} 仍可对秒杀件改价（那是真源，本就不该拦），归接口验收。
 */
class MerchantDishSeckillPriceGuardTest {

    private static final Long USER_ID = 1L;
    private static final Long MERCHANT_ID = 1L;
    private static final String GUARD_MSG = "该商品正在参加平台秒杀活动，挂牌价由运营统一维护，如需调价请联系运营";

    private final DishMapper dishMapper = mock(DishMapper.class);
    private final MerchantService merchantService = mock(MerchantService.class);
    private final AdminAuditLogMapper adminAuditLogMapper = mock(AdminAuditLogMapper.class);

    private MerchantDishServiceImpl service;

    /**
     * {@code LambdaUpdateWrapper.set(Dish::getPrice, …)} 是**即时**解析列名的，纯 Mockito 环境里没有容器
     * 替它建 TableInfo 缓存，直接跑会在装配 wrapper 那一行抛 {@code can not find lambda cache for this entity}
     * ——本类首跑四条全红就是这个原因，而不是守卫缺失。{@code TableInfoHelper} 是 MP 官方入口，
     * 只建内存缓存、不碰任何连接。母版同 {@link DishSeckillFilterTest}。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Dish.class);
    }

    @BeforeEach
    void setUp() {
        service = new MerchantDishServiceImpl();
        // @Autowired 字段注入、无构造器：逐个灌 mock，不起容器不落库（同 OrderCreateOffSaleMessageTest 姿势）
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "merchantService", merchantService);
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", adminAuditLogMapper);
        // 守卫之前的几道闸门都放行，好让断言只落在秒杀这一条上：
        // name 非 blank、bgColor 合法、image 空串、update 影响行数=1
        when(dishMapper.update(any(), any())).thenReturn(1);
    }

    /** before 快照：只关心 seckill 这一列，其余给足合法值 */
    private static Dish owned(Integer seckill) {
        Dish d = new Dish();
        d.setId(12L);
        d.setMerchantId(MERCHANT_ID);
        d.setName("演示秒杀件");
        d.setPrice(new BigDecimal("8.80"));
        d.setSeckillPrice(new BigDecimal("6.86"));
        d.setSeckill(seckill);
        d.setStock(20);
        d.setCategory("meat");
        d.setUnit("份");
        return d;
    }

    /** findOwned(id, merchantId) 走的是 selectOne(双条件 wrapper)，所以桩打在 selectOne 上 */
    private void stubBefore(Dish before) {
        when(dishMapper.selectOne(any())).thenReturn(before);
    }

    private static MerchantDishUpdateDTO priceOnly(String price) {
        MerchantDishUpdateDTO dto = new MerchantDishUpdateDTO();
        dto.setPrice(new BigDecimal(price));
        return dto;
    }

    private static MerchantDishUpdateDTO nameOnly(String name) {
        MerchantDishUpdateDTO dto = new MerchantDishUpdateDTO();
        dto.setName(name);
        return dto;
    }

    @Test
    void 秒杀件改挂牌价被400拒绝且文案指路运营() {
        stubBefore(owned(1));

        BizException ex = assertThrows(BizException.class,
                () -> service.update(USER_ID, MERCHANT_ID, 12L, priceOnly("88.00")));

        assertEquals(GUARD_MSG, ex.getMessage(), "拒绝必须说清「为什么不能改」与「该找谁」，只说「参数有误」等于没说");
        verify(dishMapper, never()).update(any(), any());
    }

    @Test
    void 秒杀件改名称不误伤() {
        stubBefore(owned(1));

        assertDoesNotThrow(() -> service.update(USER_ID, MERCHANT_ID, 12L, nameOnly("演示秒杀件新名")));

        // 只拦 price：证明这条守卫没有顺手把秒杀件冻成只读
        verify(dishMapper, times(1)).update(any(), any());
    }

    @Test
    void 非秒杀件改挂牌价照常放行() {
        stubBefore(owned(0));

        assertDoesNotThrow(() -> service.update(USER_ID, MERCHANT_ID, 12L, priceOnly("12.50")));

        verify(dishMapper, times(1)).update(any(), any());
    }

    /**
     * 反例桩：{@code Dish.seckill} 实测是 {@code Integer}（entity/Dish.java:30）且可空，
     * 写成 {@code before.getSeckill() == 1} 会在 null 时拆箱 NPE → 500。
     * 缺这条用例就等于没测过这个陷阱。
     */
    @Test
    void seckill为null时不得拆箱崩溃且照常放行() {
        stubBefore(owned(null));

        assertDoesNotThrow(() -> service.update(USER_ID, MERCHANT_ID, 12L, priceOnly("9.90")),
                "seckill 为 null 必须走「不是秒杀件」这一支，绝不能抛 NullPointerException");

        verify(dishMapper, times(1)).update(any(), any());
    }
}
