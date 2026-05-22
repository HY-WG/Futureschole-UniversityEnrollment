package com.enrollment.infrastructure;

import com.enrollment.domain.Lecture;
import com.enrollment.domain.LectureStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LectureRepository extends JpaRepository<Lecture, Long> {

    // Lecture row 락 — 동시성 제어 락 순서 2단계 (Student 락 획득 후 호출)
    // JOIN FETCH 를 사용하지 않는 단순 쿼리 — JOIN FETCH 와 PESSIMISTIC_WRITE 혼용 시
    // Hibernate 가 follow-on locking(HHH000444)으로 폴백하여 Optimistic Lock 충돌 발생
    // schedules/creator 는 트랜잭션 내 lazy load 로 접근 (락 획득 후라 안전)
    // 락 순서 변경 시 데드락 발생 가능 — 절대 바꾸지 말 것
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Lecture l WHERE l.id = :id")
    Optional<Lecture> findByIdWithLock(@Param("id") Long id);

    // 상세 조회용 (락 없음) — creator, schedules 포함
    @Query("SELECT l FROM Lecture l JOIN FETCH l.creator LEFT JOIN FETCH l.schedules WHERE l.id = :id")
    Optional<Lecture> findByIdWithSchedules(@Param("id") Long id);

    // 목록 조회 — creator JOIN FETCH (N+1 방지), schedules 미포함 (목록에는 불필요)
    @Query(value = "SELECT l FROM Lecture l JOIN FETCH l.creator WHERE l.status = :status",
           countQuery = "SELECT COUNT(l) FROM Lecture l WHERE l.status = :status")
    Page<Lecture> findByStatusWithCreator(@Param("status") LectureStatus status, Pageable pageable);

    @Query(value = "SELECT l FROM Lecture l JOIN FETCH l.creator",
           countQuery = "SELECT COUNT(l) FROM Lecture l")
    Page<Lecture> findAllWithCreator(Pageable pageable);
}
