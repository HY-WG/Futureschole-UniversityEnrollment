package com.enrollment.infrastructure;

import com.enrollment.domain.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// dev 프로파일 전용 대량 데이터 생성기
// 학생 10,000명, 강사 100명, 강좌 500개, 다양한 시간표 및 혼합 수강 상태 생성
@Profile("dev")
@Component
public class DataSeeder implements ApplicationRunner {

    private static final int CREATOR_COUNT   = 100;
    private static final int STUDENT_COUNT   = 10_000;
    private static final int LECTURE_COUNT   = 500;
    private static final int BATCH_SIZE      = 500;

    private static final ScheduleDay[] DAYS = ScheduleDay.values();

    private final JdbcTemplate jdbc;

    public DataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long existingUsers = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        if (existingUsers != null && existingUsers > 0) {
            return; // 이미 시드 데이터가 있으면 스킵
        }

        System.out.println("[DataSeeder] 대량 데이터 생성 시작...");
        long start = System.currentTimeMillis();

        List<Long> creatorIds = seedCreators();
        List<Long> studentIds = seedStudents();
        List<Long> lectureIds = seedLectures(creatorIds);
        seedLectureSchedules(lectureIds);
        seedEnrollments(lectureIds, studentIds);

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[DataSeeder] 완료: 강사 %d명, 학생 %d명, 강좌 %d개 생성 (%dms)%n",
            CREATOR_COUNT, STUDENT_COUNT, LECTURE_COUNT, elapsed);
    }

    // 강사 100명 bulk insert
    private List<Long> seedCreators() {
        List<Object[]> batch = new ArrayList<>(CREATOR_COUNT);
        for (int i = 1; i <= CREATOR_COUNT; i++) {
            batch.add(new Object[]{"강사" + i, "CREATOR"});
        }
        jdbc.batchUpdate("INSERT INTO users (name, role) VALUES (?, ?)", batch);
        return jdbc.queryForList(
            "SELECT id FROM users WHERE role = 'CREATOR' ORDER BY id", Long.class);
    }

    // 학생 10,000명 bulk insert — BATCH_SIZE 단위로 나눠 처리
    private List<Long> seedStudents() {
        for (int batchStart = 1; batchStart <= STUDENT_COUNT; batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE - 1, STUDENT_COUNT);
            List<Object[]> batch = new ArrayList<>(batchEnd - batchStart + 1);
            for (int i = batchStart; i <= batchEnd; i++) {
                batch.add(new Object[]{"학생" + i, "STUDENT"});
            }
            jdbc.batchUpdate("INSERT INTO users (name, role) VALUES (?, ?)", batch);
        }
        return jdbc.queryForList(
            "SELECT id FROM users WHERE role = 'STUDENT' ORDER BY id", Long.class);
    }

    // 강좌 500개 생성 — 다양한 정원(타이트한 정원 포함), 날짜 범위
    private List<Long> seedLectures(List<Long> creatorIds) {
        Random rand = new Random(42);
        LocalDate today = LocalDate.now();

        List<Object[]> batch = new ArrayList<>(LECTURE_COUNT);
        for (int i = 1; i <= LECTURE_COUNT; i++) {
            Long creatorId = creatorIds.get(rand.nextInt(creatorIds.size()));
            String title = "강좌" + i;
            String desc = "강좌 " + i + " 설명";
            int price = (rand.nextInt(10) + 1) * 10000; // 1만~10만원
            // 타이트한 정원: 처음 50개는 정원 1~3명, 나머지는 5~30명
            int capacity = i <= 50 ? rand.nextInt(3) + 1 : rand.nextInt(26) + 5;
            LocalDate startDate = today.plusDays(rand.nextInt(60) + 7);
            LocalDate endDate = startDate.plusDays(rand.nextInt(60) + 30);
            String status = i <= 100 ? "DRAFT" : "OPEN"; // 처음 100개는 DRAFT, 나머지 OPEN

            batch.add(new Object[]{creatorId, title, desc, price, capacity, 0, startDate, endDate, status, 0L});
        }

        jdbc.batchUpdate(
            "INSERT INTO lectures (creator_id, title, description, price, capacity, confirmed_count, " +
            "start_date, end_date, status, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", batch);

        return jdbc.queryForList("SELECT id FROM lectures ORDER BY id", Long.class);
    }

    // 강좌별 시간표 생성
    // - 의도적 충돌: 처음 10쌍(20개 강좌)은 같은 요일 겹치는 시간대로 설정
    // - 나머지: 무작위 요일·시간
    private void seedLectureSchedules(List<Long> lectureIds) {
        Random rand = new Random(42);
        List<Object[]> batch = new ArrayList<>();

        // 의도적 시간 충돌 쌍: 강좌 0~1, 2~3, ..., 18~19 → 화요일 10:00-12:00 (겹침)
        for (int i = 0; i < 20 && i < lectureIds.size(); i++) {
            Long lectureId = lectureIds.get(i);
            // 모두 화요일 10:00-12:00 → 서로 시간 충돌
            batch.add(new Object[]{lectureId, "TUE", LocalTime.of(10, 0), LocalTime.of(12, 0)});
        }

        // 나머지 강좌: 무작위 1~2개 시간표
        for (int i = 20; i < lectureIds.size(); i++) {
            Long lectureId = lectureIds.get(i);
            int scheduleCount = rand.nextInt(2) + 1; // 1 또는 2개 시간표
            List<String> usedDays = new ArrayList<>();

            for (int s = 0; s < scheduleCount; s++) {
                String day;
                int attempts = 0;
                do {
                    day = DAYS[rand.nextInt(DAYS.length)].name();
                    attempts++;
                } while (usedDays.contains(day) && attempts < 7);
                if (usedDays.contains(day)) continue;
                usedDays.add(day);

                int startHour = 9 + rand.nextInt(10); // 9~18시
                LocalTime startTime = LocalTime.of(startHour, 0);
                LocalTime endTime = startTime.plusHours(2);
                batch.add(new Object[]{lectureId, day, startTime, endTime});
            }
        }

        jdbc.batchUpdate(
            "INSERT INTO lecture_schedules (lecture_id, day_of_week, start_time, end_time) VALUES (?, ?, ?, ?)",
            batch);
    }

    // 수강 신청 데이터 생성 — 다양한 상태(PENDING, WAITING, CONFIRMED, CANCELLED) 혼합
    private void seedEnrollments(List<Long> lectureIds, List<Long> studentIds) {
        Random rand = new Random(42);
        LocalDateTime now = LocalDateTime.now();

        // OPEN 상태 강좌(index 100~)에만 수강 신청 생성
        List<Long> openLectureIds = lectureIds.subList(
            Math.min(100, lectureIds.size()), lectureIds.size());

        if (openLectureIds.isEmpty()) return;

        // 강좌별 정원 및 confirmedCount 추적
        List<int[]> lectureCapacities = new ArrayList<>();
        for (Long lectureId : openLectureIds) {
            Integer cap = jdbc.queryForObject(
                "SELECT capacity FROM lectures WHERE id = ?", Integer.class, lectureId);
            lectureCapacities.add(new int[]{cap != null ? cap : 5, 0}); // [capacity, confirmedCount]
        }

        List<Object[]> enrollmentBatch = new ArrayList<>();
        List<Object[]> confirmedCountUpdates = new ArrayList<>();

        int studentIdx = 0;
        for (int li = 0; li < openLectureIds.size(); li++) {
            Long lectureId = openLectureIds.get(li);
            int capacity = lectureCapacities.get(li)[0];
            int confirmedSoFar = 0;

            // 강좌당 최대 capacity*2 명 혹은 20명 수강 신청
            int enrollCount = Math.min(capacity * 2 + rand.nextInt(5), 20);

            for (int e = 0; e < enrollCount; e++) {
                Long studentId = studentIds.get(studentIdx % studentIds.size());
                studentIdx++;

                EnrollmentStatus status;
                LocalDateTime appliedAt = now.minusDays(rand.nextInt(30));
                LocalDateTime confirmedAt = null;
                LocalDateTime cancelledAt = null;

                if (confirmedSoFar < capacity && rand.nextInt(3) != 0) {
                    // CONFIRMED 또는 CANCELLED
                    if (rand.nextBoolean()) {
                        status = EnrollmentStatus.CONFIRMED;
                        confirmedAt = appliedAt.plusDays(1);
                        confirmedSoFar++;
                    } else {
                        status = EnrollmentStatus.CANCELLED;
                        cancelledAt = appliedAt.plusDays(2);
                    }
                } else if (confirmedSoFar >= capacity) {
                    // 정원 초과 — WAITING 또는 CANCELLED
                    status = rand.nextBoolean() ? EnrollmentStatus.WAITING : EnrollmentStatus.CANCELLED;
                    if (status == EnrollmentStatus.CANCELLED) {
                        cancelledAt = appliedAt.plusDays(1);
                    }
                } else {
                    status = EnrollmentStatus.PENDING;
                }

                enrollmentBatch.add(new Object[]{
                    lectureId, studentId, status.name(), appliedAt, confirmedAt, cancelledAt, 0L
                });
            }

            if (confirmedSoFar > 0) {
                confirmedCountUpdates.add(new Object[]{confirmedSoFar, lectureId});
            }
        }

        // 수강 신청 bulk insert
        for (int i = 0; i < enrollmentBatch.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, enrollmentBatch.size());
            jdbc.batchUpdate(
                "INSERT INTO enrollments (lecture_id, student_id, status, applied_at, confirmed_at, cancelled_at, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                enrollmentBatch.subList(i, end));
        }

        // confirmedCount 업데이트
        jdbc.batchUpdate(
            "UPDATE lectures SET confirmed_count = ? WHERE id = ?", confirmedCountUpdates);
    }
}
