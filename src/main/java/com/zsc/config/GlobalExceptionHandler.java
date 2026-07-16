package com.zsc.config;

import com.zsc.entity.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * 全局异常处理器。
 * <p>
 * 统一处理 Controller 层未捕获的异常，返回标准化的 {@link ApiResult} 格式，
 * 避免每个 Controller 方法都写 try-catch。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 参数缺失异常 → 400 Bad Request
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[GlobalException] 参数缺失: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(400, "参数缺失: " + e.getParameterName()));
    }

    /**
     * 非法参数异常 → 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[GlobalException] 非法参数: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(400, "非法参数: " + e.getMessage()));
    }

    /**
     * IO 异常（文件读写、网络等） → 500 Internal Server Error
     */
    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<ApiResult<Void>> handleIOException(java.io.IOException e) {
        log.error("[GlobalException] IO 异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(500, "IO 异常: " + e.getMessage()));
    }

    /**
     * 所有未预期的异常 → 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleGenericException(Exception e) {
        log.error("[GlobalException] 未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(500, "服务器内部错误: " + e.getClass().getSimpleName()));
    }
}
