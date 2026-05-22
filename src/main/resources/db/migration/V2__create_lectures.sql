-- 강의 테이블
-- 테이블명 'lectures' — SQL 예약어/Java 키워드인 'class' 사용 금지
-- version: Optimistic Lock 용 필드 (주 전략은 Pessimistic Lock)
CREATE TABLE lectures (
    id              BIGSERIAL    PRIMARY KEY,
    creator_id      BIGINT       NOT NULL REFERENCES users(id),
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    price           INT          NOT NULL CHECK (price >= 0),
    capacity        INT          NOT NULL CHECK (capacity > 0),
    confirmed_count INT          NOT NULL DEFAULT 0 CHECK (confirmed_count >= 0),
    start_date      DATE,
    end_date        DATE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                                 CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    version         BIGINT       NOT NULL DEFAULT 0
);
