package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.dto.AdminStoreUpdateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Store;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

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
 * F-114 自提点存量管理契约单测（不起 Spring 容器、不落库，照 AdminCouponTemplateManageTest 的姿势）。
 *
 * <p><b>要证明的事</b>（契约 §4 逐条）：
 * ① 部分更新：只带 name → 只 name 变，其余列不进 patch（NOT_NULL 策略不落列）；
 * ② 全字段合法更新 + 审计行 store.update（before/after 只记实际变化的键）；
 * ③ 校验分支逐条（契约 §1.2 清单）：非法 status / openTime / closeTime / 负容量 / name 空 /
 *    address 空 / service 超长 / accepting=2 → 400；id 不存在 404「门店不存在」；
 * ④ 红线字段：body 塞 ownerUserId/contactPhone/photos/lng/lat → 被忽略，列不动；
 *    只塞红线字段 = 八列全空 → 400（探测字段不顶白名单额）。
 *
 * <p><b>本类测不成什么</b>：updateById 的 NOT_NULL 策略拼 SQL、status/daily_capacity 写库后
 * C 端与下单拦截的真联动——由主会话 mvn 门禁与 §5 verify 脚本覆盖。
 */
class AdminStoreManageTest {

    private static final String ADMIN_USER_ACTOR = "admin_user:7";

    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminServiceImpl();
        ReflectionTestUtils.setField(service, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(service, "adminKey", "test-admin-key");
    }

    // ---------------------------------------------------------------- fixtures

    /** 4 号店（closed，契约 §0：容量全 NULL）形态的行；lng/lat/contact_phone 是红线列，塞占位值用于断言「不动」 */
    private static Store row(long id, String name, String address, String service, String status,
                             String openTime, String closeTime, Integer dailyCapacity,
                             Integer accepting, Long ownerUserId) {
        Store row = new Store();
        row.setId(id);
        row.setName(name);
        row.setAddress(address);
        row.setService(service);
        row.setStatus(status);
        row.setOpenTime(openTime);
        row.setCloseTime(closeTime);
        row.setDailyCapacity(dailyCapacity);
        row.setAccepting(accepting);
        row.setOwnerUserId(ownerUserId);
        row.setContactPhone("138****1234");
        row.setLng(new BigDecimal("113.250000"));
        row.setLat(new BigDecimal("22.750000"));
        return row;
    }

    private AdminAuditLog capturedAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- ① 部分更新

    @Test
    void 部分更新_只带name_patch只含name其余列不动() {
        when(storeMapper.selectById(4L)).thenReturn(
                row(4L, "容桂南堤示范店", "广东省佛山市顺德区容桂示范南路38号", "冷冻冷藏",
                        "closed", null, null, null, 1, null));

        AdminStoreUpdateDTO dto = new AdminStoreUpdateDTO();
        dto.setName("容桂南堤示范店(南)");

        service.updateStore(4L, dto, ADMIN_USER_ACTOR);

        ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
        verify(storeMapper).updateById(captor.capture());
        Store patch = captor.getValue();
        assertEquals(4L, patch.getId());
        assertEquals("容桂南堤示范店(南)", patch.getName());
        // F-113 同款姿势：合并值全 set 进 patch——未传列 = 旧值（落列值相同无变化），NOT_NULL
        // 策略只让「存量 NULL 且未传」的列不落列；行内其余列不动的语义由此成立
        assertEquals("广东省佛山市顺德区容桂示范南路38号", patch.getAddress(), "未传列合并旧值");
        assertEquals("冷冻冷藏", patch.getService());
        assertEquals("closed", patch.getStatus());
        assertNull(patch.getOpenTime(), "存量 NULL 列未传仍为 null（NOT_NULL 策略不落列）");
        assertNull(patch.getCloseTime());
        assertNull(patch.getDailyCapacity());
        assertEquals(1, patch.getAccepting());

        AdminAuditLog audit = capturedAudit();
        assertEquals("store.update", audit.getAction());
        assertTrue(audit.getDetail().contains("name="), "before/after 只记实际变化的键（name）");
    }

    // ---------------------------------------------------------------- ② 全字段合法更新 + 审计

