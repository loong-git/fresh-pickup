package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.MerchantApplyCreateDTO;
import com.fresh.entity.MerchantApply;
import com.fresh.entity.User;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.mapper.MerchantApplyMapper;
import com.fresh.mapper.UserMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 商家自助入驻申请接口（F-106 片 A，申请侧）。
 *
 * <p><b>前缀选择</b>：挂 {@code /api/merchant-applies}，不挂 {@code /api/merchant/}——
 * 申请时名下没有商家，{@code /api/merchant/} 的 role=merchant 闸门会把入口自己锁死。
 * AuthInterceptor#requiresLogin 为本前缀新增「只要求登录」分支（不要求角色/归属），
 * ATTR_USER_ID 由拦截器按 token 注入，Controller 不接收任何客户端归属入参。
 *
 * <p><b>代码组织按契约 §0.2③</b>：不新建 Service 接口实现类。提交/我的两件薄业务直接落在本控制器
 * （单表 INSERT 与白名单 SELECT，mapper 注入同 AuthInterceptor/AdminController 的既有先例）；
 * 审核业务在 {@code AdminServiceImpl#auditMerchantApply}，不在这里。
 */
@RestController
@RequestMapping("/api/merchant-applies")
public class MerchantApplyController {

    private static final String STATUS_PENDING = "pending";

    /** 上传白名单与 {@code MerchantController#upload} 逐字一致（R5） */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");

    /** 大小上限 5MB，同 MerchantController#upload */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    /** 魔数比对所需文件头长度（WEBP 需到第 12 字节） */
    private static final int HEADER_LEN = 12;

    /**
     * 复用既有 yml 键 {@code fresh.upload-dir}（不新增配置键）：dev 相对 backEnd/ 解析到
     * front/static/images/uploads/，prod 由 FRESH_UPLOAD_DIR 覆盖，与 MerchantController#upload 同一落盘目录。
     */
    @Value("${fresh.upload-dir:./uploads/}")
    private String uploadDir;

    @Autowired
    private MerchantApplyMapper merchantApplyMapper;
    @Autowired
    private UserMapper userMapper;

    /**
     * 提交入驻申请（{@code POST /api/merchant-applies}）：body 见 MerchantApplyCreateDTO。
     * 出参 {@code {id, status:"pending"}}；过审前不产生 merchant 行。
     * 同用户最多 1 条 pending：应用层 count 给友好 400，并发窗口由 uk_user_pending 兜底（撞键同文案）。
     */
    @RateLimit(limit = 10, windowSeconds = 60)   // 写 10/分（USER 维度）
    @PostMapping
    public R<Map<String, Object>> create(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                         @Valid @RequestBody MerchantApplyCreateDTO dto) {
        if (userId == null) {
            // 拦截器 requiresLogin 已保证有 token；本行是「内部直调」的纵深防御
            throw new BizException(R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
        }

        // ① 申请人必须存在，且名下没有商家（契约：user.merchant_id != null → 400）
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
        }
        if (user.getMerchantId() != null) {
            throw new BizException(R.CODE_BAD_REQUEST, "您已是商家，无需重复申请");
        }

        // ② 应用层闸门：已有 pending 申请 → 400 友好话术；并发窗口由 uk_user_pending 兜底
        if (merchantApplyMapper.countPendingByUser(userId) > 0) {
            throw new BizException(R.CODE_BAD_REQUEST, "已有待审核的入驻申请，请等待审核结果");
        }

        MerchantApply apply = new MerchantApply();
        apply.setUserId(userId);
        apply.setMerchantName(dto.getMerchantName().trim());
        apply.setContactName(trimToNull(dto.getContactName()));
        apply.setBusinessLicense(trimToNull(dto.getBusinessLicense()));
        apply.setLegalPerson(trimToNull(dto.getLegalPerson()));
        apply.setAddress(trimToNull(dto.getAddress()));
        apply.setSettlementAccount(trimToNull(dto.getSettlementAccount()));
        apply.setStatus(STATUS_PENDING);   // 显式写，不靠 DEFAULT：出参 status 与落库值同源
        // pending_guard 是生成列，实体不声明、INSERT 不含它，由 DB 按 status 自动落值
        try {
            merchantApplyMapper.insert(apply);
        } catch (DuplicateKeyException e) {
            // uk_user_pending（1062）→ 与应用层判定同文案、同 code，不裸 500
            throw new BizException(R.CODE_BAD_REQUEST, "已有待审核的入驻申请，请等待审核结果");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", apply.getId());
        data.put("status", STATUS_PENDING);
        return R.ok(data, "申请已提交，请等待审核结果");
    }

    /**
     * 我的最新一条申请（{@code GET /api/merchant-applies/mine}）：六键白名单；
     * **从未申请过时 {@code data} 为 {@code null}**（「还没申请过」是正常状态，不返 404，
     * 也不返一个全 null 的空壳对象——空壳会让调用方分不清"没有申请"和"申请字段恰好都空"）。
     */
    @RateLimit(limit = 30, windowSeconds = 60)   // 读 30/分（USER 维度）
    @GetMapping("/mine")
    public R<Map<String, Object>> mine(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        if (userId == null) {
            throw new BizException(R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
        }
        // 取数即白名单：mapper 的 SELECT 列表只有六列，「不出」是查库层定的
        Map<String, Object> row = merchantApplyMapper.selectLatestByUser(userId);
        if (row == null) {
            return R.ok(null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", row.get("id"));
        data.put("merchantName", row.get("merchantName"));
        data.put("status", row.get("status"));
        data.put("auditRemark", row.get("auditRemark"));
        data.put("createdAt", row.get("createdAt"));
        data.put("auditedAt", row.get("auditedAt"));
        return R.ok(data);
    }

    /**
     * 营业执照图上传（{@code POST /api/merchant-applies/uploads}）：校验链照
     * {@code MerchantController#upload} 的顺序复制（扩展名白名单 → 大小 → 魔数 → UUID 重命名 → 落盘），
     * 出参两键 {@code {path, url}}。与商家端上传的差异只有两点：
     * ① 不要求 ATTR_MERCHANT_ID（申请时还没有商家）；
     * ② 不写商家审计（没有商家主体可挂；审核动作的留痕在 Admin 侧）。
     * 绝不用用户可控文件名（路径穿越防线：落盘名 = UUID + 白名单后缀）。
     */
    @RateLimit(limit = 10, windowSeconds = 60)   // 写 10/分（USER 维度）
    @PostMapping("/uploads")
    public R<Map<String, String>> upload(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        String original = file.getOriginalFilename();
        String ext = original == null ? "" : original.toLowerCase()
                .substring(original.lastIndexOf('.') + 1);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(R.CODE_BAD_REQUEST, "文件类型不合法");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(R.CODE_BAD_REQUEST, "图片过大，请重新选择图片");
        }
        byte[] header = new byte[HEADER_LEN];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(header, 0, HEADER_LEN);
        }
        if (!magicMatched(ext, header, read)) {
            throw new BizException(R.CODE_BAD_REQUEST, "文件类型不合法");
        }

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = Paths.get(uploadDir);
        Files.createDirectories(dir);
        file.transferTo(dir.toAbsolutePath().resolve(fileName).toFile());
        return R.ok(Map.of("path", "/static/images/uploads/" + fileName,
                "url", "/uploads/" + fileName), "上传成功");
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
     * 文件头魔数比对（与 MerchantController#upload 同一口径）：JPEG FF D8 FF；PNG 89 50 4E 47 0D 0A 1A 0A；
     * WEBP 前 4 字节 RIFF 且第 9-12 字节 WEBP。头长不足一律不合法，不做越界读取。
     */
    private boolean magicMatched(String ext, byte[] h, int read) {
        if (read < 3) {
            return false;
        }
        switch (ext) {
            case "jpg":
            case "jpeg":
                return h[0] == (byte) 0xFF && h[1] == (byte) 0xD8 && h[2] == (byte) 0xFF;
            case "png":
                return read >= 8
                        && h[0] == (byte) 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47
                        && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A;
            case "webp":
                return read >= HEADER_LEN
                        && h[0] == 0x52 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x46
                        && h[8] == 0x57 && h[9] == 0x45 && h[10] == 0x42 && h[11] == 0x50;
            default:
                return false;
        }
    }
}
