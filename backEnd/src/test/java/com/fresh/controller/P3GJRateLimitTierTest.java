package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * P11-2 P3-G + P3-J：C 端读端点限流补档与领券写端点挂档的离线断言（不起容器）。
 *
 * <p>范式照 {@code AdminAliasControllerTest#四端点限流档位符合契约}：反射读 @RateLimit 注解，
 * 逐端点断言 limit/windowSeconds/keyType，并以「全端点必须都挂上」收口（少一个都算漏挂）。
 * 档位出处（家族先例，实读后抄）：
 * <ul>
 *   <li>匿名读 IP 30/60s —— StoreController:27（BE-8「读 30/分/IP」既有姿势）；</li>
 *   <li>登录读 USER 30/60s —— MerchantDishController:48 列表读档（keyType 缺省 USER）；</li>
 *   <li>写 10/60s USER —— 商家写端点家族 MerchantDishController:66/75/86/96、
 *       MerchantApplyController:104/191（P3-G claim 照此挂档）。</li>
 * </ul>
 * USER 档对匿名请求的行为已实读 RateLimitInterceptor#bucketKey 确认：userId 为 null 时兜底
 * {@code user:ip:<ip>:<api>}，不报错且生效；但挂档语义仍按端点登录语义分档——
 * 本测试同时钉住「匿名端点挂 IP 档、登录端点挂 USER 档」的口径，防后续漂移。
 */
class P3GJRateLimitTierTest {

    /** key：Controller 简名#方法名；value：{limit, windowSeconds, keyType} */
    private static final Map<String, Object[]> EXPECTED = new LinkedHashMap<>();

    static {
        // P3-G：claim 写档 10/60s USER（商家写端点家族）
        EXPECTED.put("CouponController#claim", new Object[]{10, 60, RateLimit.KeyType.USER});
        // P3-J 登录读：USER 30/60s
        EXPECTED.put("CouponController#center", new Object[]{30, 60, RateLimit.KeyType.USER});
        EXPECTED.put("CouponController#myCoupons", new Object[]{30, 60, RateLimit.KeyType.USER});
        EXPECTED.put("OrderController#getOrders", new Object[]{30, 60, RateLimit.KeyType.USER});
        EXPECTED.put("ReviewController#myReviews", new Object[]{30, 60, RateLimit.KeyType.USER});
        EXPECTED.put("InviteController#me", new Object[]{30, 60, RateLimit.KeyType.USER});
        EXPECTED.put("InviteController#cash", new Object[]{30, 60, RateLimit.KeyType.USER});
        EXPECTED.put("FreeController#myClaims", new Object[]{30, 60, RateLimit.KeyType.USER});
        // P3-J 匿名读：IP 30/60s
        EXPECTED.put("DishController#getDishes", new Object[]{30, 60, RateLimit.KeyType.IP});
        EXPECTED.put("DishController#getDishDetail", new Object[]{30, 60, RateLimit.KeyType.IP});
        EXPECTED.put("DishController#recommend", new Object[]{30, 60, RateLimit.KeyType.IP});
        EXPECTED.put("SeckillController#current", new Object[]{30, 60, RateLimit.KeyType.IP});
        EXPECTED.put("ReviewController#getReviews", new Object[]{30, 60, RateLimit.KeyType.IP});
        // FreeController#current 是既有挂档（M7 起即 IP 30/60s），一并钉住防回归
        EXPECTED.put("FreeController#current", new Object[]{30, 60, RateLimit.KeyType.IP});
    }

    @Test
    void 读写端点限流档位符合家族值() throws Exception {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Object[]> entry : EXPECTED.entrySet()) {
            String[] parts = entry.getKey().split("#");
            Class<?> controller = Class.forName("com.fresh.controller." + parts[0]);
            Method method = findMethod(controller, parts[1]);
            assertNotNull(method, entry.getKey() + " 方法不存在（改名后请同步本测试）");

            RateLimit rateLimit = method.getAnnotation(RateLimit.class);
            assertNotNull(rateLimit, entry.getKey() + " 必须挂 @RateLimit");
            Object[] spec = entry.getValue();
            assertEquals(spec[0], rateLimit.limit(), entry.getKey() + " 的 limit");
            assertEquals(spec[1], rateLimit.windowSeconds(), entry.getKey() + " 的 windowSeconds");
            assertEquals(spec[2], rateLimit.keyType(), entry.getKey() + " 的 keyType（匿名端点 IP 档 / 登录端点 USER 档）");
            seen.add(entry.getKey());
        }
        assertEquals(EXPECTED.keySet(), seen, "全部端点必须断言到位（少一个都算漏挂）");
    }

    /** 按名取首个声明方法（本组端点无重载；找不到返回 null 交上方 assertNotNull 报名指路） */
    private static Method findMethod(Class<?> controller, String name) {
        for (Method method : controller.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
