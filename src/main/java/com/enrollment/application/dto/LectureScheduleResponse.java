package com.enrollment.application.dto;

import com.enrollment.domain.LectureSchedule;
import com.enrollment.domain.ScheduleDay;
import java.time.LocalTime;

public record LectureScheduleResponse(
    Long id,
    ScheduleDay dayOfWeek,
    LocalTime startTime,
    LocalTime endTime
) {
    public static LectureScheduleResponse from(LectureSchedule schedule) {
        return new LectureScheduleResponse(
            schedule.getId(),
            schedule.getDayOfWeek(),
            schedule.getStartTime(),
            schedule.getEndTime()
        );
    }
}
