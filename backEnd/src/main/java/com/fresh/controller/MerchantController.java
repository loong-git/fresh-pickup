package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.MerchantProfileUpdateDTO;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.MerchantService;
import com.fresh.service.MerchantSettlementService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 商家端接口（F-12/G-01 Step 4，契约 §2.8/§2.11）。
 * 鉴权：/api/merchant/** 前缀整体要求登录，随后拦截器查库定角色（role != merchant → 403），
 * 通过则注入 ATTR_MERCHANT_ID——角色校验绝不在本类重复（R2/契约 §2.8 裁决）。
 * 归属：一律 @RequestAttribute 服务端解析，不收任何归属入参（R2/R3）。
 * 出参脱敏：手机号经 MerchantService 侧 maskPhone 输出（R4）。
 */
@RestController
@RequestMapping("/api/merchant")
public class MerchantController {

    /** R5 后缀白名单（原始文件名后缀转小写比对） */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");

    /** R5 大小上限 5MB */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    /** 魔数比对所需文件头长度（WEBP 需到第 12 字节） */
    private static final int HEADER_LEN = 12;

    /**
     * 上传落盘目录：dev 取 application.yml 的 fresh.upload-dir（值 ../front/static/images/uploads/，
     * 相对后端进程工作目录 backEnd/ 解析，即回退一级进 front/），prod 由环境变量 FRESH_UPLOAD_DIR 覆盖；
     * 下方 ./uploads/ 只是该 key 完全缺失时的兜底默认值，dev/prod 都不会命中（CorsConfig 同源注册静态映射）。
     */
    @Value("${fresh.upload-dir:./uploads/}")
    private String uploadDir;

    @Autowired
    private MerchantService merchantService;

    /** 结算单只读服务（F-120）：归属过滤与 404 校验都在 Service，本类不重复（照 dishes 同一分层） */
    @Autowired
    private MerchantSettlementService merchantSettlementService;

    /**
     * 单图上传（§2.8，M-03）：校验链 后缀白名单 → 大小 ≤5MB → 魔数 → 随机文件名 → 落盘，
     * 出参两键（F-13/G-02 决策①方案 A，同一物理文件的两种寻址语义）：
     * - path = /static/images/uploads/&lt;随机名&gt;：**入库一律用它**——与 C 端现状 dish.image
     *   （/static/images/dish-N.svg）完全同构，C 端 :src="dish.image" 不拼 BASE_URL，dev 由前端静态目录
     *   命中、prod 由 nginx-fresh.conf 的 location /static/images/ alias 命中；若改存 /uploads/ 前缀，
     *   dev 无此路径、prod 掉进 try_files 回 index.html 拿 200 的 HTML，症状是「上传成功、库里有值、
     *   C 端永远显示 emoji 兜底」的静默假成功，事后无法从状态码察觉；
     * - url = /uploads/&lt;随机名&gt;：后端 ResourceHandler 映射（CorsConfig 对同一目录注册的第二条），
     *   商家端 dev 预览用（商家端跑 5174，其静态目录下没有这些文件，只能回源后端）。
     * 绝不把文件内容/ base64 写进库（R5），也绝不使用用户可控的文件名（路径穿越防线：
     * 落盘名 = UUID + 白名单后缀，原始文件名只参与后缀比对不参与命名）。
     * IO 异常不在此捕获，交 GlobalExceptionHandler 统一转 500 信封（不新增文案）。
     * <p>BE-1（F-13.10 #1，critical）第二道闸：本方法原先**不读任何 request attribute**，是六个商家端点里
     * 唯一「attribute 缺失不会 500」的一个——所以 AuthInterceptor 用未解码 URI 判前缀时，
     * `POST /api/%6derchant/uploads` 无 token 也能真落盘（主会话实测）。参数表补 @RequestAttribute(ATTR_MERCHANT_ID)
     * 后，属性缺失会在 handler 参数解析阶段抛 ServletRequestBindingException → 500 fail-closed，
     * 鉴权判定（已换成映射路径）与归属注入两处只要有一处失守都落不成静默成功。ATTR_USER_ID 同批补上，
     * 供 BE-5 的审计 actor='merchant:&lt;userId&gt;' 使用（拦截器在 /api/merchant/ 分支之前就已注入它）。
     * <p>BE-5（F-13.10 #5）：决策④ 的原文是商家端**所有**写端点非 active 时 403，本方法此前既不查 status
     * 也不写审计；现在闸门（首行，失败即 403 且不落盘）与审计（落盘成功后，只记文件名 + 字节数）各补一行。
     */
    @RateLimit(limit = 10, windowSeconds = 60)   // USER 维度（注解默认；商家亦有 userId，§2.8 限流复用零改造）
    @PostMapping("/uploads")
    public R<Map<String, String>> upload(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                         @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        merchantService.assertActive(merchantId);   // BE-5 停业闸门：文案沿用 R.MSG_FORBIDDEN，不新增 403 串

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
        Files.createDirectories(dir);   // 兜底目录不存在（首次上传时目标目录尚未创建）
        file.transferTo(dir.toAbsolutePath().resolve(fileName).toFile());
        // 同一份文件的两种寻址：path 供入库（C 端静态根语义）、url 供商家端预览（后端映射）；
        // 两键皆非 null，Map.of 可用（F-12 R5 门禁过的校验链一字未动）
        merchantService.auditUpload(userId, merchantId, fileName, file.getSize());   // BE-5 审计（旁路，不影响上传）
        return R.ok(Map.of("path", "/static/images/uploads/" + fileName, "url", "/uploads/" + fileName), "上传成功");
    }

