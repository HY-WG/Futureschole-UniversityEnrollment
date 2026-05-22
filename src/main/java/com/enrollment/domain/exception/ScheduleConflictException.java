package com.enrollment.domain.exception;

// 이미 CONFIRMED 된 강의와 시간표가 겹칠 때 → 409
public class ScheduleConflictException extends EnrollmentException {
    public ScheduleConflictException(String message) {
        super(message);
    }
}
