package com.enrollment.application;

import com.enrollment.application.dto.*;
import com.enrollment.application.exception.ForbiddenException;
import com.enrollment.application.exception.ResourceNotFoundException;
import com.enrollment.domain.*;
import com.enrollment.domain.exception.*;
import com.enrollment.infrastructure.EnrollmentRepository;
import com.enrollment.infrastructure.LectureRepository;
import com.enrollment.infrastructure.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

// 수강 신청 · 결제 확정 · 취소 처리.
// confirm / cancel 은 Student → Lecture 순서로 락을 잡아 동시성 충돌을 방지한다.
@Service
@Transactional(readOnly = true)
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final LectureRepository lectureRepository;
    private final UserRepository userRepository;

    public EnrollmentService(EnrollmentRepository enrollmentRepository,
                             LectureRepository lectureRepository,
                             UserRepository userRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.lectureRepository = lectureRepository;
        this.userRepository = userRepository;
    }

    // ── 공통 락 획득 메서드 ─────────────────────────────────────────
    // 모든 쓰기 경로에서 반드시 이 메서드를 통해 락 획득 — 우회 금지

    // Student row 락 — 락 순서 1단계
    private User acquireStudentLock(Long studentId) {
        return userRepository.findByIdWithLock(studentId)
            .orElseThrow(() -> new ResourceNotFoundException("학생을 찾을 수 없습니다: " + studentId));
    }

    // Lecture row 락 — 락 순서 2단계 (반드시 Student 락 이후 호출)
    private Lecture acquireLectureLock(Long lectureId) {
        return lectureRepository.findByIdWithLock(lectureId)
            .orElseThrow(() -> new ResourceNotFoundException("강의를 찾을 수 없습니다: " + lectureId));
    }

    // ── 수강 신청 ────────────────────────────────────────────────────

    // 수강 신청 — 정원 여유 시 PENDING, 초과 시 WAITING 으로 자동 진입
    // 대기열 진입은 실패가 아닌 정상 흐름 → EnrollmentResult.waitlisted 로 구분
    @Transactional
    public EnrollmentResult enroll(Long lectureId, Long studentId) {
        Lecture lecture = lectureRepository.findByIdWithSchedules(lectureId)
            .orElseThrow(() -> new ResourceNotFoundException("강의를 찾을 수 없습니다: " + lectureId));

        if (!lecture.isOpen()) {
            throw new LectureNotOpenException("OPEN 상태 강의에만 수강 신청할 수 있습니다.");
        }

        User student = userRepository.findById(studentId)
            .orElseThrow(() -> new ResourceNotFoundException("학생을 찾을 수 없습니다: " + studentId));

        if (student.getRole() != UserRole.STUDENT) {
            throw new ForbiddenException("수강 신청은 STUDENT 역할만 가능합니다.");
        }

        // 중복 신청 확인 — DB unique index 가 최종 방어선
        enrollmentRepository.findActiveByStudentAndLecture(studentId, lectureId)
            .ifPresent(e -> { throw new DuplicateEnrollmentException("이미 활성 수강 신청이 존재합니다."); });

        boolean waitlisted = !lecture.hasCapacity();
        Enrollment enrollment = waitlisted
            ? Enrollment.createWaiting(lecture, student)
            : Enrollment.createPending(lecture, student);

        return new EnrollmentResult(enrollmentRepository.save(enrollment), waitlisted);
    }

    // ── 결제 확정 ────────────────────────────────────────────────────

    // 확정 순서: Student 락 → Lecture 락 → 상태 확인 → 시간 충돌 검사 → 정원 확인 → 상태 변경
    // 락 순서 변경 시 데드락 발생 가능 — 절대 바꾸지 말 것
    @Transactional
    public void confirm(Long enrollmentId, Long studentId) {
        // 1. Student row 락 — 동시성 제어 락 순서 1단계
        acquireStudentLock(studentId);

        // 2. Enrollment 조회 (락 없이)
        Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
            .orElseThrow(() -> new ResourceNotFoundException("수강 신청을 찾을 수 없습니다: " + enrollmentId));

        if (!enrollment.getStudent().getId().equals(studentId)) {
            throw new ForbiddenException("본인의 수강 신청만 확정할 수 있습니다.");
        }

        // 3. Lecture row 락 — 동시성 제어 락 순서 2단계 (Student 락 획득 이후)
        Lecture lecture = acquireLectureLock(enrollment.getLecture().getId());

        // 4. PENDING 상태 확인
        if (enrollment.getStatus() != EnrollmentStatus.PENDING) {
            throw new InvalidStatusTransitionException("PENDING 상태의 수강 신청만 확정할 수 있습니다.");
        }

        // 5. OPEN 상태 확인
        if (!lecture.isOpen()) {
            throw new LectureNotOpenException("OPEN 상태 강의의 수강 신청만 확정할 수 있습니다.");
        }

        // 6. 시간 충돌 검사 — 기존 CONFIRMED 강의 스케줄 vs 신청 강의 스케줄
        checkScheduleConflict(studentId, lecture);

        // 7. 정원 확인 — DB 에서 직접 읽은 confirmedCount 기준
        if (!lecture.hasCapacity()) {
            throw new LectureNotOpenException("강의 정원이 마감되었습니다.");
        }

        // 8 & 9. 상태 변경 및 confirmedCount 증가 → commit 시 반영
        enrollment.confirm();
        lecture.incrementConfirmedCount();
    }

    // ── 취소 ─────────────────────────────────────────────────────────

    // 취소 순서: Student 락 → Lecture 락 → 기간 검증 → 상태 변경 → (CONFIRMED 취소 시) 대기열 승격
    @Transactional
    public void cancel(Long enrollmentId, Long userId) {
        // 1. Student row 락 — 락 순서 1단계
        acquireStudentLock(userId);

        // 2. Enrollment 조회
        Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
            .orElseThrow(() -> new ResourceNotFoundException("수강 신청을 찾을 수 없습니다: " + enrollmentId));

        if (!enrollment.getStudent().getId().equals(userId)) {
            throw new ForbiddenException("본인의 수강 신청만 취소할 수 있습니다.");
        }

        // 3. Lecture row 락 — 락 순서 2단계
        Lecture lecture = acquireLectureLock(enrollment.getLecture().getId());

        boolean wasConfirmed = (enrollment.getStatus() == EnrollmentStatus.CONFIRMED);

        // CONFIRMED 취소 가능 기간 검증: confirmedAt + 7일 이내, 강의 시작일 이전
        if (wasConfirmed) {
            LocalDateTime now = LocalDateTime.now();
            if (ChronoUnit.DAYS.between(enrollment.getConfirmedAt(), now) > 7) { // 결제 후 7일 초과 시 취소 불가
                throw new CancellationPeriodExpiredException("결제 후 7일이 지나 취소할 수 없습니다.");
            }
            if (lecture.getStartDate() != null && !now.toLocalDate().isBefore(lecture.getStartDate())) {
                throw new CancellationPeriodExpiredException("강의 시작일 이후에는 취소할 수 없습니다.");
            }
        }

        enrollment.cancel();

        if (wasConfirmed) {
            lecture.decrementConfirmedCount();
            promoteFromWaitlist(lecture); // 대기열 승격 — 동기 처리 (CLAUDE.md 트레이드오프 참조)
        }
    }

    // ── 대기열 승격 ──────────────────────────────────────────────────

    // 취소로 confirmedCount 감소 시 WAITING 중 appliedAt 가장 이른 1명을 CONFIRMED 로 승격
    // 승격 대상 Student 락은 lecture 락 보유 상태에서 추가 획득 — 동기 승격의 트레이드오프
    private void promoteFromWaitlist(Lecture lecture) {
        List<Enrollment> waiting = enrollmentRepository
            .findWaitingByLectureOrderByAppliedAt(lecture.getId(), PageRequest.of(0, 1));
        if (waiting.isEmpty()) return;

        Enrollment waitingEnrollment = waiting.get(0);

        // 승격 대상 Student 락 획득 — lecture 락 이미 보유 중
        userRepository.findByIdWithLock(waitingEnrollment.getStudent().getId());

        waitingEnrollment.promote();
        lecture.incrementConfirmedCount();
    }

    // ── 조회 ─────────────────────────────────────────────────────────

    public PageResponse<EnrollmentResponse> getMyEnrollments(Long studentId, Pageable pageable) {
        return PageResponse.from(
            enrollmentRepository.findByStudentIdWithDetails(studentId, pageable)
                .map(EnrollmentResponse::from)
        );
    }

    public PageResponse<EnrollmentResponse> getLectureEnrollments(Long lectureId, Long userId, Pageable pageable) {
        Lecture lecture = lectureRepository.findByIdWithSchedules(lectureId)
            .orElseThrow(() -> new ResourceNotFoundException("강의를 찾을 수 없습니다: " + lectureId));
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다: " + userId));

        if (user.getRole() != UserRole.CREATOR || !lecture.getCreator().getId().equals(userId)) {
            throw new ForbiddenException("해당 강의의 CREATOR 만 수강생 목록을 조회할 수 있습니다.");
        }

        return PageResponse.from(
            enrollmentRepository.findByLectureIdWithDetails(lectureId, pageable)
                .map(EnrollmentResponse::from)
        );
    }

    public WaitlistResponse getWaitlist(Long lectureId, Long studentId) {
        long total = enrollmentRepository.countWaitingByLectureId(lectureId);

        Long myPosition = enrollmentRepository.findActiveByStudentAndLecture(studentId, lectureId)
            .filter(e -> e.getStatus() == EnrollmentStatus.WAITING)
            .map(e -> enrollmentRepository.countWaitingAhead(lectureId, e.getAppliedAt()) + 1)
            .orElse(null);

        Long myEnrollmentId = enrollmentRepository.findActiveByStudentAndLecture(studentId, lectureId)
            .filter(e -> e.getStatus() == EnrollmentStatus.WAITING)
            .map(Enrollment::getId)
            .orElse(null);

        return new WaitlistResponse(total, myPosition, myEnrollmentId);
    }

    // ── 내부 검증 ────────────────────────────────────────────────────

    // 기존 CONFIRMED 강의 스케줄과 신청 강의 스케줄의 시간 충돌 검사
    private void checkScheduleConflict(Long studentId, Lecture newLecture) {
        List<Enrollment> confirmedEnrollments =
            enrollmentRepository.findConfirmedWithSchedulesByStudentId(studentId);

        for (Enrollment confirmed : confirmedEnrollments) {
            if (confirmed.getLecture().getId().equals(newLecture.getId())) continue;
            for (LectureSchedule existing : confirmed.getLecture().getSchedules()) {
                for (LectureSchedule newSched : newLecture.getSchedules()) {
                    if (existing.overlaps(newSched)) {
                        throw new ScheduleConflictException(
                            "기존 수강 강의와 시간표가 겹칩니다: " + existing.getDayOfWeek()
                            + " " + existing.getStartTime() + "~" + existing.getEndTime());
                    }
                }
            }
        }
    }
}
