package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.dto.AdminSeckillUpdateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Dish;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-112 秒杀商品集管理契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>（契约 §3 五组逐条）：
 * ① 进秒杀：seckill=1 + 合法价 → 两列落库 + 审计行 seckill.update；
 * ② 调价：seckill=1 + 新价 → 只有 seckillPrice 变；
 * ③ 移出：seckill=0 → seckill=0 且 seckill_price 原值保留（patch 该列 null = NOT_NULL 策略不落列）；
 * ④ 校验分支逐条：dish 不存在 404；seckill 非 0/1 400；价缺失/≤0/三位小数 400；
 *    价 ≥ 原价 400 且文案含原价（服务端读库内现价，不信任前端）；
 * ⑤ 下架品可配（onSale=0 不拦）。
 * 另测列表出参：十键键序固定、id 升序透传 mapper、下架品原样列出。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：updateById 的 NOT_NULL 策略在真 MyBatis-Plus
 * 下的拼 SQL 行为、DECIMAL(10,2) 的真库舍入——本类用 mock 的 updateById 捕获 patch 断言
 * 「seckillPrice 留 null」这一写侧意图；真库行为由主会话的 mvn 门禁与 verify 脚本覆盖。
 */
class AdminSeckillManageTest {

    private static final String ADMIN_USER_ACTOR = "admin_user:7";

    private final DishMapper dishMapper = mock(DishMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(service, "adminKey", "test-admin-key");
    }

    // ---------------------------------------------------------------- fixtures

