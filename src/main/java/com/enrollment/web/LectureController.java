package com.enrollment.web;

import com.enrollment.application.LectureService;
import com.enrollment.application.dto.*;
import com.enrollment.domain.LectureStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 강의 등록, 상태 전이, 조회 API
// 인증: X-User-Id 헤더로 사용자 식별 (Role 검증은 서비스 계층에서 수행)
@RestController
@RequestMapping("/classes")
public class LectureController {

    private final LectureService lectureService;

    public LectureController(LectureService lectureService) {
        this.lectureService = lectureService;
    }

    // 강의 등록 — CREATOR 전용
    @PostMapping
    public ResponseEntity<LectureResponse> createLecture(
        @RequestBody @Valid CreateLectureRequest request,
        @RequestHeader("X-User-Id") Long userId
    ) {
        LectureResponse response = lectureService.createLecture(request, userId);
        return ResponseEntity.status(201).body(response);
    }

    // DRAFT → OPEN 전환 — 해당 강의 CREATOR 전용
    @PatchMapping("/{classId}/open")
    public ResponseEntity<LectureResponse> openLecture(
        @PathVariable Long classId,
        @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(lectureService.openLecture(classId, userId));
    }

    // OPEN → CLOSED 전환 — 해당 강의 CREATOR 전용
    @PatchMapping("/{classId}/close")
    public ResponseEntity<LectureResponse> closeLecture(
        @PathVariable Long classId,
        @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(lectureService.closeLecture(classId, userId));
    }

    // 강의 목록 — status 필터 선택 가능, 페이지네이션 지원
    @GetMapping
    public ResponseEntity<PageResponse<LectureSummaryResponse>> getLectures(
        @RequestParam(required = false) LectureStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(lectureService.getLectures(status, pageable));
    }

    // 강의 상세 — confirmedCount 포함, schedules 포함
    @GetMapping("/{classId}")
    public ResponseEntity<LectureResponse> getLecture(@PathVariable Long classId) {
        return ResponseEntity.ok(lectureService.getLecture(classId));
    }
}
