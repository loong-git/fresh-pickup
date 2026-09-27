-- M13 增量迁移（F-06.7.2 R6 坐标真源修正）：store 4 家门店坐标由 m12 估算值改为高德真实坐标
-- 来源：高德 Geocoder / PlaceSearch 双源实测（2026-10-05 主会话定案，定稿值见 docs/上线任务文档.md F-06.7.2 §6）
-- 坐标系：GCJ-02（高德地理编码返回即 GCJ-02），与前端高德底图同源——前端门店坐标直用不再 wgs84ToGcj02 转换；
-- 用户浏览器定位（WGS84）仍经 wgs84ToGcj02 转换进图/算距离（不对称转换口径，距离侧为 R6-2）
-- id=2 为纠错值：m4 的「高黎惠民路12号」为演示虚构地址（高黎无此路），Geocoder 直查发生门牌号级误匹配、
-- 偏 8km 至容桂大道南同号，故采用 PlaceSearch 真实 POI「佳惠百货(建业中路店)@容桂高黎信用社」坐标 113.345527,22.774019；
-- id=1/4 为高德对演示地址的最佳匹配值（瓦片复核同片区/近重合），id=3 容桂天佑城 Geocoder 与 PlaceSearch 双源一致到 6 位小数
-- 依赖：m12 已建的 lng/lat 列（DECIMAL(10,6)，6 位小数无损承接）；UPDATE 幂等可重跑，重跑结果不变

UPDATE store SET lng = 113.341634, lat = 22.779372 WHERE id = 1; -- 高黎柱发士多店
UPDATE store SET lng = 113.345527, lat = 22.774019 WHERE id = 2; -- 高黎惠民百货店（PlaceSearch 纠错值）
UPDATE store SET lng = 113.270704, lat = 22.760571 WHERE id = 3; -- 容桂天佑城自提点（双源一致）
UPDATE store SET lng = 113.243995, lat = 22.757834 WHERE id = 4; -- 朝阳社区便利店
