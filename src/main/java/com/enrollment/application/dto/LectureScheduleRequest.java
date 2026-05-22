package com.enrollment.application.dto;

import com.enrollment.domain.ScheduleDay;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

public record LectureScheduleRequest(
    @NotNull ScheduleDay dayOfWeek,
    @NotNull LocalTime startTime,
    @NotNull LocalTime endTime
) {}
