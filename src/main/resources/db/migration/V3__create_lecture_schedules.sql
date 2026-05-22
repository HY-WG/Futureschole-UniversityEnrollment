-- 강의 시간표 테이블
-- 하나의 강의(lecture)는 여러 요일/시간대를 가질 수 있다
-- 강의 삭제 시 시간표도 함께 삭제 (ON DELETE CASCADE)
CREATE TABLE lecture_schedules (
    id          BIGSERIAL   PRIMARY KEY,
    lecture_id  BIGINT      NOT NULL REFERENCES lectures(id) ON DELETE CASCADE,
    day_of_week VARCHAR(10) NOT NULL
                            CHECK (day_of_week IN ('MON','TUE','WED','THU','FRI','SAT','SUN')),
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    CONSTRAINT chk_schedule_time CHECK (start_time < end_time)
);
