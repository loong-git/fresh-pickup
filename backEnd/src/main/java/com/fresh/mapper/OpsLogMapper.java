package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.OpsLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 现场动作审计 ops_log 的落库入口（F-18.8 第 3 条，表见 m17-migration.sql:90-99）。
 *
 * <p>只有 BaseMapper 的 {@code insert}：ops_log 是 append-only 的审计旁路，本期没有读侧端点，
 * 不预置任何查询/更新方法（照 AdminAuditLogMapper 的同型收敛）。
 *
 * <p>调用方一律经 {@code com.fresh.service.OpsLogWriter}，**不在业务代码里直接 insert 本 Mapper**——
 * 旁路双层 try-catch（写失败只告警、绝不打断主事务）在全仓只保留那一份实现。
 */
@Mapper
public interface OpsLogMapper extends BaseMapper<OpsLog> {
}
