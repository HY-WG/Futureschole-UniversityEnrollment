-- 수강 신청 테이블
-- status: PENDING(신청) → CONFIRMED(결제확정) / WAITING(대기) → CONFIRMED(승격)
-- version: Optimistic Lock 용 필드
CREATE TABLE enrollments (
    id           BIGSERIAL   PRIMARY KEY,
    lecture_id   BIGINT      NOT NULL REFERENCES lectures(id),
    student_id   BIGINT      NOT NULL REFERENCES users(id),
    status       VARCHAR(20) NOT NULL
                             CHECK (status IN ('PENDING','WAITING','CONFIRMED','CANCELLED')),
    applied_at   TIMESTAMP   NOT NULL,
    confirmed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    version      BIGINT      NOT NULL DEFAULT 0
);
