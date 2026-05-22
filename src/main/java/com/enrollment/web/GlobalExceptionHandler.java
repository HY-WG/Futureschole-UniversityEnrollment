package com.enrollment.web;

import com.enrollment.application.exception.ForbiddenException;
import com.enrollment.application.exception.ResourceNotFoundException;
import com.enrollment.domain.exception.*;
import jakarta.persistence.LockTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 409 — 중복 신청, 시간 충돌, OPEN 상태 아님
    @ExceptionHandler({DuplicateEnrollmentException.class, ScheduleConflictException.class, LectureNotOpenException.class})
    public ResponseEntity<ErrorResponse> handleConflict(EnrollmentException ex) {
        return ResponseEntity.status(409).body(new ErrorResponse("CONFLICT", ex.getMessage()));
    }

    // 422 — 허용되지 않는 상태 전이, 취소 기간 초과
    @ExceptionHandler({InvalidStatusTransitionException.class, CancellationPeriodExpiredException.class})
    public ResponseEntity<ErrorResponse> handleUnprocessable(EnrollmentException ex) {
        return ResponseEntity.status(422).body(new ErrorResponse("UNPROCESSABLE_ENTITY", ex.getMessage()));
    }

    // 404 — 존재하지 않는 리소스
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(404).body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    // 403 — 권한 없음
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(403).body(new ErrorResponse("FORBIDDEN", ex.getMessage()));
    }

    // 409 — Pessimistic Lock 타임아웃 (3초 초과 시)
    @ExceptionHandler(LockTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleLockTimeout(LockTimeoutException ex) {
        return ResponseEntity.status(409).body(
            new ErrorResponse("LOCK_TIMEOUT", "요청이 혼잡합니다. 잠시 후 재시도해주세요."));
    }

    // 400 — 요청 헤더 누락
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.status(400).body(
            new ErrorResponse("MISSING_HEADER", ex.getHeaderName() + " 헤더가 필요합니다."));
    }

    // 400 — Bean Validation 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.status(400).body(new ErrorResponse("VALIDATION_ERROR", message));
    }
}
