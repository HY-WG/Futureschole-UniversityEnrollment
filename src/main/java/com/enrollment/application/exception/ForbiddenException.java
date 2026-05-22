package com.enrollment.application.exception;

// 권한이 없는 작업을 시도할 때 → 403
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
