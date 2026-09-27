package com.fresh.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "生鲜配送后端服务运行中");
        result.put("time", LocalDateTime.now().toString());
        result.put("api", "GET /api/dishes  GET /api/dishes/{id}  GET /api/orders");
        return result;
    }
}
