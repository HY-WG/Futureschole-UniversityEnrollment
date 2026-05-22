package com.enrollment.domain.exception;

// 수강 신청 도메인 예외의 기반 클래스
public abstract class EnrollmentException extends RuntimeException {
    public EnrollmentException(String message) {
        super(message);
    }
}
