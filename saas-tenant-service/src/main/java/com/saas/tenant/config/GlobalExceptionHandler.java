package com.saas.tenant.config;

import com.saas.common.result.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SecurityException.class)
    public Result<String> handleSecurityException(SecurityException e) {
        return Result.fail(403, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        return Result.fail(500, "系统内部错误: " + e.getMessage());
    }
}