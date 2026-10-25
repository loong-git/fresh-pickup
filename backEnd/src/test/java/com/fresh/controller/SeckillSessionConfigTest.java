package com.fresh.controller;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.AdminDictItemCreateDTO;
import com.fresh.dto.AdminDictItemUpdateDTO;
import com.fresh.entity.DictGroup;
import com.fresh.entity.DictItem;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DictGroupMapper;
import com.fresh.mapper.DictItemMapper;
import com.fresh.service.DictService;
import com.fresh.service.DishService;
import com.fresh.service.impl.AdminServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-115 秒杀场次契约化离线单测（不起 Spring 容器、不落库）：
 * <b>要证明的事</b>（契约 §4 四组）：
 * ① 默认路径：组词条齐且合法 → /api/seckill/current 出参 start/end 与词条一致、四键形状与常量时代逐字同形；
 * ② 读侧回退三分支：组不存在（getGroup 抛 BizException）/ 词条缺失 / definition 非法（"25:00"、"9:00"）
 *    → 该侧回退 00:00/23:00 且恒 code=200，配置错误绝不挂 C 端秒杀页；
 * ③ 管理端写侧特判（§2.2 只影响 seckill_session 组）：word=midnight 拒、definition="8点" 拒、
 *    写入后 start ≥ end 拒（create 与 update 双路），文案逐字断言；
 * ④ 既有 dict 组零回归：dish_category / service_tags 的 create/update 不走特判，任意 word/definition
 *    照旧落库（AdminDictServiceTest 的既有断言不变，全量回归由门禁统一跑）。
 */
class SeckillSessionConfigTest {

    private static final String ACTOR = "admin_user:7";

    private final DishService dishService = mock(DishService.class);
    private final DictService dictService = mock(DictService.class);
    private final DictGroupMapper groupMapper = mock(DictGroupMapper.class);
    private final DictItemMapper itemMapper = mock(DictItemMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private SeckillController controller;
    private AdminServiceImpl adminService;

    @BeforeEach
    void setUp() {
        controller = new SeckillController();
        ReflectionTestUtils.setField(controller, "dishService", dishService);
        ReflectionTestUtils.setField(controller, "dictService", dictService);

        adminService = new AdminServiceImpl();
        ReflectionTestUtils.setField(adminService, "dictGroupMapper", groupMapper);
        ReflectionTestUtils.setField(adminService, "dictItemMapper", itemMapper);
        ReflectionTestUtils.setField(adminService, "dictService", dictService);
        ReflectionTestUtils.setField(adminService, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(adminService, "adminKey", "test-admin-key");
    }

    // ---------------------------------------------------------------- fixtures

    private static Map<String, Object> entry(String word, String definition) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("word", word);
        m.put("definition", definition);
        m.put("sortOrder", 10L);
        return m;
    }

    /** getGroup 出参模拟：null 侧=词条缺失（不进列表） */
    private static List<Map<String, Object>> session(String start, String end) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (start != null) {
            list.add(entry("start", start));
        }
        if (end != null) {
            list.add(entry("end", end));
        }
        return list;
    }

    private static DictGroup group(String code) {
        DictGroup g = new DictGroup();
        g.setCode(code);
        g.setName(code);
        g.setEnabled(1);
        g.setSort(50);
        return g;
    }

    private static DictItem dictItem(long id, String groupCode, String word, String definition) {
        DictItem it = new DictItem();
        it.setId(id);
        it.setGroupCode(groupCode);
        it.setWord(word);
        it.setDefinition(definition);
        it.setSortOrder(10);
        return it;
    }

    private static AdminDictItemCreateDTO createDto(String word, String definition, Integer sortOrder) {
        AdminDictItemCreateDTO dto = new AdminDictItemCreateDTO();
        dto.setWord(word);
        dto.setDefinition(definition);
        dto.setSortOrder(sortOrder);
        return dto;
    }

    private static AdminDictItemUpdateDTO updateDto(String word, String definition, Integer sortOrder) {
        AdminDictItemUpdateDTO dto = new AdminDictItemUpdateDTO();
        dto.setWord(word);
        dto.setDefinition(definition);
        dto.setSortOrder(sortOrder);
        return dto;
    }

