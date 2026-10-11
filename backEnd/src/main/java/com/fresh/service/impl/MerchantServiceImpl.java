package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.MerchantProfileUpdateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Merchant;
import com.fresh.entity.MerchantProfile;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.MerchantProfileMapper;
import com.fresh.service.MerchantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商家端服务实现（F-12/G-01 Step 4d）。
 * R3 铁律落地方式（两条硬约束，契约 §2.11 主会话 Step 3 实测后新增）：
 * 1) 每个以 merchantId 为参的方法第一行判空回 403——拦截器已在归属为空时收口 403（有条件注入），故本类收到的
 *    merchantId 恒非 null；此判空属纵深防御（防将来出现不经 /api/merchant/** 前缀的内部调用路径），不是可回收的
 *    死代码——m15 列可空，merchant_id 为 NULL 若落到 WHERE merchant_id = null 会静默空集，
 *    一旦被写成「参数为空即不加条件」的动态 SQL 就是全表泄露；403 与拦截器同口径，
 *    不区分「你不是商家」与「你没归属」，避免新增泄露面。
 * 2) 读写一律带归属条件（merchant 表 WHERE id=merchantId，merchant_profile WHERE merchant_id=merchantId），
 *    查无 → 404；绝不「先按 id 查再内存比归属」（那会把存在性差异写进时序或文案）。
 */
@Slf4j
@Service
public class MerchantServiceImpl implements MerchantService {

    /**
     * 审计 detail 列宽（admin_audit_log.detail VARCHAR(500)，init.sql:319）：MySQL 严格模式
     * （STRICT_TRANS_TABLES）下超长 INSERT 报 1406，整行审计凭空消失（BE-2 / F-13.10 #2 实测）。
     */
    private static final int AUDIT_DETAIL_MAX = 500;

    /**
     * Jackson 序列化最坏膨胀倍数：一个控制码（U+0001）→ 六字符转义串（反斜杠 + u0001）共 6 字符。
     * F-13.10 #2 实测 100 个 U+0001 序列化后 602 字符，证伪了 MerchantDishServiceImpl 旧注释里的「最坏 2 倍」。
     */
    private static final int JSON_ESCAPE_FACTOR = 6;

    /** 单侧原值的宽松上限：只为挡住病态长文本白白进序列化，列宽保证在 buildAuditDetail 的「序列化后按剩余预算截」 */
    private static final int AUDIT_VALUE_MAX = 400;

    /** 兜底 detail：两侧都装不下时落这一行——「审计行必须存在」优先于「审计行有内容」（BE-2 的硬要求） */
    private static final String AUDIT_MINIMAL_DETAIL = "{\"old\":\"audit truncated\",\"new\":\"audit truncated\"}";

    /** 审计 detail 序列化用（Jackson ObjectMapper 线程安全，静态复用同 AdminServiceImpl:64 姿势） */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private MerchantProfileMapper merchantProfileMapper;
    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;

