package com.enrollment.infrastructure;

import com.enrollment.domain.Enrollment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    // 중복 신청 여부 확인 — PENDING/WAITING/CONFIRMED 상태인 활성 신청
    @Query("""
        SELECT e FROM Enrollment e
        WHERE e.student.id = :studentId
          AND e.lecture.id = :lectureId
          AND e.status IN ('PENDING', 'WAITING', 'CONFIRMED')
        """)
    Optional<Enrollment> findActiveByStudentAndLecture(
        @Param("studentId") Long studentId,
        @Param("lectureId") Long lectureId
    );

    // 시간 충돌 검사용 — 학생의 CONFIRMED 수강 신청과 해당 강의 스케줄을 함께 로드
    @Query("""
        SELECT e FROM Enrollment e
        JOIN FETCH e.lecture l
        JOIN FETCH l.schedules
        WHERE e.student.id = :studentId
          AND e.status = 'CONFIRMED'
        """)
    List<Enrollment> findConfirmedWithSchedulesByStudentId(@Param("studentId") Long studentId);

    // 대기열 승격 대상 조회 — appliedAt 오름차순 (가장 먼저 신청한 1명)
    @Query("""
        SELECT e FROM Enrollment e
        WHERE e.lecture.id = :lectureId
          AND e.status = 'WAITING'
        ORDER BY e.appliedAt ASC
        """)
    List<Enrollment> findWaitingByLectureOrderByAppliedAt(
        @Param("lectureId") Long lectureId,
        Pageable pageable
    );

    // 내 신청 목록 페이지네이션
    Page<Enrollment> findByStudentId(Long studentId, Pageable pageable);

    // 강의별 수강생 목록 (CREATOR 용)
    Page<Enrollment> findByLectureId(Long lectureId, Pageable pageable);

    // 대기열에서 내 앞 순번 계산
    @Query("""
        SELECT COUNT(e) FROM Enrollment e
        WHERE e.lecture.id = :lectureId
          AND e.status = 'WAITING'
          AND e.appliedAt < :appliedAt
        """)
    long countWaitingAhead(
        @Param("lectureId") Long lectureId,
        @Param("appliedAt") LocalDateTime appliedAt
    );

    // 강의 CLOSED 전환 시 해당 강의의 모든 PENDING/WAITING 을 일괄 CANCELLED 처리
    @Modifying
    @Query("""
        UPDATE Enrollment e
        SET e.status = 'CANCELLED', e.cancelledAt = :now
        WHERE e.lecture.id = :lectureId
          AND e.status IN ('PENDING', 'WAITING')
        """)
    int cancelPendingAndWaitingByLectureId(
        @Param("lectureId") Long lectureId,
        @Param("now") LocalDateTime now
    );

    // 내 신청 목록 — lecture, student JOIN FETCH (N+1 방지)
    @Query(value = """
        SELECT e FROM Enrollment e
        JOIN FETCH e.lecture
        JOIN FETCH e.student
        WHERE e.student.id = :studentId
        """,
        countQuery = "SELECT COUNT(e) FROM Enrollment e WHERE e.student.id = :studentId")
    Page<Enrollment> findByStudentIdWithDetails(@Param("studentId") Long studentId, Pageable pageable);

    // 강의별 수강생 목록 — student JOIN FETCH (N+1 방지)
    @Query(value = """
        SELECT e FROM Enrollment e
        JOIN FETCH e.student
        JOIN FETCH e.lecture
        WHERE e.lecture.id = :lectureId
        """,
        countQuery = "SELECT COUNT(e) FROM Enrollment e WHERE e.lecture.id = :lectureId")
    Page<Enrollment> findByLectureIdWithDetails(@Param("lectureId") Long lectureId, Pageable pageable);

    // 대기열 총 인원 수
    @Query("SELECT COUNT(e) FROM Enrollment e WHERE e.lecture.id = :lectureId AND e.status = 'WAITING'")
    long countWaitingByLectureId(@Param("lectureId") Long lectureId);
}
