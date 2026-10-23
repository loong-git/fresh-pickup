package com.fresh.controller;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.MerchantApplyCreateDTO;
import com.fresh.entity.MerchantApply;
import com.fresh.entity.User;
import com.fresh.mapper.MerchantApplyMapper;
import com.fresh.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-107 多证照提交校验契约单测（契约 §0.3 五条，全走
 * {@code POST /api/merchant-applies} 的控制器入口；不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>：
 * ① 合法 JSON 数组 1~6 条、每项 type/url 合规、含 business_license → 原样落库；
 * ② 非法 JSON / 非数组 / 尾随垃圾 → 400；
 * ③ 数量 0 或 >6 → 400；
 * ④ 条目类型/地址缺失、超长、http(s) 外链、非站内前缀 → 400；
 * ⑤ 非空但缺营业执照 → 400；
 * ⑥ 老客户端不传 / 空串 / 纯空白 → 放行，licenses 落 NULL，单图 businessLicense 照旧。
 *
 * <p><b>本类测不成什么</b>：mapper 是 mock，真实列宽（TEXT）与 1406 边界不在证明力内；
 * 校验失败一律在碰库前抛出，故负例统一断言「一次库都没碰」。
 */
class MerchantApplyLicensesControllerTest {

    private static final Long USER_ID = 7L;
    private static final String LICENSE = "/static/images/uploads/license.png";

    private final MerchantApplyMapper applyMapper = mock(MerchantApplyMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);

    private MerchantApplyController controller;

