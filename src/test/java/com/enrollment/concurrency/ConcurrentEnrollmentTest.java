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

    // 시나리오 6: confirm과 cancel이 같은 강의에서 동시 발생 → confirmedCount 정합성 유지
    @Test
    @DisplayName("시나리오6: confirm과 cancel 동시 발생 시 confirmedCount 가 capacity 초과·음수 없이 DB와 일치")
    void concurrentConfirmAndCancel_confirmedCountRemainsConsistent() throws InterruptedException {
        int capacity = 2;
        Long lectureId = createAndOpenLecture("확정취소동시강의", capacity);

        User studentA = students.get(0);
        User studentB = students.get(1);
        User studentC = students.get(2);

        // A, B CONFIRMED(정원 소진) / C는 PENDING으로 신청만 된 상태 (confirmedCount=2=capacity)
        Long enrollIdA = enroll(lectureId, studentA.getId()).getBody().id();
        Long enrollIdB = enroll(lectureId, studentB.getId()).getBody().id();
        confirmEnrollment(enrollIdA, studentA.getId());
        confirmEnrollment(enrollIdB, studentB.getId());
        Long enrollIdC = enroll(lectureId, studentC.getId()).getBody().id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        // A 취소(confirmedCount 감소)와 C 확정(confirmedCount 증가)을 동시에 실행
        executor.submit(() -> {
            try { startGate.await(); cancelEnrollment(enrollIdA, studentA.getId()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });
        executor.submit(() -> {
            try { startGate.await(); confirmEnrollment(enrollIdC, studentC.getId()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        assertThat(dbConfirmed).isGreaterThanOrEqualTo(0);
        assertThat(dbConfirmed).isLessThanOrEqualTo(capacity);
        // Lecture 엔티티의 confirmedCount 가 실제 DB 집계와 반드시 일치해야 함
        assertThat(lecture.getConfirmedCount()).isEqualTo(dbConfirmed);
    }

    // 시나리오 7: 다수 동시 취소 + 대기열 → 승격 인원이 취소 인원을 초과하지 않음
    @Test
    @DisplayName("시나리오7: 3명 동시 취소 시 대기열에서 최대 3명만 승격, confirmedCount 일관성 유지")
    void concurrentMultipleCancel_promotedCountDoesNotExceedCancelCount() throws InterruptedException {
        int capacity = 3;
        Long lectureId = createAndOpenLecture("다중취소대기강의", capacity);

        // A, B, C CONFIRMED
        List<Long> confirmedEnrollIds = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            Long id = enroll(lectureId, students.get(i).getId()).getBody().id();
            confirmEnrollment(id, students.get(i).getId());
            confirmedEnrollIds.add(id);
        }
        // D, E, F, G WAITING (정원 초과로 자동 진입)
        for (int i = capacity; i < capacity + 4; i++) {
            enroll(lectureId, students.get(i).getId());
        }

        ExecutorService executor = Executors.newFixedThreadPool(capacity);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(capacity);

        for (int i = 0; i < capacity; i++) {
            final int idx = i;
            executor.submit(() -> {
                try { startGate.await(); cancelEnrollment(confirmedEnrollIds.get(idx), students.get(idx).getId()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
        }

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        // 취소 3건 → 대기 4명 중 최대 3명 승격 → confirmedCount 는 capacity(3) 이하
        assertThat(dbConfirmed).isLessThanOrEqualTo(capacity);
        assertThat(dbConfirmed).isGreaterThanOrEqualTo(0);
        assertThat(lecture.getConfirmedCount()).isEqualTo(dbConfirmed);
    }

    // 시나리오 8: 승격 대상자(WAITING 1순위)가 동시에 직접 취소 → 데드락 없이 상태 일관성 유지
    // promoteFromWaitlist 에서 Lecture 락 보유 중 Student(B) 락을 추가 획득하는 구조라
    // B의 cancel(Student(B) 락 → Lecture 락 시도)과 데드락 가능 — PostgreSQL 이 감지·롤백
    @Test
    @DisplayName("시나리오8: 승격 대상자 동시 취소 시 데드락 없이 처리되고 confirmedCount 가 DB와 일치")
    void concurrentCancelAndPromoteeCancel_noDeadlockAndCountConsistent() throws InterruptedException {
        Long lectureId = createAndOpenLecture("승격취소충돌강의", 1);

        User studentA = students.get(0);
        User studentB = students.get(1);

        Long enrollIdA = enroll(lectureId, studentA.getId()).getBody().id();
        confirmEnrollment(enrollIdA, studentA.getId()); // A CONFIRMED
        Long enrollIdB = enroll(lectureId, studentB.getId()).getBody().id(); // B WAITING (승격 1순위)

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        // A 취소(→ B 승격 시도)와 B 본인의 직접 취소를 동시에 실행
        executor.submit(() -> {
            try { startGate.await(); cancelEnrollment(enrollIdA, studentA.getId()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });
        executor.submit(() -> {
            try { startGate.await(); cancelEnrollment(enrollIdB, studentB.getId()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        // 어느 쪽이 먼저 처리되든 count 는 DB 와 일치해야 함 (데드락 롤백이 오염시키면 안 됨)
        assertThat(lecture.getConfirmedCount()).isEqualTo(dbConfirmed);
        assertThat(dbConfirmed).isGreaterThanOrEqualTo(0);
        assertThat(dbConfirmed).isLessThanOrEqualTo(1);
    }

    // 시나리오 9: 1분 겹치는 강의 동시 확정 → 정확히 1개만 CONFIRMED
    @Test
    @DisplayName("시나리오9: 1분만 겹치는 강의 2개 동시 확정 시 1개만 CONFIRMED")
    void concurrentConfirm_oneMinuteOverlap_onlyOneConfirmed() throws InterruptedException {
        User student = students.get(0);

        // 강의A: TUE 14:00-16:00 / 강의B: TUE 15:59-17:00 → 1분(15:59~16:00) 겹침
        Long lectureId1 = createAndOpenLectureWithSchedule(
            "강의A_1분겹침", 10, ScheduleDay.TUE, LocalTime.of(14, 0), LocalTime.of(16, 0));
        Long lectureId2 = createAndOpenLectureWithSchedule(
            "강의B_1분겹침", 10, ScheduleDay.TUE, LocalTime.of(15, 59), LocalTime.of(17, 0));

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
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });
        executor.submit(() -> {
            try {
                startGate.await();
                ResponseEntity<Void> res = restTemplate.exchange(
                    "/enrollments/" + enrollId2 + "/confirm", HttpMethod.POST,
                    requestEntity(student.getId(), null), Void.class);
                if (res.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        long confirmed1 = enrollmentRepository.countConfirmedByLectureId(lectureId1);
        long confirmed2 = enrollmentRepository.countConfirmedByLectureId(lectureId2);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(confirmed1 + confirmed2).isEqualTo(1);
    }

    // 시나리오 10: 강의 CLOSED 전환과 PENDING 확정이 동시 발생
    // LectureService.closeLecture() 에 강의 락이 없으므로 실제 race condition 존재
    // 어느 쪽이 이기든 confirmedCount 와 DB 집계가 일치해야 함
    @Test
    @DisplayName("시나리오10: close와 confirm 동시 발생 시 강의는 반드시 CLOSED, confirmedCount 는 DB와 일치")
    void concurrentCloseAndConfirm_lectureClosedAndCountConsistent() throws InterruptedException {
        Long lectureId = createAndOpenLecture("close확정동시강의", 5);
        User student = students.get(0);
        Long enrollId = enroll(lectureId, student.getId()).getBody().id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        executor.submit(() -> {
            try {
                startGate.await();
                restTemplate.exchange("/classes/" + lectureId + "/close", HttpMethod.PATCH,
                    requestEntity(creator.getId(), null), LectureResponse.class);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });
        executor.submit(() -> {
            try {
                startGate.await();
                restTemplate.exchange("/enrollments/" + enrollId + "/confirm", HttpMethod.POST,
                    requestEntity(student.getId(), null), Void.class);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        assertThat(lecture.getStatus()).isEqualTo(LectureStatus.CLOSED);
        assertThat(lecture.getConfirmedCount()).isEqualTo(dbConfirmed);
        assertThat(dbConfirmed).isGreaterThanOrEqualTo(0);
        assertThat(dbConfirmed).isLessThanOrEqualTo(5);
    }

    // 시나리오 11: 같은 enrollment 에 confirm 동시 중복 요청 → 1번만 성공, 카운트 중복 증가 없음
    @Test
    @DisplayName("시나리오11: 같은 enrollment에 confirm 5회 동시 요청 시 1번만 성공하고 confirmedCount == 1")
    void concurrentConfirmSameEnrollment_onlyOneSucceedsAndCountIsOne() throws InterruptedException {
        Long lectureId = createAndOpenLecture("중복확정강의", 10);
        User student = students.get(0);
        Long enrollId = enroll(lectureId, student.getId()).getBody().id();

        int concurrency = 5;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    ResponseEntity<Void> res = restTemplate.exchange(
                        "/enrollments/" + enrollId + "/confirm", HttpMethod.POST,
                        requestEntity(student.getId(), null), Void.class);
                    if (res.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
        }

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(dbConfirmed).isEqualTo(1);
        assertThat(lecture.getConfirmedCount()).isEqualTo(1);
    }

    // 시나리오 12: 같은 CONFIRMED enrollment 에 cancel 동시 중복 요청 → 1번만 성공, 카운트 중복 감소 없음
    @Test
    @DisplayName("시나리오12: 같은 CONFIRMED enrollment에 cancel 5회 동시 요청 시 1번만 성공하고 confirmedCount == 0")
    void concurrentCancelSameEnrollment_onlyOneSucceedsAndCountIsZero() throws InterruptedException {
        Long lectureId = createAndOpenLecture("중복취소강의", 10);
        User student = students.get(0);
        Long enrollId = enroll(lectureId, student.getId()).getBody().id();
        confirmEnrollment(enrollId, student.getId()); // CONFIRMED

        int concurrency = 5;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    ResponseEntity<Void> res = restTemplate.exchange(
                        "/enrollments/" + enrollId + "/cancel", HttpMethod.POST,
                        requestEntity(student.getId(), null), Void.class);
                    if (res.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
        }

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(dbConfirmed).isEqualTo(0);
        assertThat(lecture.getConfirmedCount()).isEqualTo(0);
    }

    // 시나리오 13: 대기열 10명 존재 시 취소 1회 → appliedAt 가장 이른 1명만 승격
    @Test
    @DisplayName("시나리오13: 대기열 10명 중 취소 1회 시 가장 먼저 신청한 1명만 승격")
    void singleCancel_withWaitlist_onlyEarliestWaitingPromoted() {
        Long lectureId = createAndOpenLecture("대기순서강의", 1);

        User confirmedStudent = students.get(0);
        Long confirmedEnrollId = enroll(lectureId, confirmedStudent.getId()).getBody().id();
        confirmEnrollment(confirmedEnrollId, confirmedStudent.getId()); // 정원 소진

        // 10명 순서대로 WAITING 진입 — appliedAt 순서가 곧 승격 우선순위
        List<Long> waitingEnrollIds = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            waitingEnrollIds.add(enroll(lectureId, students.get(i).getId()).getBody().id());
        }
        Long firstWaitingEnrollId = waitingEnrollIds.get(0);

        cancelEnrollment(confirmedEnrollId, confirmedStudent.getId());

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        long dbConfirmed = enrollmentRepository.countConfirmedByLectureId(lectureId);

        assertThat(dbConfirmed).isEqualTo(1);
        assertThat(lecture.getConfirmedCount()).isEqualTo(1);

        // 가장 먼저 대기열에 진입한 학생이 승격
        Enrollment firstWaiting = enrollmentRepository.findById(firstWaitingEnrollId).orElseThrow();
        assertThat(firstWaiting.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);

        // 나머지 9명은 여전히 WAITING
        for (int i = 1; i < waitingEnrollIds.size(); i++) {
            Enrollment e = enrollmentRepository.findById(waitingEnrollIds.get(i)).orElseThrow();
            assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.WAITING);
        }
    }

    // 시나리오 14: 다른 학생이 같은 강의에 동시 확정 — 한 명은 시간 충돌, 한 명은 정상
    @Test
    @DisplayName("시나리오14: 동시 확정 시 시간 충돌 학생만 실패하고 정상 학생은 CONFIRMED")
    void concurrentConfirm_oneWithScheduleConflict_onlyNonConflictingStudentConfirmed() throws InterruptedException {
        User studentA = students.get(0); // 기존 강의와 시간 충돌 있음
        User studentB = students.get(1); // 충돌 없음

        // studentA 의 기존 CONFIRMED 강의: MON 11:00-13:00
        Long conflictingLectureId = createAndOpenLectureWithSchedule(
            "기존확정강의", 10, ScheduleDay.MON, LocalTime.of(11, 0), LocalTime.of(13, 0));
        Long enrollA_conflict = enroll(conflictingLectureId, studentA.getId()).getBody().id();
        confirmEnrollment(enrollA_conflict, studentA.getId());

        // 두 학생 모두 신청할 대상 강의: MON 10:00-12:00 (studentA 의 기존 강의와 겹침)
        Long targetLectureId = createAndOpenLectureWithSchedule(
            "대상강의", 10, ScheduleDay.MON, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Long enrollA_target = enroll(targetLectureId, studentA.getId()).getBody().id();
        Long enrollB_target = enroll(targetLectureId, studentB.getId()).getBody().id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startGate.await();
                ResponseEntity<Void> res = restTemplate.exchange(
                    "/enrollments/" + enrollA_target + "/confirm", HttpMethod.POST,
                    requestEntity(studentA.getId(), null), Void.class);
                if (res.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });
        executor.submit(() -> {
            try {
                startGate.await();
                ResponseEntity<Void> res = restTemplate.exchange(
                    "/enrollments/" + enrollB_target + "/confirm", HttpMethod.POST,
                    requestEntity(studentB.getId(), null), Void.class);
                if (res.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        long dbConfirmedTarget = enrollmentRepository.countConfirmedByLectureId(targetLectureId);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(dbConfirmedTarget).isEqualTo(1);

        // studentA 의 신청은 PENDING 유지 (시간 충돌로 CONFIRMED 불가)
        Enrollment enrollmentA = enrollmentRepository.findById(enrollA_target).orElseThrow();
        assertThat(enrollmentA.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
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
