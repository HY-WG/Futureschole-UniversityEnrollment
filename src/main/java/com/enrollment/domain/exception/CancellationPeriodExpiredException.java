package com.enrollment.domain.exception;

// CONFIRMED 취소 가능 기간(confirmedAt + 7일, 강의 시작일 이전)을 초과한 경우 → 422
public class CancellationPeriodExpiredException extends EnrollmentException {
    public CancellationPeriodExpiredException(String message) {
        super(message);
    }
}
