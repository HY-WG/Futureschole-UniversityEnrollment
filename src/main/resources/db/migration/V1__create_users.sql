-- 회원 테이블
-- 테이블명 'users' — PostgreSQL 예약어인 'user' 충돌 방지
CREATE TABLE users (
    id   BIGSERIAL    PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(20)  NOT NULL CHECK (role IN ('STUDENT', 'CREATOR'))
);
