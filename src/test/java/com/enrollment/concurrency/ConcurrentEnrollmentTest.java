package com.enrollment.concurrency;

import com.enrollment.AbstractIntegrationTest;
import com.enrollment.application.dto.*;
import com.enrollment.domain.*;
import com.enrollment.infrastructure.EnrollmentRepository;
import com.enrollment.infrastructure.LectureRepository;
import com.enrollment.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 동시성 시나리오 통합 테스트 — Testcontainers PostgreSQL + ExecutorService + CountDownLatch
// 모든 시나리오에서 confirmedCount <= capacity, DB 유니크 제약 위반 없음을 검증
@DisplayName("수강 신청 동시성 테스트")
class ConcurrentEnrollmentTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired UserRepository userRepository;
    @Autowired LectureRepository lectureRepository;
    @Autowired EnrollmentRepository enrollmentRepository;

    private User creator;
    private List<User> students;

    @BeforeEach
    void setUp() {
        enrollmentRepository.deleteAll();
        lectureRepository.deleteAll();
        userRepository.deleteAll();

        creator = userRepository.save(new User("강사", UserRole.CREATOR));
        students = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            students.add(userRepository.save(new User("학생" + i, UserRole.STUDENT)));
        }
    }

    // 시나리오 1: 정원 1명, 10명 동시 결제 확정 → confirmedCount == 1 보장
    @Test
    @DisplayName("시나리오1: 정원 1명에 10명 동시 확정 시 confirmedCount == 1")
    void concurrentConfirm_capacityOne_onlyOneConfirmed() throws InterruptedException {
        int concurrency = 10;
        Long lectureId = createAndOpenLecture("정원1강의", 1);

        // 10명 모두 PENDING 으로 사전 등록 (아직 아무도 확정하지 않아 hasCapacity() == true)
        List<Long> enrollmentIds = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            Long id = enroll(lectureId, students.get(i).getId()).getBody().id();
            enrollmentIds.add(id);
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    ResponseEntity<Void> res = restTemplate.exchange(
                        "/enrollments/" + enrollmentIds.get(idx) + "/confirm", HttpMethod.POST,
                        requestEntity(students.get(idx).getId(), null), Void.class);
                    if (res.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        // confirmedCount는 capacity를 초과해선 안 된다
        assertThat(dbConfirmed).isEqualTo(1);
        assertThat(lecture.getConfirmedCount()).isEqualTo(1);
        assertThat(successCount.get()).isEqualTo(1);
    }

    // 시나리오 2: 정원 5명, 10명 동시 확정 → confirmedCount == 5 보장
    @Test
    @DisplayName("시나리오2: 정원 5명에 10명 동시 확정 시 confirmedCount == 5")
    void concurrentConfirm_capacityN_exactlyNConfirmed() throws InterruptedException {
        int capacity = 5;
        int concurrency = 10;
        Long lectureId = createAndOpenLecture("정원5강의", capacity);

        List<Long> enrollmentIds = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            Long id = enroll(lectureId, students.get(i).getId()).getBody().id();
            enrollmentIds.add(id);
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    ResponseEntity<Void> res = restTemplate.exchange(
                        "/enrollments/" + enrollmentIds.get(idx) + "/confirm", HttpMethod.POST,
                        requestEntity(students.get(idx).getId(), null), Void.class);
                    if (res.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        assertThat(dbConfirmed).isEqualTo(capacity);
        assertThat(lecture.getConfirmedCount()).isEqualTo(capacity);
        assertThat(successCount.get()).isEqualTo(capacity);
    }

    // 시나리오 3: 같은 학생이 시간 겹치는 두 강의를 동시에 확정 → 1개만 성공
    @Test
    @DisplayName("시나리오3: 동일 학생의 시간 충돌 강의 동시 확정 시 1개만 CONFIRMED")
    void concurrentConfirm_scheduleConflict_onlyOneSucceeds() throws InterruptedException {
        User student = students.get(0);

        // 두 강의의 시간표가 겹침 — 강의A: 화 14:00-16:00, 강의B: 화 15:00-17:00
        Long lectureId1 = createAndOpenLectureWithSchedule(
            "강의A", 10, ScheduleDay.TUE, LocalTime.of(14, 0), LocalTime.of(16, 0));
        Long lectureId2 = createAndOpenLectureWithSchedule(
            "강의B", 10, ScheduleDay.TUE, LocalTime.of(15, 0), LocalTime.of(17, 0));

        Long enrollId1 = enroll(lectureId1, student.getId()).getBody().id();
        Long enrollId2 = enroll(lectureId2, student.getId()).getBody().id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startGate.await();
                ResponseEntity<Void> res = restTemplate.exchange(
                    "/enrollments/" + enrollId1 + "/confirm", HttpMethod.POST,
                    requestEntity(student.getId(), null), Void.class);
                if (res.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startGate.await();
                ResponseEntity<Void> res = restTemplate.exchange(
                    "/enrollments/" + enrollId2 + "/confirm", HttpMethod.POST,
                    requestEntity(student.getId(), null), Void.class);
                if (res.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        long confirmed1 = enrollmentRepository.countConfirmedByLectureId(lectureId1);
        long confirmed2 = enrollmentRepository.countConfirmedByLectureId(lectureId2);

        // 같은 학생이 시간 충돌 강의에 동시 확정 — 정확히 1개만 CONFIRMED
        assertThat(confirmed1 + confirmed2).isEqualTo(1);
        assertThat(successCount.get()).isEqualTo(1);
    }

    // 시나리오 4: 같은 학생이 같은 강의에 동시 신청 → 1개만 활성 (DB unique 제약)
    @Test
    @DisplayName("시나리오4: 동일 학생의 동일 강의 동시 중복 신청 시 1개만 활성")
    void concurrentEnroll_sameStudentSameLecture_onlyOneActive() throws InterruptedException {
        User student = students.get(0);
        Long lectureId = createAndOpenLecture("중복신청강의", 10);

        int concurrency = 5;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    ResponseEntity<EnrollmentResponse> res = enroll(lectureId, student.getId());
                    if (res.getStatusCode() == HttpStatus.CREATED) successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // DB unique 제약(uq_enrollment_active) 에 의해 활성 신청은 정확히 1개
        assertThat(enrollmentRepository.findActiveByStudentAndLecture(student.getId(), lectureId)).isPresent();
        assertThat(successCount.get()).isEqualTo(1);
    }

    // 시나리오 5: CONFIRMED 취소 동시 다발 + 대기열 승격 → 정확히 N명만 승격
    @Test
    @DisplayName("시나리오5: CONFIRMED 동시 취소 시 대기열에서 정확히 승격 인원만 CONFIRMED 유지")
    void concurrentCancel_withWaiting_confirmedCountStaysConsistent() throws InterruptedException {
        int capacity = 2;
        // studentA, studentB: CONFIRMED / studentC, studentD, studentE: WAITING
        Long lectureId = createAndOpenLecture("정원2대기강의", capacity);

        User studentA = students.get(0);
        User studentB = students.get(1);
        User studentC = students.get(2);
        User studentD = students.get(3);
        User studentE = students.get(4);

        // A, B 를 PENDING → CONFIRMED
        Long enrollIdA = enroll(lectureId, studentA.getId()).getBody().id();
        Long enrollIdB = enroll(lectureId, studentB.getId()).getBody().id();
        confirmEnrollment(enrollIdA, studentA.getId());
        confirmEnrollment(enrollIdB, studentB.getId());

        // C, D, E 는 WAITING (정원 초과)
        enroll(lectureId, studentC.getId());
        enroll(lectureId, studentD.getId());
        enroll(lectureId, studentE.getId());

        // A, B 동시 취소 → C, D 가 각각 1명씩 승격되어야 함
        int concurrency = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        executor.submit(() -> {
            try {
                startGate.await();
                cancelEnrollment(enrollIdA, studentA.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startGate.await();
                cancelEnrollment(enrollIdB, studentB.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        // 취소 2건 → 승격 2건 → confirmedCount 는 여전히 capacity(2) 이하여야 함
        assertThat(dbConfirmed).isLessThanOrEqualTo(capacity);
        assertThat(lecture.getConfirmedCount()).isLessThanOrEqualTo(capacity);
        assertThat(lecture.getConfirmedCount()).isGreaterThanOrEqualTo(0);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────

    private <T> HttpEntity<T> requestEntity(Long userId, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Long createAndOpenLecture(String title, int capacity) {
        return createAndOpenLectureWithSchedule(
            title, capacity, ScheduleDay.MON, LocalTime.of(9, 0), LocalTime.of(11, 0));
    }

    private Long createAndOpenLectureWithSchedule(String title, int capacity,
                                                   ScheduleDay day, LocalTime start, LocalTime end) {
        LectureScheduleRequest schedule = new LectureScheduleRequest(day, start, end);
        CreateLectureRequest req = new CreateLectureRequest(
            title, "설명", 30000, capacity,
            LocalDate.now().plusDays(10), LocalDate.now().plusDays(40), List.of(schedule));

        ResponseEntity<LectureResponse> created = restTemplate.exchange(
            "/classes", HttpMethod.POST,
            requestEntity(creator.getId(), req), LectureResponse.class);
        Long id = created.getBody().id();

        restTemplate.exchange("/classes/" + id + "/open", HttpMethod.PATCH,
            requestEntity(creator.getId(), null), LectureResponse.class);
        return id;
    }

    private ResponseEntity<EnrollmentResponse> enroll(Long lectureId, Long studentId) {
        return restTemplate.exchange("/classes/" + lectureId + "/enrollments", HttpMethod.POST,
            requestEntity(studentId, null), EnrollmentResponse.class);
    }

    private void confirmEnrollment(Long enrollmentId, Long studentId) {
        restTemplate.exchange("/enrollments/" + enrollmentId + "/confirm", HttpMethod.POST,
            requestEntity(studentId, null), Void.class);
    }

    private void cancelEnrollment(Long enrollmentId, Long userId) {
        restTemplate.exchange("/enrollments/" + enrollmentId + "/cancel", HttpMethod.POST,
            requestEntity(userId, null), Void.class);
    }
}
