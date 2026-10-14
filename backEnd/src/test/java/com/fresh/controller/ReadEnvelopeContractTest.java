package com.fresh.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.R;
import com.fresh.entity.Dish;
import com.fresh.entity.Review;
import com.fresh.service.DishService;
import com.fresh.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F-42b 读接口信封契约单测（不起 Spring 容器、不落库）：
 * DishController#getDishes / DishController#getDishDetail / ReviewController#getReviews
 * 收进统一信封 R 后，序列化出的顶层键集合恰为 {code, data, message}（顶层 total 已按 F-42b 去除），
 * 且 data 子树与"包装前 service 返回值单独序列化"逐字节相等（证明信封只是外层包装，业务载荷未被动）。
 * 404 支（菜品不存在）走 R.fail(R.CODE_NOT_FOUND, ...)，断言 data 显式为 null 键而非缺键——
 * 与 f28 门禁「401 仍显式带 data:null（不是缺键）」同源判据。
 * 序列化用 Jackson2ObjectMapperBuilder.json()，即 Spring Boot MVC 消息转换器同款构造路径
 * （自动注册 classpath 上的 jackson-datatype-jsr310，null 一律输出，与活体基线里
 * f28_base_401.json 的 "data":null 形态一致）。
 */
class ReadEnvelopeContractTest {

    private static final String ENV_KEYS = "[code, data, message]";

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    /** 字段注入的 @Autowired 依赖在纯单测里手工塞入（无容器） */
    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Dish fixtureDish() {
        Dish d = new Dish();
        d.setId(1L);
        d.setName("精品五花肉");
        d.setPrice(new BigDecimal("9.90"));
        d.setImage("/static/images/dish-1.svg");
        d.setDescription("产地直采");
        d.setCategory("meat");
        d.setUnit("斤");
        d.setStock(35);
        d.setOnSale(1);
        d.setSeckill(0);
        d.setSeckillPrice(null);
        d.setSoldCount(3455);
        d.setLimitBuy(5);
        d.setTags(List.of("冷链配送", "新鲜现切"));
        d.setGoodRate("94.3%");
        d.setEmoji("x");
        d.setMerchantId(8L);
        d.setBgColor("#FFF0F0");
        d.setCreateTime(LocalDateTime.of(2026, 10, 10, 23, 22, 42));
        d.setUpdateTime(LocalDateTime.of(2026, 10, 13, 11, 41, 26));
        return d;
    }

    private Review fixtureReview() {
        Review r = new Review();
        r.setId(3L);
        r.setDishId(1L);
        r.setUserId(9L);
        r.setRating(5);
        r.setContent("很好");
        r.setReviewer("王**");
        r.setAuditStatus(null);
        r.setCreateTime(LocalDateTime.of(2026, 10, 12, 8, 0, 0));
        return r;
    }

    /** 顶层键集合断言：恰为 code/data/message，且无 total */
    private JsonNode assertEnvelopeKeys(JsonNode root) {
        assertNotNull(root, "序列化结果不应为 null");
        assertTrue(root.isObject(), "顶层应为 JSON 对象");
        java.util.List<String> keys = keysOf(root);
        assertEquals(ENV_KEYS, new TreeSet<>(keys).toString(), "顶层键集合应恰为信封三键");
        return root;
    }

    @Test
    void getDishesReturnsThreeKeyEnvelopeWithUntouchedDataArray() throws Exception {
        DishService svc = mock(DishService.class);
        List<Dish> list = List.of(fixtureDish(), fixtureDish());
        when(svc.getDishes(eq("meat"), any())).thenReturn(list);
        DishController controller = new DishController();
        inject(controller, "dishService", svc);

        R<List<Dish>> body = controller.getDishes("meat", null);
        JsonNode root = assertEnvelopeKeys(mapper.valueToTree(body));

        assertEquals(200, root.get("code").asInt());
        assertTrue(root.get("data").isArray(), "data 必须仍是数组本体（不得包成 {list,total}）");
        assertEquals(2, root.get("data").size());
        // data 子树逐字节 = 包装前 service 返回值单独序列化
        assertEquals(mapper.readTree(mapper.writeValueAsString(list)).toString(),
                root.get("data").toString(), "data 子树应与包装前序列化结果逐字节一致");
        // 顶层 total（原 list.size()）已去除；message 为 null 键（R.ok 单参工厂语义）
        assertFalse(root.has("total"), "顶层不应再有 total 键");
        assertTrue(root.get("message").isNull());
    }

