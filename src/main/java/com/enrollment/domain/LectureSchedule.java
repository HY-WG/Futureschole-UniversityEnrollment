package com.enrollment.domain;

import jakarta.persistence.*;
import java.time.LocalTime;

// 강의 시간표. 하나의 강의(Lecture)는 여러 LectureSchedule 을 가진다.
@Entity
@Table(name = "lecture_schedules")
public class LectureSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    private ScheduleDay dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    protected LectureSchedule() {}

    public LectureSchedule(Lecture lecture, ScheduleDay dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.lecture = lecture;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    // 같은 요일에 시간이 겹치는지 확인. 경계(정확히 맞닿는 시각)는 충돌 아님.
    public boolean overlaps(LectureSchedule other) {
        if (this.dayOfWeek != other.dayOfWeek) return false;
        // 겹침 조건: startA < endB AND startB < endA
        return this.startTime.isBefore(other.endTime)
            && other.startTime.isBefore(this.endTime);
    }

    public Long getId() { return id; }
    public Lecture getLecture() { return lecture; }
    public ScheduleDay getDayOfWeek() { return dayOfWeek; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }

    void setLecture(Lecture lecture) { this.lecture = lecture; }
}
