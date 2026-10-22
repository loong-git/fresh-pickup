package com.fresh.controller;

import com.fresh.common.BizException;
import com.fresh.common.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-106 片 A 营业执照上传端点单测：不起 Spring 容器、只落临时目录。
 *
 * <p>证明 {@code MerchantApplyController#upload} 复制自 {@code MerchantController#upload} 的校验链
 * 仍然按「扩展名白名单 → 大小 → 魔数」顺序生效，且出参两键 path/url 与既有上传端点同形。
 * 不证明：真实 multipart 解析、Spring 的 max-file-size 前置拦截（由真链路验收覆盖）。
 */
class MerchantApplyControllerUploadTest {

    @TempDir
    Path tempDir;

    private MerchantApplyController newController() {
        MerchantApplyController controller = new MerchantApplyController();
        ReflectionTestUtils.setField(controller, "uploadDir", tempDir.toString());
        return controller;
    }

    private static byte[] pngBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
    }

    @Test
    void 合法png落盘并返回path与url两键() throws Exception {
        MerchantApplyController controller = newController();
        MockMultipartFile file = new MockMultipartFile(
                "file", "license.png", "image/png", pngBytes());

        R<Map<String, String>> result = controller.upload(7L, file);

        assertEquals(200, result.getCode());
        Map<String, String> data = result.getData();
        assertEquals(2, data.size());
        String path = data.get("path");
        String url = data.get("url");
        assertTrue(path.startsWith("/static/images/uploads/"));
        assertTrue(url.startsWith("/uploads/"));
        assertEquals(path.substring("/static/images/uploads/".length()),
                url.substring("/uploads/".length()), "两键必须指向同一物理文件");
        assertTrue(Files.exists(tempDir.resolve(url.substring("/uploads/".length()))));
    }

    @Test
    void 扩展名白名单外判四百() throws Exception {
        MerchantApplyController controller = newController();
        MockMultipartFile file = new MockMultipartFile(
                "file", "license.gif", "image/gif", pngBytes());

        BizException e = assertThrows(BizException.class, () -> controller.upload(7L, file));

        assertEquals(400, e.getCode());
        assertEquals("文件类型不合法", e.getMessage());
    }

    @Test
    void 后缀对但魔数不符判四百() throws Exception {
        MerchantApplyController controller = newController();
        MockMultipartFile file = new MockMultipartFile(
                "file", "license.png", "image/png", "not-an-image".getBytes());

        BizException e = assertThrows(BizException.class, () -> controller.upload(7L, file));

        assertEquals(400, e.getCode());
        assertEquals("文件类型不合法", e.getMessage());
    }

    @Test
    void 超过5MB判四百且不落盘() throws Exception {
        MerchantApplyController controller = newController();
        byte[] oversize = new byte[5 * 1024 * 1024 + 1];
        byte[] header = pngBytes();
        System.arraycopy(header, 0, oversize, 0, header.length);
        MockMultipartFile file = new MockMultipartFile(
                "file", "license.png", "image/png", oversize);

        BizException e = assertThrows(BizException.class, () -> controller.upload(7L, file));

        assertEquals(400, e.getCode());
        assertEquals("图片过大，请重新选择图片", e.getMessage());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count(), "校验失败不得留下半截文件");
        }
    }
}
