package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.common.WordFilter;
import com.fresh.dto.MerchantDishCreateDTO;
import com.fresh.dto.MerchantDishUpdateDTO;
import com.fresh.dto.PartnerStoreUpdateDTO;
import com.fresh.dto.StoreApplyCreateDTO;
import com.fresh.entity.Dish;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.StoreApplyMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.MerchantDishService;
import com.fresh.service.MerchantService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-2 P3-F：商品/门店/申请三处自由文本接入 WordFilter 的离线单测（不起容器、不落库）。
 *
 * <p>词表用<b>真词表</b>：{@code new WordFilter(); load()} 读 test classpath 上的
 * sensitive-words.txt（target/classes 副本，与生产同一份文件）——硬词取 {@code 刷单}、软词取
 * {@code 假货}，均在库（实读词表确认，见该文件硬词区第 9 行与软词区）。
 *
 * <p>覆盖面（清单 P3-F 验收口径）：三处各至少 1 例硬词 400 + 商品软词 pending（create 置
 * {@link Dish#AUDIT_PENDING}；update 仅当品仍 'ok' 才置，rejected 品改品不洗白不变式原样保留）。
 * 门店/申请侧只拦硬词——两处本就有人工审核管线，软词无动作（清单裁定）。
 *
 * <p>Mock 姿势照 {@code DishAuditFlowTest}/{@code MerchantDishSeckillPriceGuardTest}：
 * 字段注入逐个灌 mock + MP TableInfo 内存缓存（LambdaUpdateWrapper 列名解析需要）。
 */
class P3FFreeTextWordFilterTest {

    /** 硬词（sensitive-words.txt 在库，一票 400） */
    private static final String HARD_WORD = "刷单";
    /** 软词（sensitive-words.txt 在库，商品侧命中 → audit_status=pending） */
    private static final String SOFT_WORD = "假货";

    private final DishMapper dishMapper = mock(DishMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final StoreApplyMapper storeApplyMapper = mock(StoreApplyMapper.class);

    /** 真词表实例：走生产同一份 sensitive-words.txt（load 一次，全类共用） */
    private WordFilter wordFilter;

    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Dish.class);
        // 门店软词用例走 findOwnedStore 的 LambdaQueryWrapper（.select(Store::...)），同样需要列缓存
        TableInfoHelper.initTableInfo(assistant, com.fresh.entity.Store.class);
    }

    @BeforeEach
    void setUp() {
        wordFilter = new WordFilter();
        wordFilter.load();
    }

    // ------------------------------------------------------------------
    // ① 商品（MerchantDishServiceImpl#create / #update）
    // ------------------------------------------------------------------

    private MerchantDishServiceImpl dishService() {
        MerchantDishServiceImpl service = new MerchantDishServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "merchantService", mock(MerchantService.class));
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(service, "wordFilter", wordFilter);
        return service;
    }

    private static MerchantDishCreateDTO createDto(String name, String description) {
        MerchantDishCreateDTO dto = new MerchantDishCreateDTO();
        dto.setName(name);
        dto.setPrice(new BigDecimal("9.90"));
        dto.setCategory("meat");
        dto.setUnit("斤");
        dto.setStock(30);
        dto.setDescription(description);
        return dto;
    }

    @Test
    void 商品创建名称命中硬词_400且不落库() {
        MerchantDishService service = dishService();

        BizException ex = assertThrows(BizException.class,
                () -> service.create(11L, 2L, createDto("极速" + HARD_WORD + "代发", "产地直采")));

        assertEquals("商品信息包含违规内容，请修改后重新提交", ex.getMessage(), "文案照评价链「包含违规内容」族 + 修改指引");
        assertEquals(400, ex.getCode());
        verify(dishMapper, never()).insert(any(Dish.class));
    }

    @Test
    void 商品更新描述命中硬词_400且不发UPDATE() {
        MerchantDishService service = dishService();
        MerchantDishUpdateDTO dto = new MerchantDishUpdateDTO();
        dto.setDescription("支持" + HARD_WORD + "返现");

        BizException ex = assertThrows(BizException.class,
                () -> service.update(11L, 2L, 10L, dto));

        assertEquals("商品信息包含违规内容，请修改后重新提交", ex.getMessage());
        verify(dishMapper, never()).update(any(), any());
    }

    @Test
    void 商品创建命中软词_进待审pending不发ok() {
        MerchantDishServiceImpl service = dishService();
        when(dishMapper.insert(any(Dish.class))).thenAnswer(inv -> {
            Dish d = inv.getArgument(0);
            d.setId(99L);
            return 1;
        });
        when(dishMapper.selectOne(any())).thenReturn(dish(99L, Dish.AUDIT_PENDING));

        Dish created = service.create(11L, 2L, createDto("生态土鸡蛋", "个别顾客反映像" + SOFT_WORD));

        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).insert(captor.capture());
        assertEquals(Dish.AUDIT_PENDING, captor.getValue().getAuditStatus(),
                "软词命中 → 先发后审 pending（复用 m26 audit_status 列；C 端可见性恒 =ok，恢复唯一入口=运营 restore）");
    }

    @Test
    void 商品更新命中软词_品仍ok时置pending_驳回品不洗白() {
        MerchantDishServiceImpl service = dishService();
        // before=ok：软词命中 → SET 带 audit_status 且绑 'pending'
        when(dishMapper.selectOne(any())).thenReturn(dish(10L, Dish.AUDIT_OK));
        when(dishMapper.update(isNull(), any())).thenReturn(1);   // 无 tags → patch=null 纯 wrapper SET
        MerchantDishUpdateDTO dto = new MerchantDishUpdateDTO();
        dto.setDescription("有买家说是" + SOFT_WORD);

        service.update(11L, 2L, 10L, dto);

        LambdaUpdateWrapper<Dish> okWrapper = capturedUpdateWrapper();
        assertTrue(okWrapper.getSqlSet().contains("audit_status"),
                "update 侧软词同口径：正常在售品命中软词 → 置 pending 离开 C 端可见集：SET=" + okWrapper.getSqlSet());
        assertTrue(okWrapper.getParamNameValuePairs().containsValue(Dish.AUDIT_PENDING),
                "绑定的必须是 'pending' 值");

        // before=rejected：改品不得改写审核态（F-116 §2.4「防随手保存洗白」不变式），rejected 是运营更强裁定
        org.mockito.Mockito.reset(dishMapper);
        when(dishMapper.selectOne(any())).thenReturn(dish(10L, Dish.AUDIT_REJECTED));
        when(dishMapper.update(isNull(), any())).thenReturn(1);

        service.update(11L, 2L, 10L, dto);

        LambdaUpdateWrapper<Dish> rejectedWrapper = capturedUpdateWrapper();
        assertFalse(rejectedWrapper.getSqlSet().contains("audit_status"),
                "驳回品改品仍 rejected（恢复唯一入口=运营 restore）：SET=" + rejectedWrapper.getSqlSet());
    }

    private LambdaUpdateWrapper<Dish> capturedUpdateWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper<Dish>> captor =
                (ArgumentCaptor<LambdaUpdateWrapper<Dish>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(dishMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private static Dish dish(long id, String auditStatus) {
        Dish d = new Dish();
        d.setId(id);
        d.setName("演示品");
        d.setPrice(new BigDecimal("9.90"));
        d.setStock(20);
        d.setOnSale(1);
        d.setAuditStatus(auditStatus);
        d.setMerchantId(2L);
        return d;
    }

    // ------------------------------------------------------------------
    // ② 门店 service（PartnerStoreServiceImpl#updateStore——只拦硬词）
    // ------------------------------------------------------------------

    @Test
    void 门店服务说明命中硬词_400且不落库() {
        PartnerStoreServiceImpl service = new PartnerStoreServiceImpl();
        ReflectionTestUtils.setField(service, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(service, "wordFilter", wordFilter);
        PartnerStoreUpdateDTO dto = new PartnerStoreUpdateDTO();
        dto.setService("每日现切，" + HARD_WORD + "返利，欢迎选购");

        BizException ex = assertThrows(BizException.class,
                () -> service.updateStore(5L, List.of(1L), null, dto));

        assertEquals("门店服务说明包含违规内容，请修改后重新提交", ex.getMessage());
        // 400 落在值校验位（与 contactPhone 形态校验同位），任何读库/写库动作都不发生
        verify(storeMapper, never()).update(any(), any());
        verify(storeMapper, never()).selectOne(any());
    }

    @Test
    void 门店服务说明软词不拦_照既有人工链路_无审核态动作() {
        PartnerStoreServiceImpl service = new PartnerStoreServiceImpl();
        ReflectionTestUtils.setField(service, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(service, "wordFilter", wordFilter);
        PartnerStoreUpdateDTO dto = new PartnerStoreUpdateDTO();
        dto.setService("拒绝" + SOFT_WORD + "，只卖产地直采");

        // store 表无审核态列（清单裁定软词无动作）：软词文本不因敏感词被 400；
        // 正常走完 UPDATE（mock 回 1 行）——后续 ops_log 走 OpsLogWriter，这里只断言不被词表拦
        com.fresh.entity.Store before = new com.fresh.entity.Store();
        before.setId(1L);
        before.setService("旧说明");
        com.fresh.entity.Store updated = new com.fresh.entity.Store();
        updated.setId(1L);
        updated.setService("拒绝" + SOFT_WORD + "，只卖产地直采");
        when(storeMapper.selectOne(any())).thenReturn(before, updated);   // 审计预读 + 落库后回查
        when(storeMapper.update(any(), any())).thenReturn(1);
        com.fresh.service.OpsLogWriter opsLogWriter = mock(com.fresh.service.OpsLogWriter.class);
        ReflectionTestUtils.setField(service, "opsLogWriter", opsLogWriter);

        Map<String, Object> view = service.updateStore(5L, List.of(1L), null, dto);

        assertEquals("拒绝" + SOFT_WORD + "，只卖产地直采", view.get("service"),
                "软词照既有链路放行落库（门店侧无 pending 管线，清单裁定）");
        verify(storeMapper).update(any(), any());
    }

    // ------------------------------------------------------------------
    // ③ 入驻申请（StoreApplyServiceImpl#create——只拦硬词）
    // ------------------------------------------------------------------

    @Test
    void 申请门店名命中硬词_400且不落申请行() {
        StoreApplyServiceImpl service = new StoreApplyServiceImpl();
        ReflectionTestUtils.setField(service, "storeApplyMapper", storeApplyMapper);
        ReflectionTestUtils.setField(service, "wordFilter", wordFilter);
        StoreApplyCreateDTO dto = applyDto();
        dto.setProposedName("社区" + HARD_WORD + "超市");

        BizException ex = assertThrows(BizException.class, () -> service.create(7L, dto));

        assertEquals("申请信息包含违规内容，请修改后重新提交", ex.getMessage());
        verify(storeApplyMapper, never()).insert(any());
    }

    @Test
    void 申请服务说明命中硬词_400且不落申请行() {
        StoreApplyServiceImpl service = new StoreApplyServiceImpl();
        ReflectionTestUtils.setField(service, "storeApplyMapper", storeApplyMapper);
        ReflectionTestUtils.setField(service, "wordFilter", wordFilter);
        StoreApplyCreateDTO dto = applyDto();
        dto.setService("到店自提，可" + HARD_WORD);

        BizException ex = assertThrows(BizException.class, () -> service.create(7L, dto));

        assertEquals("申请信息包含违规内容，请修改后重新提交", ex.getMessage());
        verify(storeApplyMapper, never()).insert(any());
    }

    private static StoreApplyCreateDTO applyDto() {
        StoreApplyCreateDTO dto = new StoreApplyCreateDTO();
        dto.setProposedName("社区鲜果超市");
        dto.setProposedAddress("幸福路 12 号");
        dto.setLng(BigDecimal.valueOf(104.06));
        dto.setLat(BigDecimal.valueOf(30.65));
        return dto;
    }

    // ------------------------------------------------------------------
    // 词表自检：本类依赖的「刷单=硬词、假货=软词」必须真在库——词表内容被改动时在此显式红
    // （红线：本测试只读词表做断言，绝不改 sensitive-words.txt）
    // ------------------------------------------------------------------

    @Test
    void 依赖词目确在库_刷单为硬词_假货为软词() throws Exception {
        assertNotNull(wordFilter.matchHard(HARD_WORD), HARD_WORD + " 必须是硬词（用例的事实前提）");
        assertNull(wordFilter.matchSoft(HARD_WORD));
        assertNotNull(wordFilter.matchSoft(SOFT_WORD), SOFT_WORD + " 必须是软词（用例的事实前提）");
        assertNull(wordFilter.matchHard(SOFT_WORD));
        // 反射读两表规模（>0），防词表加载退化为空表后上面两条断言因「未加载」假红难定位
        Field hardField = WordFilter.class.getDeclaredField("hardWords");
        hardField.setAccessible(true);
        Field softField = WordFilter.class.getDeclaredField("softWords");
        softField.setAccessible(true);
        assertFalse(((List<?>) hardField.get(wordFilter)).isEmpty(), "硬词表不应为空（load 未生效？）");
        assertFalse(((List<?>) softField.get(wordFilter)).isEmpty(), "软词表不应为空（load 未生效？）");
    }
}
