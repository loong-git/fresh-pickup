package com.fresh.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 拦截器内直接写统一信封（T-M2-02/T-M2-06）：
 * 拦截器阶段不走 GlobalExceptionHandler，401/429 在此按信封格式写 response JSON 后返回 false，
 * 不抛裸异常（任务要求）。HTTP 状态码恒 200，码表经 body.code 携带——与 GlobalExceptionHandler
 * 的既有约定一致（前端 request 层只看 body.code 分流，见 front/api/index.js:70）。
 */
public final class ResponseUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseUtil() {
    }

    public static void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(R.fail(code, message)));
    }
}
