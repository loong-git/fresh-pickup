package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.StoreApplyCreateDTO;
import com.fresh.entity.StoreApply;
import com.fresh.mapper.StoreApplyMapper;
import com.fresh.service.OpsLogWriter;
import com.fresh.service.StoreApplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 入驻申请实现（F-18.12 第 1~2 条）。
 *
 * <p><b>发布纪律（D-12/A2.1）</b>：本类<b>只有 INSERT store_apply 这一种写</b>，
 * 过审前绝不允许出现 store 行——{@code GET /api/stores} 的唯一发布通道是 Admin 审核
 * （AdminServiceImpl#auditStoreApply），所以 pending/rejected 期间门店行数零变化（门禁 G3 断言）。
 *
 * <p><b>校验分层</b>：长度/范围/前缀这类「单字段形状」全部交给 {@link StoreApplyCreateDTO} 的注解
 * （违规由 GlobalExceptionHandler 逐字段出人话文案，正是 G2 要的「指到具体字段」）；
 * 本类只做两件注解表达不了的事：① contactPhone 的「填了才校验」语义（null 与空串都是未填，
 * {@code @Pattern} 会把空串判死，故形态判定留在这儿）；② D-21 的跨行状态判定（要查库）。
 */
@Slf4j
@Service
public class StoreApplyServiceImpl implements StoreApplyService {

    /**
     * 大陆手机号形态（与申请侧/门店维护侧同一条规则；PartnerStoreServiceImpl 里那份同名常量为另一处入参契约）。
     * 注意与 {@code AdminMerchantCreateDTO} 的 {@code ^1\d{10}$} 不同：那条是代开通商家（必填、放宽到 1 开头 11 位），
     * 本条是申请人自填的联系号，按 F-18.12 第 1 条钉成 {@code ^1[3-9]\d{9}$}。
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /** 在审状态（D-21 的判定值） */
    private static final String STATUS_PENDING = "pending";

    @Autowired
    private StoreApplyMapper storeApplyMapper;
    @Autowired
    private OpsLogWriter opsLogWriter;

    @Override
    @Transactional
    public Map<String, Object> create(Long userId, StoreApplyCreateDTO dto) {
        if (userId == null) {
            // 拦截器 requiresLogin 已保证有 token，本行是「万一有内部直调」的纵深防御：
            // 绝不能让 userId 为空退化成「插一条无主申请」
            throw new BizException(R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
        }

        // ① contactPhone：未填（null/空串）归一成 NULL 落列；填了才判形态（文案点着字段名，G2 要的就是这个）
        String contactPhone = trimToNull(dto.getContactPhone());
        if (contactPhone != null && !PHONE_PATTERN.matcher(contactPhone).matches()) {
            throw new BizException(R.CODE_BAD_REQUEST, "联系电话需为 11 位大陆手机号");
        }

        // ② D-21：一用户一期最多 1 条在审申请（应用层判定，无唯一键，见 m18 ⑦ 注释）
        if (storeApplyMapper.countPendingByUser(userId) > 0) {
            throw new BizException(R.CODE_BAD_REQUEST, "已有申请在审核中，请等待结果");
        }

        // ③ INSERT store_apply：status 不写→DB DEFAULT 'pending'（列宽 DEFAULT 见 m18 ⑦），
        //    created_at 由 DB CURRENT_TIMESTAMP 落值，实体一律不填（照 OpsLog/MerchantProfile 姿势）
        StoreApply apply = new StoreApply();
        apply.setUserId(userId);
        apply.setProposedName(dto.getProposedName().trim());
        apply.setProposedAddress(dto.getProposedAddress().trim());
        apply.setLng(dto.getLng());
        apply.setLat(dto.getLat());
        apply.setService(trimToNull(dto.getService()));
        apply.setContactName(trimToNull(dto.getContactName()));
        apply.setContactPhone(contactPhone);
        apply.setProofPhotos(normalizePhotos(dto.getProofPhotos()));
        apply.setStatus(STATUS_PENDING);   // 显式写，不靠 DEFAULT：出参 status:'pending' 要与落库值同源
        storeApplyMapper.insert(apply);

        // ④ 审计落点（F-18.4：写端点必留痕）。两表边界 D-5 把「申请提交」列在 ops_log 的现场动作一侧
        //    （operator_user_id 就是申请人本人，不是平台动作，平台动作是下一步的审核），
        //    detail 只放状态与凭证张数，绝不放手机号/照片路径/地址（RD）。
        List<String> photos = apply.getProofPhotos();
        opsLogWriter.write(userId, "submit_store_apply", String.valueOf(apply.getId()),
                "{\"status\":\"" + STATUS_PENDING + "\",\"proofCount\":" + (photos == null ? 0 : photos.size()) + "}");
        log.info("入驻申请已提交: applyId={}, applicantUserId={}, proofCount={}",
                apply.getId(), userId, photos == null ? 0 : photos.size());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", apply.getId());
        data.put("status", STATUS_PENDING);
        return data;
    }

    @Override
    public Map<String, Object> mine(Long userId) {
        if (userId == null) {
            throw new BizException(R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
        }
        // 取数即白名单：StoreApplyMapper#selectLatestByUser 连 user_id/contact_phone/proof_photos 都没 SELECT，
        // 「不出」是查库层定的，不是拼装层忘了删（同 PartnerOrderMapper 的 RD 姿势）
        Map<String, Object> row = storeApplyMapper.selectLatestByUser(userId);
        long historyCount = storeApplyMapper.countByUser(userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", row == null ? null : row.get("id"));
        data.put("proposedName", row == null ? null : row.get("proposedName"));
        data.put("proposedAddress", row == null ? null : row.get("proposedAddress"));
        data.put("status", row == null ? null : row.get("status"));
        data.put("auditRemark", row == null ? null : row.get("auditRemark"));
        data.put("createdAt", row == null ? null : row.get("createdAt"));
        data.put("auditedAt", row == null ? null : row.get("auditedAt"));
        data.put("historyCount", historyCount);
        return data;
    }

    /** null 与空串都归一成 null（=该列未填）；纯空白同样算未填 */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 凭证照片归一：null → null（列留空）、空数组 → 空数组（落库 {@code []}，语义「本次没上传凭证」）、
     * 非空 → 逐项 trim 后交给 JacksonTypeHandler 序列化成 JSON 数组串。
     * <p>前缀白名单与 ≤6 张已在 DTO 的元素约束上拦掉（StoreApplyCreateDTO#proofPhotos 注释写了为什么放注解），
     * 这里只处理 null 元素之外的空白噪声；元素全为空白串时退化成未填，不给库里留 {@code [""]} 这种既非空也非路径的值。
     */
    private List<String> normalizePhotos(List<String> photos) {
        if (photos == null || photos.isEmpty()) {
            return photos;
        }
        List<String> cleaned = new ArrayList<>(photos.size());
        for (String photo : photos) {
            String trimmed = photo == null ? null : photo.trim();
            if (trimmed != null && !trimmed.isEmpty()) {
                cleaned.add(trimmed);
            }
        }
        return cleaned.isEmpty() ? null : cleaned;
    }
}
