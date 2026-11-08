package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.common.WordFilter;
import com.fresh.entity.Dish;
import com.fresh.entity.Review;
import com.fresh.entity.ReviewImage;
import com.fresh.entity.User;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.ReviewImageMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.service.ReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewServiceImpl implements ReviewService {

    /** 评价图后缀白名单（P10-6b D1/m34：原始文件名后缀转小写比对，魔数为最终裁决——照 MerchantController#upload 同款常量） */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");

    /** 单张上限 5MB（既有 multipart 闸门 application.yml max-file-size=5MB，服务端再校验照既有姿势） */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    /** 魔数比对所需文件头长度（WEBP 需到第 12 字节，照 MerchantController#upload） */
    private static final int HEADER_LEN = 12;

    /** 每评价图数上限（P10-6b D1：≤3 张，服务端计数拒绝） */
    private static final int MAX_IMAGES_PER_REVIEW = 3;

    /** 落盘子目录：{fresh.upload-dir}/reviews/{yyyyMM}/{uuid}.{ext}（契约 D1 存储） */
    private static final String IMAGE_SUB_DIR = "reviews";

    private static final DateTimeFormatter MONTH_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private WordFilter wordFilter;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private ReviewImageMapper reviewImageMapper;

    /**
     * 落盘根目录：复用既有 yml 键 fresh.upload-dir（P10-6b 契约「复用商家上传基建」，不新增配置键）；
     * dev 取 application.yml 的 ../front/static/images/uploads/（相对后端进程工作目录解析），
     * 评价图落 reviews/ 子目录——/uploads/** 静态映射（CorsConfig#addResourceHandlers）指向该根目录，
     * 子目录天然覆盖，零新增映射（契约「复用商家上传基建」第 3 条）。
     */
    @Value("${fresh.upload-dir:./uploads/}")
    private String uploadDir;

    @Override
    public List<Review> getReviewsByDishId(Long dishId) {
        LambdaQueryWrapper<Review> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Review::getDishId, dishId);
        // T-M4-05：命中软词待审（audit_status=pending）的评价默认不展示；
        // audit_status 为 NULL（正常）或其他值照常展示——IS NULL 必须显式判断，NULL <> 'pending' 不成立
        wrapper.and(w -> w.isNull(Review::getAuditStatus)
                .or().ne(Review::getAuditStatus, Review.AUDIT_PENDING));
        wrapper.orderByDesc(Review::getCreateTime);
        List<Review> reviews = reviewMapper.selectList(wrapper);
        // P10-6b D3：列表出参每行带 images（纯加键，既有键与过滤语义零改动）
        fillImages(reviews);
        return reviews;
    }

    @Override
    public List<Map<String, Object>> listByUser(Long userId) {
        LambdaQueryWrapper<Review> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Review::getUserId, userId);
        // 我的评价不过滤 audit_status：本人可见自己待审（pending）评价；驳回为物理删除（AdminServiceImpl#auditReview），自然不出现
        wrapper.orderByDesc(Review::getCreateTime);
        List<Review> reviews = reviewMapper.selectList(wrapper);
        if (reviews.isEmpty()) {
            return Collections.emptyList();
        }
        // dish 名批量填充：去重 dishId 一次 selectBatchIds，两查询收敛，不做逐条循环查库
        List<Long> dishIds = reviews.stream().map(Review::getDishId).distinct().collect(Collectors.toList());
        Map<Long, String> dishNames = dishMapper.selectBatchIds(dishIds).stream()
                .collect(Collectors.toMap(Dish::getId, Dish::getName));
        // P10-6b D3：图路径按 reviewId 批量填充（与 dishName 同一「两查询收敛」纪律，杜绝逐行查库）
        fillImages(reviews);
        // 字段按现有 Review 实体风格直出；时间键契约要求 createdAt（对齐 getMyCoupons 的 UserCoupon.createdAt 口径），
        // 与实体风格键 createTime 同值双写，兼容两端字段读取；dishId 无对应 dish（历史脏数据）时 dishName 为 null
        return reviews.stream().map(review -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", review.getId());
            item.put("dishId", review.getDishId());
            item.put("dishName", dishNames.get(review.getDishId()));
            item.put("userId", review.getUserId());
            item.put("rating", review.getRating());
            item.put("content", review.getContent());
            item.put("reviewer", review.getReviewer());
            item.put("auditStatus", review.getAuditStatus());
            item.put("createTime", review.getCreateTime());
            item.put("createdAt", review.getCreateTime());
            item.put("images", review.getImages() == null ? List.of() : review.getImages());
            return item;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional // 评价落库与好评率回写同事务：任一失败整体回滚，good_rate 不滞留旧值
    public Review addReview(Review review, Long userId) {
        // F-124 幂等快查：入参带 clientRequestId 时先按其查同键评价，命中即返回首次评价行——
        // 不再 insert、不再回写好评率（同键重复提交零写库）；快查形状照 OrderServiceImpl#selectByClientRequestId
        // 先例（借鉴不改它）。键可空不强制：null/纯空白视为不带键（空白归一为 null，不占唯一索引），
        // 走原插入路径，旧前端行为不变
        String clientRequestId = review.getClientRequestId();
        if (clientRequestId != null) {
            String trimmed = clientRequestId.trim();
            if (trimmed.isEmpty()) {
                review.setClientRequestId(null);
            } else {
                review.setClientRequestId(trimmed);
                Review existing = selectByClientRequestId(trimmed);
                if (existing != null) {
                    log.info("评价幂等命中，重复提交已忽略: clientRequestId={}, firstReviewId={}",
                            trimmed, existing.getId());
                    return existing;
                }
            }
        }
        // M6 购买归属校验：本人订单（任意状态）须含该菜品明细，防登录后对任意商品刷评
        if (orderItemMapper.countByUserIdAndDishId(userId, review.getDishId()) <= 0) {
            throw new BizException("请先购买该商品后再评价");
        }
        // M6 reviewer 服务端按登录手机号脱敏生成（138****1234），忽略前端直传昵称；user 须存在
        User user = userMapper.selectById(userId);
        if (user == null) {
            // userId 来自已验证 token，正常必命中；兜底防静默失败（同 AuthServiceImpl 口径）
            throw new BizException("用户不存在，请重新登录");
        }
        review.setUserId(userId);
        review.setReviewer(maskPhone(user.getPhone()));
        // T-M4-05 敏感词过滤（对原文匹配，先于 HTML 转义）：
        // 硬词 → 400"评价包含违规内容"（前端保留输入不清空）；软词 → audit_status=pending 待人工复核；正常 → NULL
        if (wordFilter.matchHard(review.getContent()) != null) {
            throw new BizException("评价包含违规内容");
        }
        review.setAuditStatus(wordFilter.matchSoft(review.getContent()) != null
                ? Review.AUDIT_PENDING : null);
        // T-M1-04：评价内容入库前 HTML 转义，防存储型 XSS
        if (review.getContent() != null) {
            review.setContent(HtmlUtils.htmlEscape(review.getContent()));
        }
        reviewMapper.insert(review);
        // 落库后按该 dish 真实评价聚合（rating>=4 占比）回写好评率，替代 init.sql 种子公式静态假值；
        // 无有效评价时回写影响行数 0，good_rate 保留种子兜底文案，详见 DishMapper.refreshGoodRate
        dishMapper.refreshGoodRate(review.getDishId());
        return review;
    }

    /** F-124 同键评价快查：uk_review_client_request_id 唯一约束保证至多一行 */
    private Review selectByClientRequestId(String clientRequestId) {
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        wrapper.eq("client_request_id", clientRequestId);
        return reviewMapper.selectOne(wrapper);
    }

    /** 138****1234（与 OrderServiceImpl 脱敏口径一致；登录手机号已按 1[3-9]\d{9} 强校验，无需长度防御） */
    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    // ------------------------------------------------------------------
    // P10-6b 评价带图（D3 上传 / D3 列表出参 / D4 驳回联动）
    // ------------------------------------------------------------------

    @Override
    public String addImage(Long userId, Long dishId, Long reviewId, MultipartFile file) {
        // ① review 存在（404 同 AdminServiceImpl#auditReview 的「评价不存在」文案口径）
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BizException(R.CODE_NOT_FOUND, "评价不存在");
        }
        // ② 归属本人（D7：403 归属；message 恒 MSG_FORBIDDEN 与全仓 403 一致）。
        //   userId 为 null 的历史/种子评价（init.sql review.user_id NULL）归属不可考，一律拒——
        //   服务端从不给这类评价落图，防「匿名行被任何人认领」
        if (review.getUserId() == null || !review.getUserId().equals(userId)) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
        // ③ review.dish_id 与路径 dishId 一致（防把 A 菜品评价的图挂到 B 菜品路径下）
        if (!review.getDishId().equals(dishId)) {
            throw new BizException(R.CODE_BAD_REQUEST, "评价与菜品不匹配");
        }
        // ④ 图数 <3（D1：每评价 ≤3 张，服务端计数拒绝）
        Long count = reviewImageMapper.selectCount(new LambdaQueryWrapper<ReviewImage>()
                .eq(ReviewImage::getReviewId, reviewId));
        if (count != null && count >= MAX_IMAGES_PER_REVIEW) {
            throw new BizException(R.CODE_BAD_REQUEST, "每条评价最多上传" + MAX_IMAGES_PER_REVIEW + "张图片");
        }

        // ⑤ 校验链照 MerchantController#upload 顺序复制（扩展名白名单 → 大小 → 魔数）：
        //   扩展名仅作辅助，魔数为最终裁决（契约 D1）；IO 异常不在此捕获，交 GlobalExceptionHandler
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
        } catch (java.io.IOException e) {
            throw new BizException(R.CODE_BAD_REQUEST, "文件类型不合法");
        }
        if (!magicMatched(ext, header, read)) {
            throw new BizException(R.CODE_BAD_REQUEST, "文件类型不合法");
        }

        // ⑥ 落盘 {fresh.upload-dir}/reviews/{yyyyMM}/{uuid}.{ext}：文件名/年月子目录全部服务端生成
        //   （原始文件名只参与后缀比对不参与命名——路径穿越防线照 MerchantController 注释同款），
        //   越界防护纵深兜底：最终路径 normalize 后必须仍在 upload-dir 内
        String month = LocalDateTime.now().format(MONTH_DIR_FORMAT);
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path baseDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path target = baseDir.resolve(IMAGE_SUB_DIR).resolve(month).resolve(fileName).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BizException(R.CODE_BAD_REQUEST, "文件类型不合法");
        }
        try {
            Files.createDirectories(target.getParent());   // 兜底年月子目录不存在（首次上传时）
            file.transferTo(target.toFile());
        } catch (java.io.IOException e) {
            throw new BizException(R.CODE_SERVER_ERROR, R.MSG_SERVER_ERROR);
        }

        // ⑦ 落行 review_image，返回展示路径（契约 D7：/uploads/reviews/...，静态映射 /uploads/** 覆盖子目录）
        String webPath = "/uploads/" + IMAGE_SUB_DIR + "/" + month + "/" + fileName;
        ReviewImage image = new ReviewImage();
        image.setReviewId(reviewId);
        image.setFilePath(webPath);
        reviewImageMapper.insert(image);
        return webPath;
    }

    @Override
    public void fillImages(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }
        // 一条 IN 查询防 N+1（契约点名）；按 id 升序保证同一评价的图按上传顺序展示
        List<Long> reviewIds = reviews.stream().map(Review::getId).collect(Collectors.toList());
        Map<Long, List<String>> byReview = reviewImageMapper.selectList(new LambdaQueryWrapper<ReviewImage>()
                        .in(ReviewImage::getReviewId, reviewIds)
                        .orderByAsc(ReviewImage::getId))
                .stream()
                .collect(Collectors.groupingBy(ReviewImage::getReviewId,
                        Collectors.mapping(ReviewImage::getFilePath, Collectors.toList())));
        for (Review review : reviews) {
            review.setImages(byReview.getOrDefault(review.getId(), List.of()));
        }
    }

    @Override
    public void deleteImagesByReviewId(Long reviewId) {
        List<ReviewImage> images = reviewImageMapper.selectList(new LambdaQueryWrapper<ReviewImage>()
                .eq(ReviewImage::getReviewId, reviewId));
        if (images.isEmpty()) {
            return;
        }
        reviewImageMapper.deleteBatchIds(images.stream().map(ReviewImage::getId).collect(Collectors.toList()));
        // 文件尽力删（契约 D4：文件失败仅日志，不抛出、不回滚——绝不打断既有驳回链路）；
        // 本方法与 reviewMapper.deleteById 同处调用方事务，若图行删除后事务因故回滚，
        // 已删文件不可恢复，但 Files.deleteIfExists 幂等——下次驳回自然收口
        for (ReviewImage image : images) {
            try {
                Path f = resolveUnderUploadDir(image.getFilePath());
                if (f == null) {
                    log.warn("评价图路径形态异常，跳过文件删除: reviewId={}, path={}", reviewId, image.getFilePath());
                    continue;
                }
                Files.deleteIfExists(f);
            } catch (Exception e) {
                log.warn("评价图文件删除失败（仅日志不回滚）: reviewId={}, path={}",
                        reviewId, image.getFilePath(), e);
            }
        }
    }

    /**
     * 把入库的展示路径（/uploads/reviews/...）解析回 upload-dir 下的物理路径；
     * 解析结果越界（normalize 后不在 upload-dir 内）或前缀不符返回 null——与落盘侧同一越界防线
     */
    private Path resolveUnderUploadDir(String webPath) {
        if (webPath == null || !webPath.startsWith("/uploads/")) {
            return null;
        }
        Path baseDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path f = baseDir.resolve(webPath.substring("/uploads/".length())).normalize();
        return f.startsWith(baseDir) ? f : null;
    }

    /**
     * 文件头魔数比对（照 MerchantController#magicMatched 同款）：JPEG FF D8 FF；PNG 89 50 4E 47 0D 0A 1A 0A；
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
