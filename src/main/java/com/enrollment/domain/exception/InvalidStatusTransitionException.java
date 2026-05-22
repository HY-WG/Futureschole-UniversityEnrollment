package com.enrollment.domain.exception;

// 허용되지 않는 상태 전이를 시도할 때 → 422
public class InvalidStatusTransitionException extends EnrollmentException {
    public InvalidStatusTransitionException(String message) {
        super(message);
    }
}