    @Test
    void 全字段合法更新_审计store_update只记变化键() {
        when(storeMapper.selectById(4L)).thenReturn(
                row(4L, "容桂南堤示范店", "广东省佛山市顺德区容桂示范南路38号", "冷冻冷藏",
                        "closed", null, null, null, 1, null));

        AdminStoreUpdateDTO dto = new AdminStoreUpdateDTO();
        dto.setName(" 容桂南堤示范店(南) ");   // trim 后落库
        dto.setAddress("广东省佛山市顺德区容桂示范南路38号之二");
        dto.setService("冷冻冷藏/自备袋");
        dto.setStatus("open");
        dto.setOpenTime("08:00");
        dto.setCloseTime("20:30");
        dto.setDailyCapacity(0);               // 0 合法：与 null 语义均为不限容（F-111 拦截条件 >0 才拦）
        dto.setAccepting(0);

        service.updateStore(4L, dto, ADMIN_USER_ACTOR);

        ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
        verify(storeMapper).updateById(captor.capture());
        Store patch = captor.getValue();
        assertEquals("容桂南堤示范店(南)", patch.getName(), "name trim 后落库");
        assertEquals("广东省佛山市顺德区容桂示范南路38号之二", patch.getAddress());
        assertEquals("冷冻冷藏/自备袋", patch.getService());
        assertEquals("open", patch.getStatus());
        assertEquals("08:00", patch.getOpenTime());
        assertEquals("20:30", patch.getCloseTime());
        assertEquals(0, patch.getDailyCapacity());
        assertEquals(0, patch.getAccepting());
        assertNull(patch.getOwnerUserId(), "owner_user_id 不在落库白名单");

        AdminAuditLog audit = capturedAudit();
        assertEquals("store.update", audit.getAction());
        assertEquals(ADMIN_USER_ACTOR, audit.getActor());
        assertEquals("store", audit.getTargetType());
        assertEquals("4", audit.getTargetId());
        assertTrue(audit.getDetail().contains("status=closed") && audit.getDetail().contains("status=open"),
                "before/after 记 status 旧新值");
        assertTrue(audit.getDetail().contains("dailyCapacity=null")
                        && audit.getDetail().contains("dailyCapacity=0"),
                "容量 null→0 进审计（0=不限容语义，运营改容量真实生效到下单拦截）");
    }

    // ---------------------------------------------------------------- ③ 校验分支逐条 + 404

