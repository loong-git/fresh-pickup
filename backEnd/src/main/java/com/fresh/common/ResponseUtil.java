package com.fresh.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 拦截器内直接写统一信封（T-M2-02/T-M2-06）：
 * 拦截器阶段不走 GlobalExceptionHandler，401/429 在此按信封格式写 response JSON 后返回 false，
 * 不抛裸异常（任务要求）。HTTP 状态码恒 200，码表经 body.code 携带——与 GlobalExceptionHandler
 * 的既有约定一致（前端 request 层只看 body.code 分流，判据在 front/api/index.js 里 request 的 success 分支）。
 * 带结构化载荷时走四参版（F-22：429 经 data 下发 retryAfterSeconds），
 * 401 等无载荷的码仍走三参版——三参版即四参版传 data=null，出参与改造前逐字节同形。
 */
public final class ResponseUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseUtil() {
    }

    public static void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        writeJson(response, code, message, null);
    }

    /**
     * 带 data 的信封（写法照 GlobalExceptionHandler#handleBiz:31-34：先 R.fail 再 setData，
     * data 为 null 时不调 setData，保证与三参版输出逐字节同形）。
     */
    public static void writeJson(HttpServletResponse response, int code, String message, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        R<Object> r = R.fail(code, message);
        if (data != null) {
            r.setData(data);
        }
        response.getWriter().write(MAPPER.writeValueAsString(r));
    }
}
