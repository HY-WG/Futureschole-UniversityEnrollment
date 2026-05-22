package com.enrollment.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record CreateLectureRequest(
    @NotBlank String title,
    String description,
    @Min(0) int price,
    @Min(1) int capacity,
    LocalDate startDate,
    LocalDate endDate,
    @NotNull @Valid List<LectureScheduleRequest> schedules
) {}
