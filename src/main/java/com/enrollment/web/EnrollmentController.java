package com.enrollment.web;

import com.enrollment.application.EnrollmentResult;
import com.enrollment.application.EnrollmentService;
import com.enrollment.application.dto.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 수강 신청 · 결제 확정 · 취소 · 조회 API
// 인증: X-User-Id 헤더로 사용자 식별, 역할 검증은 서비스 계층에서 수행
@RestController
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    // 수강 신청 — PENDING(정원 여유) 또는 WAITING(정원 초과), 두 경우 모두 201
    @PostMapping("/classes/{classId}/enrollments")
    public ResponseEntity<EnrollmentResponse> enroll(
        @PathVariable Long classId,
        @RequestHeader("X-User-Id") Long userId
    ) {
        EnrollmentResult result = enrollmentService.enroll(classId, userId);
        return ResponseEntity.status(201).body(EnrollmentResponse.from(result.enrollment()));
    }

    // 결제 확정: PENDING → CONFIRMED (Student→Lecture 순서 Pessimistic Lock)
    @PostMapping("/enrollments/{enrollmentId}/confirm")
    public ResponseEntity<Void> confirm(
        @PathVariable Long enrollmentId,
        @RequestHeader("X-User-Id") Long userId
    ) {
        enrollmentService.confirm(enrollmentId, userId);
        return ResponseEntity.ok().build();
    }

    // 취소 — CONFIRMED 취소 시 대기열 자동 승격
    @PostMapping("/enrollments/{enrollmentId}/cancel")
    public ResponseEntity<Void> cancel(
        @PathVariable Long enrollmentId,
        @RequestHeader("X-User-Id") Long userId
    ) {
        enrollmentService.cancel(enrollmentId, userId);
        return ResponseEntity.ok().build();
    }

    // 내 수강 신청 목록
    @GetMapping("/me/enrollments")
    public ResponseEntity<PageResponse<EnrollmentResponse>> getMyEnrollments(
        @RequestHeader("X-User-Id") Long userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
            enrollmentService.getMyEnrollments(userId, PageRequest.of(page, size)));
    }

    // 강의별 수강생 목록 — 해당 강의 CREATOR 전용
    @GetMapping("/classes/{classId}/enrollments")
    public ResponseEntity<PageResponse<EnrollmentResponse>> getLectureEnrollments(
        @PathVariable Long classId,
        @RequestHeader("X-User-Id") Long userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
            enrollmentService.getLectureEnrollments(classId, userId, PageRequest.of(page, size)));
    }

    // 대기열 현황 — 전체 대기 인원 및 내 순번
    @GetMapping("/classes/{classId}/waitlist")
    public ResponseEntity<WaitlistResponse> getWaitlist(
        @PathVariable Long classId,
        @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(enrollmentService.getWaitlist(classId, userId));
    }
}
