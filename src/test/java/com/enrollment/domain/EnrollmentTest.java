package com.enrollment.domain;

import com.enrollment.domain.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EnrollmentTest {

    private Lecture lecture;
    private User student;

    @BeforeEach
    void setUp() {
        User creator = new User("강사A", UserRole.CREATOR);
        lecture = new Lecture(creator, "Spring 심화", "설명", 100000, 5, null, null);
        student = new User("학생A", UserRole.STUDENT);
    }

    // PENDING → CONFIRMED
    @Test
    @DisplayName("PENDING 수강 신청을 confirm() 하면 CONFIRMED 상태가 된다")
    void confirm_fromPending_succeeds() {
        Enrollment enrollment = Enrollment.createPending(lecture, student);
        enrollment.confirm();
        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(enrollment.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("PENDING 이 아닌 상태에서 confirm() 호출 시 예외")
    void confirm_fromWaiting_throws() {
        Enrollment enrollment = Enrollment.createWaiting(lecture, student);
        assertThatThrownBy(enrollment::confirm)
            .isInstanceOf(InvalidStatusTransitionException.class);
    }

    // WAITING → CONFIRMED (대기열 승격)
    @Test
    @DisplayName("WAITING 수강 신청을 promote() 하면 CONFIRMED 상태가 된다")
    void promote_fromWaiting_succeeds() {
        Enrollment enrollment = Enrollment.createWaiting(lecture, student);
        enrollment.promote();
        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(enrollment.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("WAITING 이 아닌 상태에서 promote() 호출 시 예외")
    void promote_fromPending_throws() {
        Enrollment enrollment = Enrollment.createPending(lecture, student);
        assertThatThrownBy(enrollment::promote)
            .isInstanceOf(InvalidStatusTransitionException.class);
    }

    // → CANCELLED
    @Test
    @DisplayName("PENDING 수강 신청을 cancel() 하면 CANCELLED 상태가 된다")
    void cancel_fromPending_succeeds() {
        Enrollment enrollment = Enrollment.createPending(lecture, student);
        enrollment.cancel();
        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(enrollment.getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("WAITING 수강 신청을 cancel() 하면 CANCELLED 상태가 된다")
    void cancel_fromWaiting_succeeds() {
        Enrollment enrollment = Enrollment.createWaiting(lecture, student);
        enrollment.cancel();
        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("CONFIRMED 수강 신청을 cancel() 하면 CANCELLED 상태가 된다")
    void cancel_fromConfirmed_succeeds() {
        Enrollment enrollment = Enrollment.createPending(lecture, student);
        enrollment.confirm();
        enrollment.cancel();
        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("이미 CANCELLED 된 수강 신청에 cancel() 재호출 시 예외")
    void cancel_fromCancelled_throws() {
        Enrollment enrollment = Enrollment.createPending(lecture, student);
        enrollment.cancel();
        assertThatThrownBy(enrollment::cancel)
            .isInstanceOf(InvalidStatusTransitionException.class);
    }

    // 팩토리 메서드 검증
    @Test
    @DisplayName("createPending() 은 PENDING 상태와 appliedAt 이 설정된 Enrollment 를 반환한다")
    void createPending_setsStatusAndAppliedAt() {
        Enrollment enrollment = Enrollment.createPending(lecture, student);
        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(enrollment.getAppliedAt()).isNotNull();
    }

    @Test
    @DisplayName("createWaiting() 은 WAITING 상태와 appliedAt 이 설정된 Enrollment 를 반환한다")
    void createWaiting_setsStatusAndAppliedAt() {
        Enrollment enrollment = Enrollment.createWaiting(lecture, student);
        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.WAITING);
        assertThat(enrollment.getAppliedAt()).isNotNull();
    }
}
