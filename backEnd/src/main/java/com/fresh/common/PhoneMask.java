package com.fresh.common;

/**
 * 手机号脱敏工具（F-17/G-03 RD 红线「脱敏不可绕过」的唯一出口）。
 *
 * <p>输出形态 {@code 138****1234}，掩码结果与 {@code OrderServiceImpl#maskPhone}（现位于 :397-399，
 * 契约 F-17.2 #5 记作 347-349 是改动前的行号——差异已登记「契约疑点」）**逐字相同**：保留前 3 位、
 * 中间固定 4 个星号、保留第 8 位起的尾串。对合法 11 位号码，本方法与 F-17 当时那四份私有实现
 * （OrderServiceImpl / UserController / AdminServiceImpl / MerchantServiceImpl）输出完全相等（2026-10-21 实测：本仓<b>现存</b>私有 maskPhone 是 5 份 = 前三者 + FreeServiceImpl + ReviewServiceImpl，MerchantServiceImpl 那份已改调本方法），
 * 故 {@code MerchantServiceImpl} 改调本方法不构成行为变更（现库 19 笔 orders.phone 与 5 笔
 * merchant.contact_phone 实测 CHAR_LENGTH 恒为 11）。
 *
 * <p>唯一差异在母版**抛异常**的那段定义域：长度不是 11 的值（null 除外，原样回 null；过短、过长、
 * 含分隔符的历史/迁移脏值）统一回 {@code "****"}。理由：orders.phone 是 VARCHAR(20) 而非 CHAR(11)，
 * 母版口径 {@code substring(0,3)+"****"+substring(7)} 对长度 20 的值只遮 4 位、会把尾部 9 位明文
 * 送进商家侧出参（G-4/L5，找茬轮判定：现网/API 造不出该值，历史与迁移数据能）；而「非 11 位原样
 * 返回」（UserController:126 的口径）会把短号明文发到商家侧，正面违反 RD。两个失败模式都排除后
 * 只剩「全掩码」这一种取值。<b>合法 11 位号码的输出与母版逐字不变</b>（前 3 + 4 星 + 后 4）。
 *
 * <p>本类只新增工具，不回收 OrderServiceImpl / UserController / AdminServiceImpl 三份私有实现
 * （RE 红线：C 端交易链路与 Admin 侧一字不动；登记为 F-17.7 #7 的既有债务）。
 */
public final class PhoneMask {

    /** 掩码串：4 个星号，与四份母版逐字一致 */
    private static final String MASK = "****";

    /** 合法手机号长度：仅 11 位走母版掩码口径，其余长度一律全掩码（G-4/L5） */
    private static final int VALID_PHONE_LENGTH = 11;

    private PhoneMask() {
    }

    /**
     * @param phone 明文手机号（可为 null）
     * @return 脱敏值：长度恰为 {@value #VALID_PHONE_LENGTH} 位按母版口径掩码（前 3 + {@value #MASK} + 后 4，
     *         与四份既有实现逐字相等）；其余长度（过短/过长/历史迁移脏值）一律回 {@value #MASK}，
     *         null 回 null。返回值**永不**是入参原文（除非入参本身已含星号，取值仍为 {@value #MASK}）。
     */
    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        if (phone.length() != VALID_PHONE_LENGTH) {
            return MASK;
        }
        return phone.substring(0, 3) + MASK + phone.substring(7);
    }
}
