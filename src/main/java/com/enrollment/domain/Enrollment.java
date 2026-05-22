package com.enrollment.domain;

import com.enrollment.domain.exception.InvalidStatusTransitionException;
import jakarta.persistence.*;
import java.time.LocalDateTime;

// 수강 신청 엔티티. PENDING/WAITING/CONFIRMED/CANCELLED 상태를 가진다.
// version 필드: Optimistic Lock 용
@Entity
@Table(name = "enrollments")
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EnrollmentStatus status;

    @Column(nullable = false)
    private LocalDateTime appliedAt;

    private LocalDateTime confirmedAt;
    private LocalDateTime cancelledAt;

    @Version
    private Long version;

    protected Enrollment() {}

    // PENDING 상태로 수강 신청 생성 (정원 여유 있을 때)
    public static Enrollment createPending(Lecture lecture, User student) {
        Enrollment e = new Enrollment();
        e.lecture = lecture;
        e.student = student;
        e.status = EnrollmentStatus.PENDING;
        e.appliedAt = LocalDateTime.now();
        return e;
    }

    // WAITING 상태로 수강 신청 생성 (정원 초과 시)
    public static Enrollment createWaiting(Lecture lecture, User student) {
        Enrollment e = new Enrollment();
        e.lecture = lecture;
        e.student = student;
        e.status = EnrollmentStatus.WAITING;
        e.appliedAt = LocalDateTime.now();
        return e;
    }

    // 상태 전이: PENDING → CONFIRMED (결제 확정)
    // 락 순서 및 선행 검증은 서비스 계층(EnrollmentConfirmService)에서 수행
    public void confirm() {
        if (this.status != EnrollmentStatus.PENDING) {
            throw new InvalidStatusTransitionException(
                "PENDING 상태의 수강 신청만 확정할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = EnrollmentStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    // 상태 전이: WAITING → CONFIRMED (취소로 인한 대기열 승격)
    public void promote() {
        if (this.status != EnrollmentStatus.WAITING) {
            throw new InvalidStatusTransitionException(
                "WAITING 상태의 수강 신청만 승격할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = EnrollmentStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    // 상태 전이: any → CANCELLED
    // CONFIRMED 취소의 기간 제한(7일, 강의 시작일)은 서비스 계층에서 검증 후 호출
    public void cancel() {
        if (this.status == EnrollmentStatus.CANCELLED) {
            throw new InvalidStatusTransitionException("이미 취소된 수강 신청입니다.");
        }
        this.status = EnrollmentStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Lecture getLecture() { return lecture; }
    public User getStudent() { return student; }
    public EnrollmentStatus getStatus() { return status; }
    public LocalDateTime getAppliedAt() { return appliedAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public Long getVersion() { return version; }
}
