-- M12 增量迁移（F-06 自提点地图化）：store 加经纬度列 + 4 家门店坐标种子
-- 坐标为佛山顺德容桂一带手工配置（113.27-113.30E / 22.74-22.78N 合理散布），非真实测绘；
-- lng/lat 可为 NULL（历史数据/未跑本迁移的库不报错，NULL 门店不进地图不参与距离排序，前端降级链兜底）

ALTER TABLE store
    ADD COLUMN lng DECIMAL(10,6) NULL COMMENT '经度（F-06 地图）',
    ADD COLUMN lat DECIMAL(10,6) NULL COMMENT '纬度（F-06 地图）';

-- 坐标种子（可重跑：UPDATE 幂等）
UPDATE store SET lng = 113.285600, lat = 22.774500 WHERE id = 1;
UPDATE store SET lng = 113.289200, lat = 22.776800 WHERE id = 2;
UPDATE store SET lng = 113.264100, lat = 22.759400 WHERE id = 3;
UPDATE store SET lng = 113.270500, lat = 22.768800 WHERE id = 4;
