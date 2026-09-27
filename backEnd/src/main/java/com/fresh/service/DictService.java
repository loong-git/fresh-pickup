package com.fresh.service;

import java.util.List;
import java.util.Map;

public interface DictService {

    /**
     * 字典分组词条（T-M2-07，契约 M2-7）：后端持 key 代理调字典服务上游，
     * Caffeine 本地缓存 10 分钟（失败结果不缓存，便于下次自动重试）。
     *
     * @param group 分组代码：dish_category / coupon_template / kingkong_entry / service_tags
     * @return 词条列表 [{word, definition, sortOrder}]，按 sortOrder 降序（与前端 normalizeList 结构一致）
     */
    List<Map<String, Object>> getGroup(String group);
}
