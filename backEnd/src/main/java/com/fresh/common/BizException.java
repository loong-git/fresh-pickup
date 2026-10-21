package com.fresh.common;

import lombok.Getter;

/**
 * 业务异常（T-M1-02）：service 层抛出，由 GlobalExceptionHandler 统一转为
 * {code, message} 信封。抛出点实际用到的 code 共五枚（全取自 R.CODE_*）：400 业务校验失败（44 处）、404 资源不存在（26）、401 未认证/口令错（10）、403 角色或停业不符（9）、429 防枚举锁与限流（2）；单参构造走默认 400。
 * message 面向用户，必须是人话，不得包含 SQL/表名/堆栈等内部细节。
 * <p>BE-5（F-18.20 E 行）新增可选 data 载荷：抛出方可给信封补一个结构化小对象
 * （当前唯一使用方=D-14 锁的 429 信封 {retryAfterSeconds:N}），由 GlobalExceptionHandler 透传进 R.data；
 * 不带载荷的旧构造器行为完全不变（data=null，信封也与从前一致）。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    /** 可选信封载荷（BE-5）；null=不下发 data，与历史形态逐字节一致 */
    private final Object data;

    public BizException(int code, String message) {
        this(code, message, null);
    }

    public BizException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public BizException(String message) {
        this(R.CODE_BAD_REQUEST, message);
    }
}
