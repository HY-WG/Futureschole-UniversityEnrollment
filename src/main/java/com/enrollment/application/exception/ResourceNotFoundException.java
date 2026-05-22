package com.enrollment.application.exception;

// 요청한 리소스가 존재하지 않을 때 → 404
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