    @Test
    void getDishDetailSuccessReturnsThreeKeyEnvelopeWithUntouchedDataObject() throws Exception {
        DishService svc = mock(DishService.class);
        Dish dish = fixtureDish();
        when(svc.getDishById(1L)).thenReturn(dish);
        DishController controller = new DishController();
        inject(controller, "dishService", svc);

        R<Dish> body = controller.getDishDetail(1L);
        JsonNode root = assertEnvelopeKeys(mapper.valueToTree(body));

        assertEquals(200, root.get("code").asInt());
        assertEquals(mapper.readTree(mapper.writeValueAsString(dish)).toString(),
                root.get("data").toString(), "data 子树应与包装前序列化结果逐字节一致");
        assertFalse(root.has("total"), "顶层不应再有 total 键");
    }

    @Test
    void getDishDetailNotFoundCarriesExplicitDataNullKey() throws Exception {
        DishService svc = mock(DishService.class);
        when(svc.getDishById(9999L)).thenReturn(null);
        DishController controller = new DishController();
        inject(controller, "dishService", svc);

        R<Dish> body = controller.getDishDetail(9999L);
        JsonNode root = assertEnvelopeKeys(mapper.valueToTree(body));

        assertEquals(404, root.get("code").asInt());
        assertEquals("菜品不存在", root.get("message").asText());
        // 关键判据：data 是显式 null 键，不是缺键（与 f28_base_401.json 的形态同源）
        assertTrue(root.has("data"), "404 分支必须带 data 键");
        assertTrue(root.get("data").isNull(), "404 分支的 data 必须是 null 值");
        // 原始串级判据：键顺序 code,data,message 且 null 不省略
        assertEquals("{\"code\":404,\"data\":null,\"message\":\"菜品不存在\"}",
                mapper.writeValueAsString(body), "404 出参原始 JSON 串应显式带 data:null");
    }

    @Test
    void getReviewsReturnsThreeKeyEnvelopeWithUntouchedDataArray() throws Exception {
        ReviewService svc = mock(ReviewService.class);
        List<Review> list = List.of(fixtureReview());
        when(svc.getReviewsByDishId(1L)).thenReturn(list);
        ReviewController controller = new ReviewController();
        inject(controller, "reviewService", svc);

        R<List<Review>> body = controller.getReviews(1L);
        JsonNode root = assertEnvelopeKeys(mapper.valueToTree(body));

        assertEquals(200, root.get("code").asInt());
        assertTrue(root.get("data").isArray(), "data 必须仍是数组本体");
        assertEquals(mapper.readTree(mapper.writeValueAsString(list)).toString(),
                root.get("data").toString(), "data 子树应与包装前序列化结果逐字节一致");
        assertFalse(root.has("total"), "顶层不应再有 total 键");
    }

    @Test
    void emptyReviewListKeepsDataAsEmptyArrayNotNull() throws Exception {
        ReviewService svc = mock(ReviewService.class);
        when(svc.getReviewsByDishId(1L)).thenReturn(List.of());
        ReviewController controller = new ReviewController();
        inject(controller, "reviewService", svc);

        JsonNode root = assertEnvelopeKeys(mapper.valueToTree(controller.getReviews(1L)));
        // 活体基线 f42_base_dishes_1_reviews.json 的 data 是空数组，收信封后必须仍是 []（非 null）
        assertTrue(root.get("data").isArray());
        assertEquals(0, root.get("data").size());
    }

    /**
     * 负对照（证明上面三条键集合断言不是橡皮章）：把改前那种手工 Map（total/code/data 与 code/message）
     * 原样喂进同一个判据，必须被判为"不是信封三键"。若本方法里的 assertEquals 反过来成立，
     * 说明 assertEnvelopeKeys 恒真、前三条测试没有鉴别力。
     */
    @Test
    void preChangeManualMapShapesAreRejectedByTheSameKeyPredicate() throws Exception {
        java.util.Map<String, Object> oldListResp = new java.util.LinkedHashMap<>();
        oldListResp.put("total", 7);
        oldListResp.put("code", 200);
        oldListResp.put("data", List.of(fixtureDish()));
        String oldListKeys = new TreeSet<>(keysOf(mapper.valueToTree(oldListResp))).toString();
        assertFalse(ENV_KEYS.equals(oldListKeys), "改前的 total/code/data 组合不该被判成信封三键：" + oldListKeys);

        java.util.Map<String, Object> old404Resp = new java.util.LinkedHashMap<>();
        old404Resp.put("code", 404);
        old404Resp.put("message", "菜品不存在");
        JsonNode node = mapper.valueToTree(old404Resp);
        assertEquals("[code, message]", keysOf(node).toString(), "改前 404 支本来就缺 data 键");
        assertFalse(node.has("data"), "判据要能识别改前那个「缺 data 键」");
    }

    private static java.util.List<String> keysOf(JsonNode root) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        root.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
