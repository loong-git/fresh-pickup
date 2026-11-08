package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.ReviewImage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评价图 mapper（P10-6b D2/m34）：零手写语句（照 ReviewMapper 既有零手写先例，
 * 列表出参的批量填充走 LambdaQueryWrapper IN 查询，一条 SQL 防 N+1）。
 */
@Mapper
public interface ReviewImageMapper extends BaseMapper<ReviewImage> {
}
