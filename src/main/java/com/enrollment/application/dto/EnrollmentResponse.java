package com.enrollment.application.dto;

import com.enrollment.domain.Enrollment;
import com.enrollment.domain.EnrollmentStatus;
import java.time.LocalDateTime;

public record EnrollmentResponse(
    Long id,
    Long lectureId,
    String lectureTitle,
    Long studentId,
    String studentName,
    EnrollmentStatus status,
    LocalDateTime appliedAt,
    LocalDateTime confirmedAt,
    LocalDateTime cancelledAt
) {
    public static EnrollmentResponse from(Enrollment e) {
        return new EnrollmentResponse(
            e.getId(),
            e.getLecture().getId(),
            e.getLecture().getTitle(),
            e.getStudent().getId(),
            e.getStudent().getName(),
            e.getStatus(),
            e.getAppliedAt(),
            e.getConfirmedAt(),
            e.getCancelledAt()
        );
    }
}
