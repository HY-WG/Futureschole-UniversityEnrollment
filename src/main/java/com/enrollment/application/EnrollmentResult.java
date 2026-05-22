package com.enrollment.application;

import com.enrollment.domain.Enrollment;

// 수강 신청 결과 — 대기열 진입은 예외가 아닌 정상 흐름이므로 결과 객체로 표현
public record EnrollmentResult(Enrollment enrollment, boolean waitlisted) {}
