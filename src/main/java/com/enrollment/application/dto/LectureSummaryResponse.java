package com.enrollment.application.dto;

import com.enrollment.domain.Lecture;
import com.enrollment.domain.LectureStatus;
import java.time.LocalDate;

// 강의 목록 조회용 — schedules 미포함 (목록에서는 불필요)
public record LectureSummaryResponse(
    Long id,
    Long creatorId,
    String creatorName,
    String title,
    int price,
    int capacity,
    int confirmedCount,
    LocalDate startDate,
    LocalDate endDate,
    LectureStatus status
) {
    public static LectureSummaryResponse from(Lecture lecture) {
        return new LectureSummaryResponse(
            lecture.getId(),
            lecture.getCreator().getId(),
            lecture.getCreator().getName(),
            lecture.getTitle(),
            lecture.getPrice(),
            lecture.getCapacity(),
            lecture.getConfirmedCount(),
            lecture.getStartDate(),
            lecture.getEndDate(),
            lecture.getStatus()
        );
    }
}
