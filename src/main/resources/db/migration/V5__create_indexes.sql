-- 강의 상태별 목록 조회 최적화
CREATE INDEX idx_lecture_status ON lectures(status);

-- 수강 신청 조회 최적화
CREATE INDEX idx_enrollment_student ON enrollments(student_id);
CREATE INDEX idx_enrollment_lecture ON enrollments(lecture_id);

-- 활성 수강 신청(PENDING/WAITING/CONFIRMED) 중복 방지용 partial unique index
-- CANCELLED 는 제외하여 취소 후 재신청이 가능하도록 함
CREATE UNIQUE INDEX uq_enrollment_active
    ON enrollments(student_id, lecture_id)
    WHERE status IN ('PENDING', 'WAITING', 'CONFIRMED');
