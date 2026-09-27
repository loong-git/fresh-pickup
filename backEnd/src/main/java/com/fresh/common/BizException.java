package com.fresh.common;

import lombok.Getter;

/**
 * 业务异常（T-M1-02）：service 层抛出，由 GlobalExceptionHandler 统一转为
 * {code, message} 信封。code 仅用 400（业务校验失败）/404（资源不存在）。
 * message 面向用户，必须是人话，不得包含 SQL/表名/堆栈等内部细节。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(R.CODE_BAD_REQUEST, message);
    }
}