    @Test
    void 校验分支逐条400_不落库不写审计() {
        when(storeMapper.selectById(4L)).thenReturn(
                row(4L, "容桂南堤示范店", "广东省佛山市顺德区容桂示范南路38号", "冷冻冷藏",
                        "closed", null, null, null, 1, null));
        // 非法 status / openTime 格式 / closeTime 格式 / 负容量 / name 空 / name 超长 /
        // address 空 / service 超长 / accepting=2（契约 §1.2 校验清单逐条 + §4.3 点名的五项）
        record Bad(AdminStoreUpdateDTO dto, String message) {}
        AdminStoreUpdateDTO d1 = new AdminStoreUpdateDTO(); d1.setStatus("paused");
        AdminStoreUpdateDTO d2 = new AdminStoreUpdateDTO(); d2.setOpenTime("25:00");
        AdminStoreUpdateDTO d3 = new AdminStoreUpdateDTO(); d3.setOpenTime("8:00");
        AdminStoreUpdateDTO d4 = new AdminStoreUpdateDTO(); d4.setCloseTime("24:30");
        AdminStoreUpdateDTO d5 = new AdminStoreUpdateDTO(); d5.setDailyCapacity(-1);
        AdminStoreUpdateDTO d6 = new AdminStoreUpdateDTO(); d6.setName("   ");
        AdminStoreUpdateDTO d7 = new AdminStoreUpdateDTO(); d7.setName("长".repeat(101));
        AdminStoreUpdateDTO d8 = new AdminStoreUpdateDTO(); d8.setAddress(" ");
        AdminStoreUpdateDTO d9 = new AdminStoreUpdateDTO(); d9.setService("长".repeat(51));
        AdminStoreUpdateDTO d10 = new AdminStoreUpdateDTO(); d10.setAccepting(2);
        List<Bad> bads = List.of(
                new Bad(d1, "status 仅允许 open/closed"),
                new Bad(d2, "营业开始时间格式应为 HH:MM"),
                new Bad(d3, "营业开始时间格式应为 HH:MM"),
                new Bad(d4, "营业结束时间格式应为 HH:MM"),
                new Bad(d5, "日接单容量须为大于等于 0 的整数"),
                new Bad(d6, "门店名称须为 1-100 个字符"),
                new Bad(d7, "门店名称须为 1-100 个字符"),
                new Bad(d8, "门店地址须为 1-255 个字符"),
                new Bad(d9, "服务说明最长 50 个字符"),
                new Bad(d10, "accepting 仅允许 0/1"));
        for (int i = 0; i < bads.size(); i++) {
            final Bad bad = bads.get(i);
            BizException e = assertThrows(BizException.class,
                    () -> service.updateStore(4L, bad.dto(), ADMIN_USER_ACTOR), "case=" + i);
            assertEquals(400, e.getCode(), "case=" + i);
            assertEquals(bad.message(), e.getMessage(), "case=" + i);
        }
        verify(storeMapper, never()).updateById(any(Store.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 更新_id不存在404_先于一切body判定() {
        when(storeMapper.selectById(999999L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.updateStore(
                999999L, new AdminStoreUpdateDTO(), ADMIN_USER_ACTOR));

        assertEquals(404, e.getCode());
        assertEquals("门店不存在", e.getMessage(), "契约 §1.2 逐字文案");
        verify(storeMapper, never()).updateById(any(Store.class));
        verifyNoInteractions(auditMapper);
    }

    // ---------------------------------------------------------------- ④ 红线字段

    @Test
    void 红线字段带了被忽略_白名单列照常落库() {
        when(storeMapper.selectById(4L)).thenReturn(
                row(4L, "容桂南堤示范店", "广东省佛山市顺德区容桂示范南路38号", "冷冻冷藏",
                        "closed", null, null, null, 1, null));

        AdminStoreUpdateDTO dto = new AdminStoreUpdateDTO();
        dto.setOwnerUserId(999L);                              // 红线：账号归属
        dto.setContactPhone("13800000000");                    // 红线：PII，主理人端维护
        dto.setPhotos(List.of("/static/images/uploads/x.jpg")); // 红线：上传链路
        dto.setLng(new BigDecimal("113.999999"));              // 红线：F-06 GCJ-02 真源
        dto.setLat(new BigDecimal("22.999999"));               // 红线：同上
        dto.setName("容桂南堤示范店(南)");                       // 白名单列伴随生效

        service.updateStore(4L, dto, ADMIN_USER_ACTOR);

        ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
        verify(storeMapper).updateById(captor.capture());
        Store patch = captor.getValue();
        assertEquals("容桂南堤示范店(南)", patch.getName());
        assertNull(patch.getOwnerUserId(), "owner_user_id 不在落库白名单（null=NOT_NULL 策略不落列）");
        assertNull(patch.getContactPhone(), "contact_phone 不在落库白名单");
        assertNull(patch.getPhotos(), "photos 不在落库白名单");
        assertNull(patch.getLng(), "lng 不在落库白名单");
        assertNull(patch.getLat(), "lat 不在落库白名单");
    }

    @Test
    void 只塞红线字段_八列全空400_列不动() {
        when(storeMapper.selectById(4L)).thenReturn(
                row(4L, "容桂南堤示范店", "广东省佛山市顺德区容桂示范南路38号", "冷冻冷藏",
                        "closed", null, null, null, 1, null));

        AdminStoreUpdateDTO dto = new AdminStoreUpdateDTO();
        dto.setOwnerUserId(999L);
        dto.setContactPhone("13800000000");
        dto.setPhotos(List.of("/static/images/uploads/x.jpg"));

        BizException e = assertThrows(BizException.class, () -> service.updateStore(4L, dto, ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().startsWith("无更新字段"), "探测字段不顶白名单额，全空必须 400 而非空更新");
        verify(storeMapper, never()).updateById(any(Store.class));
        verifyNoInteractions(auditMapper);
    }
}
