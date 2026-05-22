package com.enrollment.domain.exception;

// 강의가 OPEN 상태가 아닐 때 수강 신청/확정을 시도하는 경우 → 409
public class LectureNotOpenException extends EnrollmentException {
    public LectureNotOpenException(String message) {
        super(message);
    }
}
