package com.fresh.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（T-M1-02）：所有未捕获异常统一转 R 信封并脱敏——
 * 对外只出码表内 code 与人话文案；完整堆栈仅进服务端日志。
 * 替代各 Controller 逐个 catch(e) 回传 e.getMessage() 的写法（会把 SQL/表名泄露给客户端）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：message 已由抛出方保证是人话 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** @Valid 请求体校验失败（DTO 注解消息即人话文案） */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<Void> handleValid(BindException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe != null ? fe.getDefaultMessage() : "请求参数不合法";
        log.warn("参数校验失败: {}", msg);
        return R.fail(R.CODE_BAD_REQUEST, msg);
    }

    /** 请求体不可读 / 缺参 / 类型不匹配 / 方法不支持：一律 400，不透出内部类名 */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public R<Void> handleBadRequest(Exception e) {
        log.warn("请求不合法: {}", e.getClass().getSimpleName());
        return R.fail(R.CODE_BAD_REQUEST, "请求参数有误，请检查后重试");
    }

    /** multipart 缺 file part：400 人话文案，不落 500 兜底 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public R<Void> handleMissingServletRequestPart(MissingServletRequestPartException e) {
        log.warn("请求不合法: {}", e.getClass().getSimpleName());
        return R.fail(R.CODE_BAD_REQUEST, "请选择文件");
    }

    /** 上传体积超 spring.servlet.multipart.max-file-size（进 handler 前即被打断）：400 文案同上传端点自查口径 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("请求不合法: {}", e.getClass().getSimpleName());
        return R.fail(R.CODE_BAD_REQUEST, "图片过大，请重新选择图片");
    }

    /** 路径资源不存在（含静态资源 404） */
    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNotFound(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getResourcePath());
        return R.fail(R.CODE_NOT_FOUND, "资源不存在");
    }

    /**
     * 兜底：对外固定通用文案（不泄露 SQL/表名/堆栈），完整异常仅进服务端日志。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleUnexpected(Exception e) {
        log.error("系统异常", e);
        return R.fail(R.CODE_SERVER_ERROR, R.MSG_SERVER_ERROR);
    }
}
