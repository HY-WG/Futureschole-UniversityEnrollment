package com.enrollment.application;

import com.enrollment.AbstractIntegrationTest;
import com.enrollment.application.dto.*;
import com.enrollment.domain.*;
import com.enrollment.infrastructure.EnrollmentRepository;
import com.enrollment.infrastructure.LectureRepository;
import com.enrollment.infrastructure.UserRepository;
import com.enrollment.web.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 수강 신청 전체 플로우 통합 테스트 (Testcontainers PostgreSQL)
@DisplayName("수강 신청 API 통합 테스트")
class EnrollmentApiTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired UserRepository userRepository;
    @Autowired LectureRepository lectureRepository;
    @Autowired EnrollmentRepository enrollmentRepository;

    private User creator;
    private User studentA;
    private User studentB;
    private Long openLectureId;   // 정원 3
    private Long fullLectureId;   // 정원 1 (미리 1명 CONFIRMED)

    @BeforeEach
    void setUp() {
        enrollmentRepository.deleteAll();
        lectureRepository.deleteAll();
        userRepository.deleteAll();

        creator  = userRepository.save(new User("강사A", UserRole.CREATOR));
        studentA = userRepository.save(new User("학생A", UserRole.STUDENT));
        studentB = userRepository.save(new User("학생B", UserRole.STUDENT));

        openLectureId = createAndOpenLecture("Java 기초", 3);
        fullLectureId = createAndOpenLecture("정원1 강의", 1);
    }

    // ─── 수강 신청 ────────────────────────────────────────────────

    @Test
    @DisplayName("정원 여유 있는 강의에 신청하면 201 과 PENDING 상태를 반환한다")
    void enroll_withCapacity_returnsPending() {
        ResponseEntity<EnrollmentResponse> res = enroll(openLectureId, studentA.getId());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().status()).isEqualTo(EnrollmentStatus.PENDING);
    }

    @Test
    @DisplayName("정원 초과 강의에 신청하면 201 과 WAITING 상태를 반환한다")
    void enroll_capacityFull_returnsWaiting() {
        // 정원 1명짜리 강의에 studentA 가 먼저 PENDING
        enroll(fullLectureId, studentA.getId());
        // studentB 는 WAITING
        ResponseEntity<EnrollmentResponse> res = enroll(fullLectureId, studentB.getId());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().status()).isEqualTo(EnrollmentStatus.WAITING);
    }

    @Test
    @DisplayName("같은 강의에 중복 신청하면 409 를 반환한다")
    void enroll_duplicate_returns409() {
        enroll(openLectureId, studentA.getId());
        ResponseEntity<ErrorResponse> res = restTemplate.exchange(
            "/classes/" + openLectureId + "/enrollments", HttpMethod.POST,
            requestEntity(studentA.getId(), null), ErrorResponse.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("CLOSED 강의에 신청하면 409 를 반환한다")
    void enroll_closedLecture_returns409() {
        closeLecture(openLectureId);
        ResponseEntity<ErrorResponse> res = restTemplate.exchange(
            "/classes/" + openLectureId + "/enrollments", HttpMethod.POST,
            requestEntity(studentA.getId(), null), ErrorResponse.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ─── 결제 확정 ───────────────────────────────────────────────

    @Test
    @DisplayName("PENDING 수강 신청을 확정하면 200 을 반환하고 confirmedCount 가 증가한다")
    void confirm_pending_returns200AndIncrementsCount() {
        Long enrollmentId = enroll(openLectureId, studentA.getId()).getBody().id();

        ResponseEntity<Void> res = restTemplate.exchange(
            "/enrollments/" + enrollmentId + "/confirm", HttpMethod.POST,
            requestEntity(studentA.getId(), null), Void.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        LectureResponse lecture = getLecture(openLectureId);
        assertThat(lecture.confirmedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("타인의 수강 신청을 확정하면 403 을 반환한다")
    void confirm_otherStudent_returns403() {
        Long enrollmentId = enroll(openLectureId, studentA.getId()).getBody().id();

        ResponseEntity<ErrorResponse> res = restTemplate.exchange(
            "/enrollments/" + enrollmentId + "/confirm", HttpMethod.POST,
            requestEntity(studentB.getId(), null), ErrorResponse.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─── 취소 → 대기열 승격 플로우 ───────────────────────────────

    @Test
    @DisplayName("시나리오: CONFIRMED 취소 시 WAITING 학생이 자동 승격된다")
    void cancel_confirmedAndPromoteWaiting() {
        // studentA: PENDING → CONFIRMED
        Long enrollIdA = enroll(fullLectureId, studentA.getId()).getBody().id();
        confirmEnrollment(enrollIdA, studentA.getId());

        // studentB: WAITING (정원 1명 이미 CONFIRMED)
        Long enrollIdB = enroll(fullLectureId, studentB.getId()).getBody().id();
        assertThat(getEnrollmentStatus(enrollIdB, studentB.getId())).isEqualTo(EnrollmentStatus.WAITING);

        // studentA 취소 → studentB 자동 승격
        cancelEnrollment(enrollIdA, studentA.getId());

        assertThat(getEnrollmentStatus(enrollIdB, studentB.getId())).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(getLecture(fullLectureId).confirmedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PENDING 수강 신청은 언제든 취소 가능하다")
    void cancel_pendingEnrollment_returns200() {
        Long enrollmentId = enroll(openLectureId, studentA.getId()).getBody().id();

        ResponseEntity<Void> res = restTemplate.exchange(
            "/enrollments/" + enrollmentId + "/cancel", HttpMethod.POST,
            requestEntity(studentA.getId(), null), Void.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─── 내 신청 목록 ─────────────────────────────────────────────

    @Test
    @DisplayName("내 수강 신청 목록을 페이지네이션으로 조회한다")
    void getMyEnrollments_returnsPaginated() {
        enroll(openLectureId, studentA.getId());

        ResponseEntity<PageResponse> res = restTemplate.exchange(
            "/me/enrollments?page=0&size=20", HttpMethod.GET,
            requestEntity(studentA.getId(), null), PageResponse.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().totalElements()).isEqualTo(1);
    }

    // ─── 대기열 ──────────────────────────────────────────────────

    @Test
    @DisplayName("대기열 현황 조회 시 totalWaiting 과 내 순번을 반환한다")
    void getWaitlist_returnsPositionInfo() {
        enroll(fullLectureId, studentA.getId()); // PENDING (정원 1)
        enroll(fullLectureId, studentB.getId()); // WAITING

        ResponseEntity<WaitlistResponse> res = restTemplate.exchange(
            "/classes/" + fullLectureId + "/waitlist", HttpMethod.GET,
            requestEntity(studentB.getId(), null), WaitlistResponse.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().totalWaiting()).isEqualTo(1);
        assertThat(res.getBody().myPosition()).isEqualTo(1L);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────

    private <T> HttpEntity<T> requestEntity(Long userId, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Long createAndOpenLecture(String title, int capacity) {
        LectureScheduleRequest schedule = new LectureScheduleRequest(
            ScheduleDay.TUE, LocalTime.of(14, 0), LocalTime.of(16, 0));
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

    private void closeLecture(Long lectureId) {
        restTemplate.exchange("/classes/" + lectureId + "/close", HttpMethod.PATCH,
            requestEntity(creator.getId(), null), LectureResponse.class);
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

    private LectureResponse getLecture(Long lectureId) {
        return restTemplate.getForObject("/classes/" + lectureId, LectureResponse.class);
    }

    private EnrollmentStatus getEnrollmentStatus(Long enrollmentId, Long studentId) {
        ResponseEntity<PageResponse> res = restTemplate.exchange(
            "/me/enrollments?page=0&size=100", HttpMethod.GET,
            requestEntity(studentId, null), PageResponse.class);
        return res.getBody().content().stream()
            .map(e -> EnrollmentStatus.valueOf((String) ((java.util.LinkedHashMap<?, ?>) e).get("status")))
            .filter(s -> true)
            .findFirst()
            .orElseThrow();
    }
}
