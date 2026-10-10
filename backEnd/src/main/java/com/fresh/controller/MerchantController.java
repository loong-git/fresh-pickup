package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.MerchantProfileUpdateDTO;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** 上传落盘目录：dev 默认 ./uploads/，prod 由环境变量 FRESH_UPLOAD_DIR 覆盖（CorsConfig 同源注册静态映射） */
    @Value("${fresh.upload-dir:./uploads/}")
    private String uploadDir;

    @Autowired
    private MerchantService merchantService;

    /**
     * 单图上传（§2.8，M-03）：校验链 后缀白名单 → 大小 ≤5MB → 魔数 → 随机文件名 → 落盘，
     * 返回相对路径 /uploads/<随机名>（前端拼 baseURL）。
     * 绝不把文件内容/ base64 写进库（R5），也绝不使用用户可控的文件名（路径穿越防线：
     * 落盘名 = UUID + 白名单后缀，原始文件名只参与后缀比对不参与命名）。
     * IO 异常不在此捕获，交 GlobalExceptionHandler 统一转 500 信封（不新增文案）。
     */
    @RateLimit(limit = 10, windowSeconds = 60)   // USER 维度（注解默认；商家亦有 userId，§2.8 限流复用零改造）
    @PostMapping("/uploads")
    public R<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
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
        Files.createDirectories(dir);   // 兜底目录不存在（首次上传时 ./uploads/ 尚未创建）
        file.transferTo(dir.toAbsolutePath().resolve(fileName).toFile());
        return R.ok(Map.of("url", "/uploads/" + fileName), "上传成功");
    }

    /** 商家资料 + 资质状态（§2.11）：非商家 403 由拦截器裁决，本类不重复校验 */
    @GetMapping("/me")
    public R<Map<String, Object>> me(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                     @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId) {
        return R.ok(merchantService.getMe(merchantId));
    }

    /** 更新可编辑字段（§2.11）：主体名/结算账户一期锁定平台维护，不在白名单 */
    @PutMapping("/profile")
    @RateLimit(limit = 10, windowSeconds = 60)
    public R<Void> updateProfile(@RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                                 @Valid @RequestBody MerchantProfileUpdateDTO dto) {
        merchantService.updateProfile(merchantId, dto);   // WHERE merchant_id=? 强制归属（R3 铁律）
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
