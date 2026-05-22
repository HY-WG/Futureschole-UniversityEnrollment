package com.enrollment.domain.exception;

// 이미 활성(PENDING/WAITING/CONFIRMED) 수강 신청이 존재할 때 → 409
public class DuplicateEnrollmentException extends EnrollmentException {
    public DuplicateEnrollmentException(String message) {
        super(message);
    }
}