    @BeforeEach
    void setUp() {
        controller = new MerchantApplyController();
        ReflectionTestUtils.setField(controller, "merchantApplyMapper", applyMapper);
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);
    }

    private static User user() {
        User u = new User();
        u.setId(USER_ID);
        u.setPhone("13800001234");
        u.setRole("user");
        u.setMerchantId(null);
        return u;
    }

    private static MerchantApplyCreateDTO dto(String licenses) {
        MerchantApplyCreateDTO dto = new MerchantApplyCreateDTO();
        dto.setMerchantName("演示商家");
        dto.setBusinessLicense(LICENSE);
        dto.setLicenses(licenses);
        return dto;
    }

    private void stubHappyPath() {
        when(userMapper.selectById(USER_ID)).thenReturn(user());
        when(applyMapper.countPendingByUser(USER_ID)).thenReturn(0L);
        when(applyMapper.insert(any(MerchantApply.class))).thenAnswer(inv -> {
            inv.getArgument(0, MerchantApply.class).setId(11L);
            return 1;
        });
    }

    private MerchantApply captureSaved() {
        ArgumentCaptor<MerchantApply> cap = ArgumentCaptor.forClass(MerchantApply.class);
        verify(applyMapper).insert(cap.capture());
        return cap.getValue();
    }

    private void assertRejected(String licenses, String expectedMessage) {
        BizException e = assertThrows(BizException.class, () -> controller.create(USER_ID, dto(licenses)),
                "licenses=" + licenses + " 必须 400");
        assertEquals(400, e.getCode());
        assertEquals(expectedMessage, e.getMessage());
        verify(applyMapper, never()).insert(any());
        verify(userMapper, never()).selectById(any());
    }

    /** 生成 count 条证照：i=0 且 includeBusiness 时第一条为营业执照，其余 type=other */
    private static String licensesJson(int count, boolean includeBusiness) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String type = i == 0 && includeBusiness ? "business_license" : "other";
            sb.append("{\"type\":\"").append(type)
                    .append("\",\"url\":\"/static/images/uploads/").append(i).append(".png\"}");
        }
        return sb.append(']').toString();
    }

    // ---------------------------------------------------------------- 正例

    @Test
    void 合法多证照原样落库且单图字段照旧() {
        stubHappyPath();
        String licenses = "[{\"type\":\"business_license\",\"url\":\"/static/images/uploads/a.png\"},"
                + "{\"type\":\"food_business_license\",\"url\":\"/static/images/uploads/b.png\"}]";

        R<java.util.Map<String, Object>> result = controller.create(USER_ID, dto("  " + licenses + "  "));

        assertEquals(200, result.getCode());
        MerchantApply saved = captureSaved();
        assertEquals(licenses, saved.getLicenses(), "服务端只校验不重组，原文（仅去首尾空白）落库");
        assertEquals(LICENSE, saved.getBusinessLicense(), "单图字段照旧保存，兼容旧读路径");
    }

    @Test
    void 一条与六条边界均合法() {
        stubHappyPath();

        controller.create(USER_ID, dto(licensesJson(1, true)));
        assertEquals(licensesJson(1, true), captureSaved().getLicenses());

        controller.create(USER_ID, dto(licensesJson(6, true)));
        verify(applyMapper, org.mockito.Mockito.times(2)).insert(any(MerchantApply.class));
    }

    // ---------------------------------------------------------------- ① 格式与数量

    @Test
    void 非法JSON与尾随垃圾判四百() {
        assertRejected("not-json", "证照列表格式不正确");
        assertRejected("[{", "证照列表格式不正确");
        assertRejected("{\"type\":\"business_license\"}", "证照列表必须是数组");
        assertRejected(licensesJson(1, true) + "garbage", "证照列表格式不正确");
        assertRejected("null", "证照列表必须是数组");
    }

    @Test
    void 数量为零或超过六判四百() {
        assertRejected("[]", "证照数量需为 1~6 张");
        assertRejected(licensesJson(7, true), "证照数量需为 1~6 张");
    }

    @Test
    void 非对象条目判四百() {
        assertRejected("[\"business_license\"]", "证照条目格式不正确");
        assertRejected("[null]", "证照条目格式不正确");
    }

    @Test
    void 超过四千字判四百() {
        // 长度闸门在 JSON 解析之前：4,002 字符即可命中，不依赖内容合法
        assertRejected("[\"" + "0".repeat(4000) + "\"]", "证照列表内容过长");
    }

    // ---------------------------------------------------------------- ② 每项 type/url

    @Test
    void 类型缺失空白或超长判四百() {
        assertRejected("[{\"url\":\"" + LICENSE + "\"}]", "证照类型不合法");
        assertRejected("[{\"type\":\"   \",\"url\":\"" + LICENSE + "\"}]", "证照类型不合法");
        assertRejected("[{\"type\":\"" + "x".repeat(33) + "\",\"url\":\"" + LICENSE + "\"}]", "证照类型不合法");
        assertRejected("[{\"type\":123,\"url\":\"" + LICENSE + "\"}]", "证照类型不合法");
    }

    @Test
    void 地址缺失空白或超长判四百() {
        assertRejected("[{\"type\":\"business_license\"}]", "证照图片地址不合法，仅支持站内上传的图片");
        assertRejected("[{\"type\":\"business_license\",\"url\":\"   \"}]", "证照图片地址不合法，仅支持站内上传的图片");
        assertRejected("[{\"type\":\"business_license\",\"url\":\"" + LICENSE + "x".repeat(256) + "\"}]",
                "证照图片地址不合法，仅支持站内上传的图片");
    }

    // ---------------------------------------------------------------- ③ 外链拒绝

    @Test
    void http与https外链一律判四百() {
        assertRejected("[{\"type\":\"business_license\",\"url\":\"http://evil.example.com/x.png\"}]",
                "证照图片地址不合法，仅支持站内上传的图片");
        assertRejected("[{\"type\":\"business_license\",\"url\":\"https://evil.example.com/x.png\"}]",
                "证照图片地址不合法，仅支持站内上传的图片");
        // 只认绝对前缀：缺前导斜杠、协议相对地址、相似前缀均拒绝
        assertRejected("[{\"type\":\"business_license\",\"url\":\"static/images/uploads/x.png\"}]",
                "证照图片地址不合法，仅支持站内上传的图片");
        assertRejected("[{\"type\":\"business_license\",\"url\":\"//evil.example.com/x.png\"}]",
                "证照图片地址不合法，仅支持站内上传的图片");
        assertRejected("[{\"type\":\"business_license\",\"url\":\"/static/images/uploads-evil/x.png\"}]",
                "证照图片地址不合法，仅支持站内上传的图片");
    }

    // ---------------------------------------------------------------- ④ 营业执照门槛

    @Test
    void 非空但缺营业执照判四百() {
        assertRejected("[{\"type\":\"food_business_license\",\"url\":\"" + LICENSE + "\"}]",
                "请至少上传一张营业执照");
        assertRejected(licensesJson(6, false), "请至少上传一张营业执照");
    }

    // ---------------------------------------------------------------- 老客户端兼容

    @Test
    void 老客户端不传licenses仍可提交且单图字段照旧() {
        stubHappyPath();

        R<java.util.Map<String, Object>> result = controller.create(USER_ID, dto(null));

        assertEquals(200, result.getCode());
        MerchantApply saved = captureSaved();
        assertNull(saved.getLicenses(), "未传 licenses → NULL，不影响 old 客户端");
        assertEquals(LICENSE, saved.getBusinessLicense());
    }

    @Test
    void 空串与纯空白licenses等同未传() {
        stubHappyPath();

        controller.create(USER_ID, dto(""));
        assertNull(captureSaved().getLicenses(), "空串 = 未传");

        controller.create(USER_ID, dto("   "));
        verify(applyMapper, org.mockito.Mockito.times(2)).insert(any(MerchantApply.class));
    }
}
