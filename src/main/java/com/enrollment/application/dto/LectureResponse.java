package com.enrollment.application.dto;

import com.enrollment.domain.Lecture;
import com.enrollment.domain.LectureStatus;
import java.time.LocalDate;
import java.util.List;

// 강의 상세 조회용 — schedules 포함
public record LectureResponse(
    Long id,
    Long creatorId,
    String creatorName,
    String title,
    String description,
    int price,
    int capacity,
    int confirmedCount,
    LocalDate startDate,
    LocalDate endDate,
    LectureStatus status,
    List<LectureScheduleResponse> schedules
) {
    public static LectureResponse from(Lecture lecture) {
        return new LectureResponse(
            lecture.getId(),
            lecture.getCreator().getId(),
            lecture.getCreator().getName(),
            lecture.getTitle(),
            lecture.getDescription(),
            lecture.getPrice(),
            lecture.getCapacity(),
            lecture.getConfirmedCount(),
            lecture.getStartDate(),
            lecture.getEndDate(),
            lecture.getStatus(),
            lecture.getSchedules().stream().map(LectureScheduleResponse::from).toList()
        );
    }
}