    // ---------------------------------------------------------------- ① 默认路径

    @Test
    void 默认路径_词条齐且合法_出参与词条一致且四键形状零改动() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("06:00", "20:00"));
        when(dishService.listSeckill()).thenReturn(List.of());

        R<Map<String, Object>> out = controller.current();

        assertEquals(R.CODE_OK, out.getCode());
        Map<String, Object> data = out.getData();
        assertEquals("06:00", data.get("start"), "start 取词条 definition 原值");
        assertEquals("20:00", data.get("end"), "end 取词条 definition 原值");
        assertEquals(List.of("serverTime", "start", "end", "dishes"), new ArrayList<>(data.keySet()),
                "出参四键与常量时代逐字同形（契约 §2.1 形状零改动）");
        assertNotNull(data.get("serverTime"), "serverTime 照旧取服务端时钟");
        assertNotNull(data.get("dishes"), "dishes 照旧出自 listSeckill");
    }

    // ---------------------------------------------------------------- ② 读侧回退三分支

    @Test
    void 回退_组不存在getGroup抛BizException_双侧回退默认值仍200() {
        when(dictService.getGroup("seckill_session")).thenThrow(new BizException("字典分组不存在"));

        R<Map<String, Object>> out = controller.current();

        assertEquals(R.CODE_OK, out.getCode(), "配置错误绝不挂 C 端秒杀页");
        assertEquals("00:00", out.getData().get("start"));
        assertEquals("23:00", out.getData().get("end"));
    }

    @Test
    void 回退_词条缺失_缺侧回退另一侧照常透出() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("08:00", null));

        R<Map<String, Object>> out = controller.current();

        assertEquals("08:00", out.getData().get("start"), "在场的词条照常透出");
        assertEquals("23:00", out.getData().get("end"), "end 缺失该侧回退");
        assertEquals(R.CODE_OK, out.getCode());
    }

    @Test
    void 回退_组被停用空列表_双侧回退默认值仍200() {
        when(dictService.getGroup("seckill_session")).thenReturn(List.of());

        R<Map<String, Object>> out = controller.current();

        assertEquals(R.CODE_OK, out.getCode());
        assertEquals("00:00", out.getData().get("start"));
        assertEquals("23:00", out.getData().get("end"));
    }

    @Test
    void 回退_非法值25点与9点_双非法双侧回退仍200() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("25:00", "9:00"));

        R<Map<String, Object>> out = controller.current();

        assertEquals(R.CODE_OK, out.getCode());
        assertEquals("00:00", out.getData().get("start"), "25:00 越界不匹配 HH:MM → 回退");
        assertEquals("23:00", out.getData().get("end"), "9:00 缺位补零不匹配 → 回退");
    }

    @Test
    void 回退_单侧非法_仅非法侧回退合法侧照常透出() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("21:30", "25:00"));

        R<Map<String, Object>> out = controller.current();

        assertEquals("21:30", out.getData().get("start"));
        assertEquals("23:00", out.getData().get("end"), "回退按侧独立判定");
    }

    // ---------------------------------------------------------------- ③ 写侧特判（§2.2）

    @Test
    void 特判_create_word越出start_end白名单400且不落库() {
        when(groupMapper.selectById("seckill_session")).thenReturn(group("seckill_session"));

        BizException e = assertThrows(BizException.class,
                () -> adminService.createDictItem("seckill_session", createDto("midnight", "00:00", null), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("该组仅允许 start/end 词条", e.getMessage());
        verify(itemMapper, never()).insert(any(DictItem.class));
        verify(dictService, never()).evict(anyString());
    }

    @Test
    void 特判_create_definition非HHMM格式400文案含格式说明() {
        when(groupMapper.selectById("seckill_session")).thenReturn(group("seckill_session"));

        BizException e = assertThrows(BizException.class,
                () -> adminService.createDictItem("seckill_session", createDto("start", "8点", null), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("场次时刻须为 HH:MM 格式（00:00~23:59，如 06:00）", e.getMessage());
        verify(itemMapper, never()).insert(any(DictItem.class));
    }

    @Test
    void 特判_create_写入后start晚于组内end400场次开始须早于结束() {
        when(groupMapper.selectById("seckill_session")).thenReturn(group("seckill_session"));
        // 组内现有 end=10:00（异常存量）：create start=20:00 → 写入后 20:00 ≥ 10:00
        when(itemMapper.selectByGroup("seckill_session")).thenReturn(sessionRows(12L, "end", "10:00"));

        BizException e = assertThrows(BizException.class,
                () -> adminService.createDictItem("seckill_session", createDto("start", "20:00", null), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("场次开始须早于结束", e.getMessage());
        verify(itemMapper, never()).insert(any(DictItem.class));
    }

    @Test
    void 特判_update_生效值按时序拒_相等也拒() {
        when(itemMapper.selectById(11L))
                .thenReturn(dictItem(11L, "seckill_session", "start", "06:00"));
        when(itemMapper.selectByGroup("seckill_session")).thenReturn(List.of(
                dictItem(11L, "seckill_session", "start", "06:00"),
                dictItem(12L, "seckill_session", "end", "23:00")));

        BizException e = assertThrows(BizException.class,
                () -> adminService.updateDictItem(11L, updateDto(null, "23:00", null), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("场次开始须早于结束", e.getMessage(), "start 改到与 end 相等同样拒（start ≥ end）");
        verify(itemMapper, never()).updateById(any(DictItem.class));
        verify(dictService, never()).evict(anyString());
    }

    @Test
    void 特判_update_合法值照常落库并evict() {
        when(itemMapper.selectById(12L))
                .thenReturn(dictItem(12L, "seckill_session", "end", "23:00"));
        when(itemMapper.selectByGroup("seckill_session")).thenReturn(List.of(
                dictItem(11L, "seckill_session", "start", "06:00"),
                dictItem(12L, "seckill_session", "end", "23:00")));
        when(itemMapper.updateById(any(DictItem.class))).thenReturn(1);

        adminService.updateDictItem(12L, updateDto(null, "20:00", null), ACTOR);

        verify(itemMapper).updateById(any(DictItem.class));
        verify(dictService).evict("seckill_session");
    }

    @Test
    void 特判_create_合法词条照常落库并evict() {
        when(groupMapper.selectById("seckill_session")).thenReturn(group("seckill_session"));
        when(itemMapper.selectByGroup("seckill_session")).thenReturn(sessionRows(11L, "start", "06:00"));
        when(itemMapper.insert(any(DictItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, DictItem.class).setId(99L);
            return 1;
        });

        adminService.createDictItem("seckill_session", createDto("end", "20:00", null), ACTOR);

        verify(itemMapper).insert(any(DictItem.class));
        verify(dictService).evict("seckill_session");
    }

    // ---------------------------------------------------------------- ④ 既有 dict 组零回归

    @Test
    void 零回归_dish_category组建词条不走特判_任意word照旧落库() {
        when(groupMapper.selectById("dish_category")).thenReturn(group("dish_category"));
        when(itemMapper.insert(any(DictItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, DictItem.class).setId(98L);
            return 1;
        });

        // word=海鲜 不在 start/end 白名单、definition 非 HH:MM——若特判误伤其他组这里即 400
        adminService.createDictItem("dish_category", createDto("海鲜", "code:seafood", 1), ACTOR);

        verify(itemMapper).insert(any(DictItem.class));
        // 时序校验只查 seckill_session 组，其他组不得触发组内查询
        verify(itemMapper, never()).selectByGroup(anyString());
        verify(dictService).evict("dish_category");
    }

    @Test
    void 零回归_其他组update_definition任意值照旧成功() {
        when(itemMapper.selectById(5L))
                .thenReturn(dictItem(5L, "service_tags", "次日达", "tag:nextday"));
        when(itemMapper.updateById(any(DictItem.class))).thenReturn(1);

        adminService.updateDictItem(5L, updateDto(null, "8点", null), ACTOR);

        verify(itemMapper).updateById(any(DictItem.class));
        verify(itemMapper, never()).selectByGroup(anyString());
        verify(dictService).evict("service_tags");
    }

    /** 组内单行模拟（时序校验的 selectByGroup 返回值） */
    private static List<DictItem> sessionRows(long id, String word, String definition) {
        return List.of(dictItem(id, "seckill_session", word, definition));
    }
}