    @Override
    public Map<String, Object> getMe(Long merchantId) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        Merchant merchant = merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>()
                .eq(Merchant::getId, merchantId));
        if (merchant == null) {
            // 404 口径同既有业务侧「<主体>不存在」（订单/菜品/评价不存在），两处缺失同文案：
            // 不区分「主体没了」还是「资质没了」，防存在性差异成为可枚举信号
            throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
        }
        MerchantProfile profile = merchantProfileMapper.selectOne(new LambdaQueryWrapper<MerchantProfile>()
                .eq(MerchantProfile::getMerchantId, merchantId));
        if (profile == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
        }

        Map<String, Object> me = new LinkedHashMap<>();
        // 角色判定入口（契约 §3.2「me 返回 role=merchant」）：拦截器已裁决，回常量不额外查库
        me.put("role", "merchant");
        me.put("merchantId", merchant.getId());
        me.put("name", merchant.getName());
        // R4：手机号一律脱敏（出参绝不直出 contactPhone 明文）
        me.put("contactPhone", maskPhone(merchant.getContactPhone()));
        me.put("contactName", merchant.getContactName());
        me.put("status", merchant.getStatus());
        Map<String, Object> profileMap = new LinkedHashMap<>();
        profileMap.put("auditStatus", profile.getAuditStatus());
        profileMap.put("businessLicense", profile.getBusinessLicense());
        profileMap.put("legalPerson", profile.getLegalPerson());
        profileMap.put("address", profile.getAddress());
        me.put("profile", profileMap);
        return me;
    }

    @Override
    public void assertActive(Long merchantId) {
        requireActive(merchantId);
    }

    @Override
    @Transactional
    public void updateProfile(Long userId, Long merchantId, MerchantProfileUpdateDTO dto) {
        // BE-5（F-13.10 #5）：停业闸门从「只有 dish 四个写端点有」补到 profile 上——决策④ 的原文是
        // 商家端**所有**写端点。requireActive 首行的 merchantId 判空兼作本方法原有的纵深防御（口径不变）。
        // 用 requireActive 而非 assertActive 是为了白拿读到的主体行做审计旧值，少一次 SELECT
        Merchant before = requireActive(merchantId);
        if (dto.getContactName() == null && dto.getAddress() == null
                && dto.getBusinessLicense() == null && dto.getLegalPerson() == null) {
            // 全空请求：口径对齐 AdminServiceImpl.updateDish 的部分更新判定（400 无更新字段），
            // 而非静默假成功——调用方需知道自己什么都没改
            throw new BizException("无更新字段（可传 contactName / address / businessLicense / legalPerson）");
        }

        // 审计旧值（detail 记旧值→新值，R6'）：资质三列来自 merchant_profile，查不到记 null，
        // 绝不因为「读不到旧值」提前抛 404——本方法的 404 口径仍由下面两条 UPDATE 的影响行数裁定
        MerchantProfile beforeProfile = merchantProfileMapper.selectOne(new LambdaQueryWrapper<MerchantProfile>()
                .eq(MerchantProfile::getMerchantId, merchantId));

        // 写一：merchant.contact_name（联系人属主体列，merchant 表的归属条件即主键 = 服务端解析的 merchantId）
        if (dto.getContactName() != null) {
            int rows = merchantMapper.update(null, new LambdaUpdateWrapper<Merchant>()
                    .eq(Merchant::getId, merchantId)
                    .set(Merchant::getContactName, dto.getContactName()));
            if (rows == 0) {
                // 连接串未开 useAffectedRows → 返回 matched rows，同值更新也计 1，故 0 行只会是主体不存在
                throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
            }
        }

        // 写二：merchant_profile 三列（资质侧可编辑字段，WHERE merchant_id=? 强制归属）
        if (dto.getAddress() != null || dto.getBusinessLicense() != null || dto.getLegalPerson() != null) {
            LambdaUpdateWrapper<MerchantProfile> wrapper =
                    new LambdaUpdateWrapper<MerchantProfile>().eq(MerchantProfile::getMerchantId, merchantId);
            if (dto.getAddress() != null) {
                wrapper.set(MerchantProfile::getAddress, dto.getAddress());
            }
            if (dto.getBusinessLicense() != null) {
                wrapper.set(MerchantProfile::getBusinessLicense, dto.getBusinessLicense());
            }
            if (dto.getLegalPerson() != null) {
                wrapper.set(MerchantProfile::getLegalPerson, dto.getLegalPerson());
            }
            if (merchantProfileMapper.update(null, wrapper) == 0) {
                throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
            }
        }
        log.info("商家端更新资料: merchantId={}, contactName={}, address={}, businessLicense={}, legalPerson={}",
                merchantId, dto.getContactName() != null, dto.getAddress() != null,
                dto.getBusinessLicense() != null, dto.getLegalPerson() != null);

        // 只把本次提交的列写进审计（未传的列本次未动，记进去是噪音）；本方法在 @Transactional 内、
        // 影响行数判定之后，失败的请求走不到这里 → 审计与写操作同成同败
        writeAudit("merchant:" + userId, "merchant.profile", "merchant", String.valueOf(merchantId),
                profileBrief(before, beforeProfile, dto, true).toString(),
                profileBrief(before, beforeProfile, dto, false).toString());
    }

    @Override
    public void auditUpload(Long userId, Long merchantId, String savedFileName, long bytes) {
        // detail 只有文件名 + 字节数（BE-5）：文件内容/base64 绝不进库（R5 敏感清单同口径），
        // 原始文件名用户可控且无留痕价值，同样不记
        writeAudit("merchant:" + userId, "merchant.upload", "merchant", String.valueOf(merchantId),
                null, "file=" + savedFileName + ",bytes=" + bytes);
    }

    /**
     * 写闸门的实现体（BE-5）：assertActive 复用同一段逻辑，updateProfile 额外要返回读到的主体行做审计旧值。
     * 三条判定的文案全部沿用既有串（R2/R6'：不新增 403/404 文案）：
     * merchantId 为空 → 403 R.MSG_FORBIDDEN；主体查无 → 404「商家不存在」（同本类 getMe 的 404 口径，
     * 也同 dish 侧写闸门旧实现 assertWritable 的三条判定）；status != active → 403 R.MSG_FORBIDDEN。
     * 停业/清退的具体状态由 GET /api/merchant/me 的 status 字段供前端展示，不靠错误文案区分（免新增可枚举信号）。
     */
    private Merchant requireActive(Long merchantId) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
        }
        if (!"active".equals(merchant.getStatus())) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        return merchant;
    }

    /** 资料审计的一侧：old=true 取改前快照（merchant.contact_name + merchant_profile 三列），false 取本次提交值 */
    private StringBuilder profileBrief(Merchant merchant, MerchantProfile profile,
                                      MerchantProfileUpdateDTO dto, boolean oldSide) {
        StringBuilder sb = new StringBuilder();
        if (dto.getContactName() != null) {
            appendField(sb, "contactName", oldSide ? merchant.getContactName() : dto.getContactName());
        }
        if (dto.getAddress() != null) {
            appendField(sb, "address", oldSide ? (profile == null ? null : profile.getAddress()) : dto.getAddress());
        }
        if (dto.getLegalPerson() != null) {
            appendField(sb, "legalPerson",
                    oldSide ? (profile == null ? null : profile.getLegalPerson()) : dto.getLegalPerson());
        }
        if (dto.getBusinessLicense() != null) {
            appendField(sb, "businessLicense",
                    oldSide ? (profile == null ? null : profile.getBusinessLicense()) : dto.getBusinessLicense());
        }
        return sb;
    }

    /**
     * 审计落库（R6'，契约随建随写；本类是商家资料/上传两个写入口的母版）。
     * 审计是旁路——写失败绝不打断主事务；BE-2 的修正点是「失败也要再落一行」：
     * 第一趟用完整 detail，任一层原因（超长 1406、孤立代理 1366、列宽…）失败即退成
     * {@link #AUDIT_MINIMAL_DETAIL} 再写一次，两趟都失败才 log.warn。
     * 自带一份不复用 AdminServiceImpl / MerchantDishServiceImpl 的私有方法：那两个都是 private，
     * 跨类复用就得把它们降为公开，属越界改动（同 MerchantDishServiceImpl:322 的取舍口径）。
     */
    private void writeAudit(String actor, String action, String targetType, String targetId,
                            String oldVal, String newVal) {
        String detail = buildAuditDetail(oldVal, newVal);
        try {
            insertAudit(actor, action, targetType, targetId, detail);
            return;
        } catch (Exception e) {
            log.warn("审计写入失败（旁路，不影响主事务），改用最小 detail 重试: actor={}, action={}, targetType={}, targetId={}",
                    actor, action, targetType, targetId, e);
        }
        try {
            insertAudit(actor, action, targetType, targetId, AUDIT_MINIMAL_DETAIL);
        } catch (Exception e) {
            log.warn("审计重试仍失败（旁路，不影响主事务）: actor={}, action={}, targetType={}, targetId={}",
                    actor, action, targetType, targetId, e);
        }
    }

    private void insertAudit(String actor, String action, String targetType, String targetId, String detail) {
        AdminAuditLog row = new AdminAuditLog();
        row.setActor(actor);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setDetail(detail);
        row.setIp(currentIp());
        adminAuditLogMapper.insert(row);
    }

    /**
     * detail 组装（BE-2）：先序列化、再按剩余预算整体截断，保证列宽内且仍是合法 JSON
     * （JSON_VALID(detail)=1 是 F-13.5 DoD⑦ 的硬断言）。
     * 预算按 Jackson 最坏 6 倍膨胀推（JSON_ESCAPE_FACTOR），故「原值截到多少字符才安全」本身无解，
     * 真正的保证是这条「序列化后长度」判定：常规中文值（膨胀系数≈1）两侧都原样保留，
     * 一旦超限就把 old 侧塌成 "truncated"，把预算让给更能说明「本次改了什么」的 new 侧，
     * new 侧仍装不下就按 token 边界截（clipJsonLiteral），绝不丢行。
     */
    private String buildAuditDetail(String oldVal, String newVal) {
        String oldJson = jsonValue(dropUnpairedSurrogates(truncate(oldVal)));
        String newJson = jsonValue(dropUnpairedSurrogates(truncate(newVal)));
        String detail = "{\"old\":" + oldJson + ",\"new\":" + newJson + "}";
        if (detail.length() <= AUDIT_DETAIL_MAX) {
            return detail;
        }
        String head = "{\"old\":\"truncated\",\"new\":";
        String clippedNew = clipJsonLiteral(newJson, AUDIT_DETAIL_MAX - head.length() - 1);
        if (clippedNew != null) {
            return head + clippedNew + "}";
        }
        return AUDIT_MINIMAL_DETAIL;
    }

    /** 审计值拼串：key=value 逗号分隔（null 值原样写成 null，与 AdminServiceImpl 的 detail 显式留痕同口径） */
    private StringBuilder appendField(StringBuilder sb, String key, Object value) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        return sb.append(key).append('=').append(value);
    }

    /** 原值宽松上限（见 AUDIT_VALUE_MAX 注释）；截点避开代理对，按 codePoint 退到整字符边界 */
    private String truncate(String value) {
        if (value == null || value.length() <= AUDIT_VALUE_MAX) {
            return value;
        }
        int end = AUDIT_VALUE_MAX;
        while (end > 0 && Character.isSurrogate(value.charAt(end - 1))) {
            // 尾部高代理 → 退一格；孤立低代理同理（前面的高代理被截掉时它也是 surrogates），
            // 两种情形都交给 dropUnpairedSurrogates/clipJsonLiteral 收口，这里先保证不劈开成对代理
            if (Character.isHighSurrogate(value.charAt(end - 1))) {
                end--;
                continue;
            }
            break;
        }
        return value.substring(0, end);
    }

    /**
     * 把「已序列化的 JSON 值」截到 maxLen 字符内且仍是合法 JSON 字符串。
     * 按 token 边界前进扫描（\\uXXXX 6 字符、\\x 2 字符、代理对 2 字符），截点绝不落在转义序列
     * 或代理对中间——否则截出来的是不可解析 JSON，或 utf8mb4 存不下的孤立代理（BE-2 的另一个丢行成因）。
     * 不可截（预算小于一个空串字面量、或值本身是 null 字面量）时返回 null，交调用方走最小 detail 兜底。
     */
    private String clipJsonLiteral(String literal, int maxLen) {
        if (literal == null || literal.length() <= maxLen) {
            return literal;
        }
        if (literal.length() < 2 || literal.charAt(0) != '"' || maxLen < 2) {
            return null;
        }
        int bodyEnd = maxLen - 1;                 // 末位留给收尾双引号
        int i = 1;
        int safeEnd = 1;
        while (i < bodyEnd) {
            char c = literal.charAt(i);
            int step = 1;
            if (c == '\\') {
                step = (i + 1 < literal.length() && literal.charAt(i + 1) == 'u') ? 6 : 2;
            } else if (Character.isHighSurrogate(c)) {
                step = 2;
            }
            if (i + step > bodyEnd) {
                break;
            }
            i += step;
            safeEnd = i;
        }
        return literal.substring(0, safeEnd) + "\"";
    }

    /**
     * 孤立代理项换成 U+FFFD：merchant 可控的 name/description 里塞一个 \\uD800 而不成对时，
     * Jackson 走 Writer 序列化是原样透传（不做 UTF-8 编码，故不报错），但 utf8mb4 列存不下它 →
     * MySQL 1366 → 整行审计消失（与超长 1406 同一类「投毒一次即永久脱审」）。
     */
    private String dropUnpairedSurrogates(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                if (sb != null) {
                    sb.append(c).append(value.charAt(i + 1));
                }
                i++;                            // 合法代理对：两个一起走
                continue;
            }
            if (!Character.isSurrogate(c)) {
                if (sb != null) {
                    sb.append(c);
                }
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(value.length());
                sb.append(value, 0, i);
            }
            sb.append('\uFFFD');
        }
        return sb == null ? value : sb.toString();
    }

    /**
     * detail 值序列化：null → null，其余交 Jackson writeValueAsString 全量转义
     * （F-12 S-4 结论沿用：手写 replace 只挡反斜杠/双引号，挡不住 \n、\r 与控制码，写出的会是不可解析 JSON）。
     */
    private String jsonValue(String value) {
        if (value == null) {
            return "null";
        }
        try {
            return AUDIT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /** 操作来源 IP：Service 层拿不到 request，走 RequestContextHolder 取 remoteAddr；非请求线程返回 null（列可空） */
    private String currentIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    /** 138****1234（逐字复刻 OrderServiceImpl#maskPhone:347-349；merchant.contact_phone 由代开通
     *  DTO @Pattern("^1\\d{10}$") 与 m15 种子保证 11 位，substring 安全，同 FreeServiceImpl:449 口径注释） */
    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
