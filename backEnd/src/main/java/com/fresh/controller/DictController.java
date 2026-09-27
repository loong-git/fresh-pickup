package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.service.DictService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 字典代理接口（T-M2-07，契约 M2-7）：匿名可读（权限矩阵 M2-3），
 * 前端 dict.js 改调自家 /api/dict?group=，FALLBACK 降级保留在前端。
 * sk_ 密钥收敛后端；Caffeine 10 分钟缓存；限流 10 次/分钟/IP（T-M2-06）。
 */
@RestController
@RequestMapping("/api/dict")
public class DictController {

    @Autowired
    private DictService dictService;

    /** 字典分组词条：data=[{word, definition, sortOrder}] 按 sortOrder 降序；未知分组/上游异常 → 400 */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)
    @GetMapping
    public R<List<Map<String, Object>>> getDict(@RequestParam("group") String group) {
        return R.ok(dictService.getGroup(group));
    }
}
