package com.fresh.common;

/**
 * 手机号脱敏工具（F-17/G-03 RD 红线「脱敏不可绕过」的唯一出口）。
 *
 * <p>输出形态 {@code 138****1234}，掩码结果与 {@code OrderServiceImpl#maskPhone}（现位于 :351-353，
 * 契约 F-17.2 #5 记作 347-349 是改动前的行号——差异已登记「契约疑点」）**逐字相同**：保留前 3 位、
 * 中间固定 4 个星号、保留第 8 位起的尾串。对合法 11 位号码，本方法与既有四份私有实现
 * （OrderServiceImpl / UserController / AdminServiceImpl / MerchantServiceImpl）输出完全相等，
 * 故 {@code MerchantServiceImpl} 改调本方法不构成行为变更（现库 19 笔 orders.phone 与 5 笔
 * merchant.contact_phone 实测 CHAR_LENGTH 恒为 11）。
 *
 * <p>唯一差异在母版**抛异常**的那段定义域（null 或长度 &lt; 7）：母版是裸 substring，脏值会抛
 * StringIndexOutOfBoundsException 并被 GlobalExceptionHandler 兜底成 500。本方法在该区间不抛、
 * 也绝不回明文——长度不足一律回 {@code "****"}，null 原样回 null。理由：orders.phone 是
 * VARCHAR(20) 而非 CHAR(11)，读端点把脏数据变成 500 既不可用也无处排查；而「非 11 位原样返回」
 * （UserController:126 的口径）会把短号明文发到商家侧，正面违反 RD。两个失败模式都排除后只剩
 * 「全掩码」这一种取值。合法值路径未受影响。
 *
 * <p>本类只新增工具，不回收 OrderServiceImpl / UserController / AdminServiceImpl 三份私有实现
 * （RE 红线：C 端交易链路与 Admin 侧一字不动；登记为 F-17.7 #7 的既有债务）。
 */
public final class PhoneMask {

    /** 掩码串：4 个星号，与四份母版逐字一致 */
    private static final String MASK = "****";

    /** 母版 substring(0, 3) + substring(7) 能成功的最小长度（长度 7 时尾段为空串） */
    private static final int MIN_MASKABLE_LENGTH = 7;

    private PhoneMask() {
    }

    /**
     * @param phone 明文手机号（可为 null）
     * @return 脱敏值：长度 {@value #MIN_MASKABLE_LENGTH} 及以上按母版口径掩码；更短回 {@value #MASK}；
     *         null 回 null。返回值**永不**是入参原文（除非入参本身已含星号且长度不足，取值仍为 {@value #MASK}）。
     */
    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        if (phone.length() < MIN_MASKABLE_LENGTH) {
            return MASK;
        }
        return phone.substring(0, 3) + MASK + phone.substring(7);
    }
}
