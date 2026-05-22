package com.enrollment.domain;

import com.enrollment.domain.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LectureTest {

    private User creator;
    private Lecture lecture;

    @BeforeEach
    void setUp() {
        creator = new User("강사A", UserRole.CREATOR);
        lecture = new Lecture(creator, "Java 기초", "설명", 50000, 10, null, null);
    }

    // 상태 전이: DRAFT → OPEN
    @Test
    @DisplayName("DRAFT 상태 강의를 OPEN 으로 전환한다")
    void open_fromDraft_succeeds() {
        lecture.open();
        assertThat(lecture.getStatus()).isEqualTo(LectureStatus.OPEN);
    }

    // 역방향 전이 금지
    @Test
    @DisplayName("OPEN 상태에서 open() 재호출 시 예외")
    void open_fromOpen_throws() {
        lecture.open();
        assertThatThrownBy(lecture::open)
            .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    @DisplayName("CLOSED 상태에서 open() 호출 시 예외")
    void open_fromClosed_throws() {
        lecture.open();
        lecture.close();
        assertThatThrownBy(lecture::open)
            .isInstanceOf(InvalidStatusTransitionException.class);
    }

    // 상태 전이: OPEN → CLOSED
    @Test
    @DisplayName("OPEN 상태 강의를 CLOSED 로 전환한다")
    void close_fromOpen_succeeds() {
        lecture.open();
        lecture.close();
        assertThat(lecture.getStatus()).isEqualTo(LectureStatus.CLOSED);
    }

    @Test
    @DisplayName("DRAFT 상태에서 close() 호출 시 예외")
    void close_fromDraft_throws() {
        assertThatThrownBy(lecture::close)
            .isInstanceOf(InvalidStatusTransitionException.class);
    }

    // hasCapacity: confirmedCount < capacity
    @Test
    @DisplayName("confirmedCount 가 capacity 미만이면 hasCapacity() true")
    void hasCapacity_belowCapacity_true() {
        assertThat(lecture.hasCapacity()).isTrue();
    }

    @Test
    @DisplayName("confirmedCount == capacity 이면 hasCapacity() false")
    void hasCapacity_atCapacity_false() {
        // capacity = 10, confirmedCount 를 10번 증가
        for (int i = 0; i < 10; i++) {
            lecture.incrementConfirmedCount();
        }
        assertThat(lecture.hasCapacity()).isFalse();
    }

    @Test
    @DisplayName("incrementConfirmedCount 는 confirmedCount 를 1 증가시킨다")
    void incrementConfirmedCount_increases() {
        lecture.incrementConfirmedCount();
        assertThat(lecture.getConfirmedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("decrementConfirmedCount 는 confirmedCount 를 1 감소시킨다")
    void decrementConfirmedCount_decreases() {
        lecture.incrementConfirmedCount();
        lecture.decrementConfirmedCount();
        assertThat(lecture.getConfirmedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("confirmedCount 가 0 일 때 decrementConfirmedCount 호출 시 예외")
    void decrementConfirmedCount_belowZero_throws() {
        assertThatThrownBy(lecture::decrementConfirmedCount)
            .isInstanceOf(IllegalStateException.class);
    }
}
