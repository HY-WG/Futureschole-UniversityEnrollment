package com.enrollment.application.dto;

// 대기열 현황 응답 — totalWaiting: 전체 대기 인원, myPosition: 내 순번(null이면 미대기)
public record WaitlistResponse(
    long totalWaiting,
    Long myPosition,
    Long myEnrollmentId
) {}
