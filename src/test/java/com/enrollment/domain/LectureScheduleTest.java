package com.enrollment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

// 시나리오: LectureSchedule.overlaps() 는 같은 요일에 시간대가 겹치는지 판별한다
class LectureScheduleTest {

    // 겹침 케이스
    @Test
    @DisplayName("같은 요일, 시간대가 완전히 겹치면 true")
    void overlaps_sameDay_fullyOverlapping_true() {
        LectureSchedule a = schedule(ScheduleDay.MON, "09:00", "11:00");
        LectureSchedule b = schedule(ScheduleDay.MON, "10:00", "12:00");
        assertThat(a.overlaps(b)).isTrue();
        assertThat(b.overlaps(a)).isTrue();
    }

    @Test
    @DisplayName("같은 요일, 한 쪽이 다른 쪽을 완전 포함해도 true")
    void overlaps_sameDay_contained_true() {
        LectureSchedule a = schedule(ScheduleDay.TUE, "09:00", "13:00");
        LectureSchedule b = schedule(ScheduleDay.TUE, "10:00", "11:00");
        assertThat(a.overlaps(b)).isTrue();
    }

    // 비겹침 케이스
    @Test
    @DisplayName("다른 요일이면 false")
    void overlaps_differentDay_false() {
        LectureSchedule a = schedule(ScheduleDay.MON, "09:00", "11:00");
        LectureSchedule b = schedule(ScheduleDay.TUE, "09:00", "11:00");
        assertThat(a.overlaps(b)).isFalse();
    }

    @Test
    @DisplayName("같은 요일, 시간대가 전혀 겹치지 않으면 false")
    void overlaps_sameDay_noOverlap_false() {
        LectureSchedule a = schedule(ScheduleDay.WED, "09:00", "11:00");
        LectureSchedule b = schedule(ScheduleDay.WED, "13:00", "15:00");
        assertThat(a.overlaps(b)).isFalse();
    }

    // 경계값: 정확히 맞닿는 시각은 겹침이 아님
    @Test
    @DisplayName("경계값 — A 종료 시각과 B 시작 시각이 동일하면 겹치지 않는다")
    void overlaps_boundary_touchingEdge_false() {
        LectureSchedule a = schedule(ScheduleDay.FRI, "09:00", "11:00");
        LectureSchedule b = schedule(ScheduleDay.FRI, "11:00", "13:00");
        assertThat(a.overlaps(b)).isFalse();
        assertThat(b.overlaps(a)).isFalse();
    }

    private LectureSchedule schedule(ScheduleDay day, String start, String end) {
        User creator = new User("강사", UserRole.CREATOR);
        Lecture lecture = new Lecture(creator, "강의", null, 0, 10, null, null);
        return new LectureSchedule(lecture, day, LocalTime.parse(start), LocalTime.parse(end));
    }
}
