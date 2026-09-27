package com.fresh.common;

import lombok.Data;

/**
 * 统一响应信封（T-M1-02）
 * 码表（总文档裁定，前后端一致）：
 *   200 成功；400 业务校验失败（message 为人话）；401 未认证（无/伪造/过期 token，见 AuthInterceptor）；
 *   404 资源不存在；429 限流（见 RateLimitInterceptor）；
 *   500 系统异常（message 固定通用文案，不泄露 SQL/表名/堆栈，见 GlobalExceptionHandler）。
 * 所有码均通过 body.code 携带（HTTP 状态码恒为 200），前端 request 层按 body.code 分流。
 */
@Data
public class R<T> {

    public static final int CODE_OK = 200;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_UNAUTHORIZED = 401;
    public static final int CODE_NOT_FOUND = 404;
    public static final int CODE_TOO_MANY_REQUESTS = 429;
    public static final int CODE_SERVER_ERROR = 500;
    /** 401 对外固定文案（契约 M2-3） */
    public static final String MSG_UNAUTHORIZED = "请先登录";
    /** 429 对外固定文案（契约 M2-6） */
    public static final String MSG_TOO_MANY_REQUESTS = "操作过于频繁，请稍后再试";
    /** 500 对外固定文案，绝不携带内部细节 */
    public static final String MSG_SERVER_ERROR = "服务开小差了，请稍后重试";

    private int code;
    private T data;
    private String message;

    public static <T> R<T> ok(T data) {
        return ok(data, null);
    }

    public static <T> R<T> ok(T data, String message) {
        R<T> r = new R<>();
        r.code = CODE_OK;
        r.data = data;
        r.message = message;
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