    /**
     * 商家资料 + 资质状态（§2.11）：非商家 403 由拦截器裁决，本类不重复校验。
     * BE-7（F-13.10 #12）：G-02 起 me 被 dishes/dish-edit 两页的 syncMerchantSnapshot 消费，
     * 是商家端唯一没挂限流的端点；补齐读档口径（F-13.2 表行 1/2 的 USER 30/分）。
     */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/me")
    public R<Map<String, Object>> me(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                     @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId) {
        return R.ok(merchantService.getMe(merchantId));
    }

    // ------------------------------------------------------------------
    // 结算单只读两端点（F-120 §1）：商家对结算单零写入口（settlement 只增不改，F-118 §3 红线照守），
    // 归属一律 merchantId 等值过滤/校验、404 同文案不泄露他人账单存在性，口径照运营端（见实现类）。
    // 限流照商家端既有 GET 读档 30 次/分（USER 维度注解默认，同 /me）。
    // ------------------------------------------------------------------

    /** 本人账单列表（F-120 §1）：商家自见子集 + reconcileOk/paidChannelAbsent，id 升序 */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/settlements")
    public R<List<Map<String, Object>>> settlements(
            @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId) {
        return R.ok(merchantSettlementService.list(merchantId));
    }

    /** 本人某账单的挂锚明细（F-120 §1）：不存在/他商家同 404「账单不存在」，orphan 语义照运营端口径 */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/settlements/{id}/items")
    public R<List<Map<String, Object>>> settlementItems(
            @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
            @PathVariable Long id) {
        return R.ok(merchantSettlementService.items(merchantId, id));
    }

    /** 更新可编辑字段（§2.11）：主体名/结算账户一期锁定平台维护，不在白名单 */
    @PutMapping("/profile")
    @RateLimit(limit = 10, windowSeconds = 60)
    public R<Void> updateProfile(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                 @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                                 @Valid @RequestBody MerchantProfileUpdateDTO dto) {
        // 闸门与审计都在 Service 内（BE-5：merchant.profile 随建随写 admin_audit_log）
        merchantService.updateProfile(userId, merchantId, dto);   // WHERE merchant_id=? 强制归属（R3 铁律）
        return R.ok(null, "资料已更新");
    }

    /**
     * 文件头魔数比对（§2.8 口径）：JPEG FF D8 FF；PNG 89 50 4E 47 0D 0A 1A 0A；
     * WEBP 前 4 字节 52 49 46 46（RIFF）且第 9-12 字节 57 45 42 50（WEBP）。
     * 头长不足（read < 所需偏移）一律判不合法，不做越界读取。
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
