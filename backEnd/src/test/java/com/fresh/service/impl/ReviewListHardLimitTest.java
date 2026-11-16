package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.entity.Review;
import com.fresh.entity.ReviewImage;
import com.fresh.mapper.ReviewImageMapper;
import com.fresh.mapper.ReviewMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-3 R6：C 端单 dish 评价列表硬上限（ReviewServiceImpl#getReviewsByDishId 加 LIMIT 200 后缀）。
 *
 * <p>mock 不执行真 SQL，「200 上限」用渲染 SQL 段取证（手法照 OrderCancelRestockTest ⑦ 的
 * wrapper 绑定值取证 + AdminCsvExportTest 的 TableInfo 灌库范式）：getSqlSegment() 会把
 * last 后缀拼到段尾，断言恰好以 LIMIT 200 收尾、待审过滤与 createTime 排序两段既有口径不变。
 * 本类测不成什么：真库 LIMIT 截断行为（归 verify 脚本/主会话终验兜底）。
 */
class ReviewListHardLimitTest {

    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);
    private final ReviewImageMapper reviewImageMapper = mock(ReviewImageMapper.class);

    private ReviewServiceImpl service;

    /**
     * getSqlSegment() 触发 LambdaQueryWrapper 的实体列名解析（生产由 MP 启动扫 mapper 装配），
     * 本测试不起容器不连库，按 MP 官方入口手工灌一次内存缓存（照 OrderCancelRestockTest 先例）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Review.class);
        TableInfoHelper.initTableInfo(assistant, ReviewImage.class);
    }

    @BeforeEach
    void setUp() {
        service = new ReviewServiceImpl();
        ReflectionTestUtils.setField(service, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(service, "reviewImageMapper", reviewImageMapper);
        when(reviewMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void 单dish评价列表渲染SQL以LIMIT200收尾_待审过滤与排序口径不变() {
        service.getReviewsByDishId(1L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<Review>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(reviewMapper, times(1)).selectList(captor.capture());
        LambdaQueryWrapper<Review> wrapper = captor.getValue();
        String segment = wrapper.getSqlSegment();

        assertTrue(segment.endsWith("LIMIT 200"),
                "硬上限 200 必须以 last 后缀落到渲染 SQL 尾部（防爆款全量）：" + segment);
        assertTrue(segment.contains("audit_status"),
                "T-M4-05 待审过滤（audit_status IS NULL OR <> pending）必须仍在：" + segment);
        assertTrue(segment.contains("create_time"),
                "createTime desc 排序口径不变（200 截掉的是最旧尾部）：" + segment);

        // 返回形状：空列表路径短路（fillImages 对空列表直接 return），与改前一致
        assertTrue(service.getReviewsByDishId(2L).isEmpty());
        verify(reviewImageMapper, times(0)).selectList(any());
        assertEquals(0, service.getReviewsByDishId(2L).size());
    }
}