    private static Dish dish(long id, String name, String price, int onSale, int seckill, String seckillPrice) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setPrice(new BigDecimal(price));
        d.setOnSale(onSale);
        d.setStock(50);
        d.setSoldCount(120);
        d.setSeckill(seckill);
        d.setSeckillPrice(seckillPrice == null ? null : new BigDecimal(seckillPrice));
        d.setLimitBuy(5);
        d.setMerchantId(1L);
        return d;
    }

    private static AdminSeckillUpdateDTO dto(Integer seckill, String seckillPrice) {
        AdminSeckillUpdateDTO dto = new AdminSeckillUpdateDTO();
        dto.setSeckill(seckill);
        dto.setSeckillPrice(seckillPrice == null ? null : new BigDecimal(seckillPrice));
        return dto;
    }

    private AdminAuditLog capturedAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- 列表

    @Test
    void 列表出参十键_保持id升序_下架品原样列出() {
        when(dishMapper.selectList(any())).thenReturn(List.of(
                dish(3L, "沙瓤西红柿", "8.80", 1, 1, "6.90"),
                dish(4L, "高山土豆", "5.20", 0, 0, null)));

        List<Map<String, Object>> out = service.seckillDishes();

        assertEquals(2, out.size());
        assertEquals(List.of("id", "name", "price", "onSale", "stock", "soldCount",
                        "seckill", "seckillPrice", "limitBuy", "merchantId"),
                new ArrayList<>(out.get(0).keySet()), "列表键序固定为契约 §1.1 十键");
        assertEquals(3L, out.get(0).get("id"));
        assertEquals("沙瓤西红柿", out.get(0).get("name"));
        assertEquals(new BigDecimal("8.80"), out.get(0).get("price"));
        assertEquals(1, out.get(0).get("seckill"));
        assertEquals(new BigDecimal("6.90"), out.get(0).get("seckillPrice"));
        assertEquals(1L, out.get(0).get("merchantId"));
        assertEquals(0, out.get(1).get("onSale"), "下架品必须原样列出（预先配秒杀是合法状态）");
        assertNull(out.get(1).get("seckillPrice"), "未进秒杀的 seckillPrice 透传库内 null");
    }

    // ---------------------------------------------------------------- ① 进秒杀

    @Test
    void 进秒杀_两列落库_写审计seckillUpdate() {
        when(dishMapper.selectById(4L)).thenReturn(dish(4L, "高山土豆", "5.20", 1, 0, null));
        when(dishMapper.updateById(any(Dish.class))).thenReturn(1);

        service.updateSeckillDish(4L, dto(1, "3.90"), ADMIN_USER_ACTOR);

        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).updateById(captor.capture());
        Dish patch = captor.getValue();
        assertEquals(4L, patch.getId());
        assertEquals(1, patch.getSeckill());
        assertEquals(new BigDecimal("3.90"), patch.getSeckillPrice());

        AdminAuditLog audit = capturedAudit();
        assertEquals("seckill.update", audit.getAction());
        assertEquals(ADMIN_USER_ACTOR, audit.getActor(), "运营 JWT 轨 actor 原样采用 grant.actor()");
        assertEquals("dish", audit.getTargetType());
        assertEquals("4", audit.getTargetId());
        assertTrue(audit.getDetail().contains("seckill=0"), "旧值取方法开头快照");
        assertTrue(audit.getDetail().contains("seckill=1,seckillPrice=3.90"), "新值为两列完整快照");
    }

    // ---------------------------------------------------------------- ② 调价

    @Test
    void 调价_只有秒杀价变_仍写审计() {
        when(dishMapper.selectById(3L)).thenReturn(dish(3L, "沙瓤西红柿", "8.80", 1, 1, "6.90"));
        when(dishMapper.updateById(any(Dish.class))).thenReturn(1);

        service.updateSeckillDish(3L, dto(1, "5.50"), ADMIN_USER_ACTOR);

        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getSeckill(), "调价仍是 seckill=1，参与位不变");
        assertEquals(new BigDecimal("5.50"), captor.getValue().getSeckillPrice(), "只有秒杀价落新值");
        AdminAuditLog audit = capturedAudit();
        assertTrue(audit.getDetail().contains("seckillPrice=6.90"), "旧价进审计");
        assertTrue(audit.getDetail().contains("seckillPrice=5.50"), "新价进审计");
    }

    // ---------------------------------------------------------------- ③ 移出

    @Test
    void 移出_seckill落0_秒杀价原值保留() {
        when(dishMapper.selectById(3L)).thenReturn(dish(3L, "沙瓤西红柿", "8.80", 1, 1, "6.90"));
        when(dishMapper.updateById(any(Dish.class))).thenReturn(1);

        service.updateSeckillDish(3L, dto(0, null), ADMIN_USER_ACTOR);

        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getSeckill());
        assertNull(captor.getValue().getSeckillPrice(),
                "移出必须不落 seckill_price 列（NOT_NULL 策略 = 历史价原样保留，§1.2 第 5 条）");
        AdminAuditLog audit = capturedAudit();
        assertTrue(audit.getDetail().contains("\"new\":\"seckill=0,seckillPrice=6.90\""),
                "审计新值如实记保留后的原价（两列解耦）");
    }

    @Test
    void 移出时body带了价也被忽略_不落价列() {
        when(dishMapper.selectById(3L)).thenReturn(dish(3L, "沙瓤西红柿", "8.80", 1, 1, "6.90"));
        when(dishMapper.updateById(any(Dish.class))).thenReturn(1);

        service.updateSeckillDish(3L, dto(0, "9.99"), ADMIN_USER_ACTOR);

        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).updateById(captor.capture());
        assertNull(captor.getValue().getSeckillPrice(), "seckill=0 时 seckillPrice 不参与校验也不落库");
    }

    // ---------------------------------------------------------------- ④ 校验分支

    @Test
    void dish不存在404_先于body一切判定() {
        when(dishMapper.selectById(404L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.updateSeckillDish(404L, dto(9, null), ADMIN_USER_ACTOR));

        assertEquals(404, e.getCode());
        assertEquals("商品不存在", e.getMessage(), "文案必须是契约逐字那句");
        assertEquals(404, assertThrows(BizException.class,
                () -> service.updateSeckillDish(404L, null, ADMIN_USER_ACTOR)).getCode(),
                "body 缺失也先吃到 404（报错优先级第一条）");
        verify(dishMapper, never()).updateById(any(Dish.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void seckill非0非1_或缺失_400不落库() {
        when(dishMapper.selectById(4L)).thenReturn(dish(4L, "高山土豆", "5.20", 1, 0, null));

        for (Integer bad : new Integer[]{null, 2, -1}) {
            BizException e = assertThrows(BizException.class,
                    () -> service.updateSeckillDish(4L, dto(bad, "3.90"), ADMIN_USER_ACTOR));
            assertEquals(400, e.getCode());
            assertEquals("seckill 仅允许 0（移出秒杀）或 1（进秒杀）", e.getMessage());
        }
        verify(dishMapper, never()).updateById(any(Dish.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 秒杀价缺失_或非正数_或三位小数_400同一句文案() {
        when(dishMapper.selectById(4L)).thenReturn(dish(4L, "高山土豆", "5.20", 1, 0, null));

        for (String bad : new String[]{null, "0", "-0.01", "3.999"}) {
            BizException e = assertThrows(BizException.class,
                    () -> service.updateSeckillDish(4L, dto(1, bad), ADMIN_USER_ACTOR));
            assertEquals(400, e.getCode());
            assertEquals("秒杀价必须为正数（最多两位小数）", e.getMessage(), "bad=" + bad);
        }
        verify(dishMapper, never()).updateById(any(Dish.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 秒杀价等于原价400_文案含原价两位小数() {
        when(dishMapper.selectById(4L)).thenReturn(dish(4L, "高山土豆", "5.20", 1, 0, null));

        BizException e = assertThrows(BizException.class,
                () -> service.updateSeckillDish(4L, dto(1, "5.20"), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("秒杀价必须低于原价 5.20", e.getMessage(), "倒挂（=）必须拦且文案带库内现价");
        verify(dishMapper, never()).updateById(any(Dish.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 秒杀价高于原价400_现价取库内不信任前端() {
        when(dishMapper.selectById(4L)).thenReturn(dish(4L, "高山土豆", "5.20", 1, 0, null));

        BizException e = assertThrows(BizException.class,
                () -> service.updateSeckillDish(4L, dto(1, "5.21"), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("5.20"), "文案含库内现价（划线价语义）");
    }

    @Test
    void 价形状先于价比_三位小数即使倒挂也报形状那句() {
        when(dishMapper.selectById(4L)).thenReturn(dish(4L, "高山土豆", "5.20", 1, 0, null));

        BizException e = assertThrows(BizException.class,
                () -> service.updateSeckillDish(4L, dto(1, "9.999"), ADMIN_USER_ACTOR));

        assertEquals("秒杀价必须为正数（最多两位小数）", e.getMessage(),
                "校验顺序：价形状（第 3 条）先于价与现价比（第 4 条）");
    }

    // ---------------------------------------------------------------- ⑤ 下架品可配

    @Test
    void 下架品可进秒杀_onSale不拦() {
        when(dishMapper.selectById(9L)).thenReturn(dish(9L, "库存尾货梨", "12.00", 0, 0, null));
        when(dishMapper.updateById(any(Dish.class))).thenReturn(1);

        service.updateSeckillDish(9L, dto(1, "7.20"), ADMIN_USER_ACTOR);

        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getSeckill(), "下架品允许预先配秒杀（C 端 listSeckill 自会过滤 on_sale=1）");
        assertEquals("seckill.update", capturedAudit().getAction());
    }
}
