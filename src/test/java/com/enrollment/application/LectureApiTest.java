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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 강의 CRUD 및 상태 전이 통합 테스트 (Testcontainers PostgreSQL)
@DisplayName("강의 API 통합 테스트")
class LectureApiTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired UserRepository userRepository;
    @Autowired LectureRepository lectureRepository;
    @Autowired EnrollmentRepository enrollmentRepository;

    private User creator;
    private User student;
    private User otherCreator;

    @BeforeEach
    void setUp() {
        enrollmentRepository.deleteAll();
        lectureRepository.deleteAll();
        userRepository.deleteAll();

        creator = userRepository.save(new User("강사A", UserRole.CREATOR));
        student = userRepository.save(new User("학생A", UserRole.STUDENT));
        otherCreator = userRepository.save(new User("강사B", UserRole.CREATOR));
    }

    // ─── 강의 등록 ───────────────────────────────────────────────

    @Test
    @DisplayName("CREATOR 가 강의를 등록하면 201 과 DRAFT 상태 강의를 반환한다")
    void createLecture_byCreator_returns201() {
        ResponseEntity<LectureResponse> response = restTemplate.exchange(
            "/classes", HttpMethod.POST,
            requestEntity(creator.getId(), buildCreateRequest("Java 기초", 3)),
            LectureResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().title()).isEqualTo("Java 기초");
        assertThat(response.getBody().status()).isEqualTo(LectureStatus.DRAFT);
        assertThat(response.getBody().schedules()).hasSize(1);
    }

    @Test
    @DisplayName("STUDENT 가 강의를 등록하면 403 을 반환한다")
    void createLecture_byStudent_returns403() {
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/classes", HttpMethod.POST,
            requestEntity(student.getId(), buildCreateRequest("강의", 5)),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("제목 없이 강의 등록 요청 시 400 을 반환한다")
    void createLecture_blankTitle_returns400() {
        CreateLectureRequest request = new CreateLectureRequest(
            "", null, 10000, 30, null, null, List.of()
        );
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/classes", HttpMethod.POST,
            requestEntity(creator.getId(), request),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── 강의 OPEN 전환 ───────────────────────────────────────────

    @Test
    @DisplayName("CREATOR 가 DRAFT 강의를 OPEN 으로 전환하면 200 과 OPEN 상태를 반환한다")
    void openLecture_byCreator_returns200() {
        Long lectureId = createDraftLecture(creator.getId());

        ResponseEntity<LectureResponse> response = restTemplate.exchange(
            "/classes/" + lectureId + "/open", HttpMethod.PATCH,
            requestEntity(creator.getId(), null),
            LectureResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(LectureStatus.OPEN);
    }

    @Test
    @DisplayName("다른 CREATOR 가 OPEN 전환 시 403 을 반환한다")
    void openLecture_byOtherCreator_returns403() {
        Long lectureId = createDraftLecture(creator.getId());

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/classes/" + lectureId + "/open", HttpMethod.PATCH,
            requestEntity(otherCreator.getId(), null),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("이미 OPEN 인 강의에 open 요청 시 422 를 반환한다")
    void openLecture_alreadyOpen_returns422() {
        Long lectureId = createDraftLecture(creator.getId());
        openLecture(lectureId, creator.getId());

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/classes/" + lectureId + "/open", HttpMethod.PATCH,
            requestEntity(creator.getId(), null),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ─── 강의 CLOSED 전환 ─────────────────────────────────────────

    @Test
    @DisplayName("OPEN 강의를 CLOSED 로 전환하면 200 과 CLOSED 상태를 반환한다")
    void closeLecture_fromOpen_returns200() {
        Long lectureId = createDraftLecture(creator.getId());
        openLecture(lectureId, creator.getId());

        ResponseEntity<LectureResponse> response = restTemplate.exchange(
            "/classes/" + lectureId + "/close", HttpMethod.PATCH,
            requestEntity(creator.getId(), null),
            LectureResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(LectureStatus.CLOSED);
    }

    @Test
    @DisplayName("DRAFT 강의를 CLOSED 로 전환하면 422 를 반환한다")
    void closeLecture_fromDraft_returns422() {
        Long lectureId = createDraftLecture(creator.getId());

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/classes/" + lectureId + "/close", HttpMethod.PATCH,
            requestEntity(creator.getId(), null),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ─── 강의 목록 조회 ───────────────────────────────────────────

    @Test
    @DisplayName("status=OPEN 으로 목록 조회 시 OPEN 강의만 페이지네이션으로 반환한다")
    void getLectures_byStatusOpen_returnsPaginatedList() {
        Long lectureId = createDraftLecture(creator.getId());
        openLecture(lectureId, creator.getId());
        createDraftLecture(creator.getId()); // DRAFT — 미포함

        ResponseEntity<PageResponse<LectureSummaryResponse>> response = restTemplate.exchange(
            "/classes?status=OPEN&page=0&size=20", HttpMethod.GET,
            requestEntity(creator.getId(), null),
            new ParameterizedTypeReference<PageResponse<LectureSummaryResponse>>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).hasSize(1);
        assertThat(response.getBody().totalElements()).isEqualTo(1);
    }

    // ─── 강의 상세 조회 ───────────────────────────────────────────

    @Test
    @DisplayName("강의 상세 조회 시 schedules 포함 응답을 반환한다")
    void getLecture_exists_returnsDetailWithSchedules() {
        Long lectureId = createDraftLecture(creator.getId());

        ResponseEntity<LectureResponse> response = restTemplate.exchange(
            "/classes/" + lectureId, HttpMethod.GET,
            requestEntity(creator.getId(), null),
            LectureResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().schedules()).hasSize(1);
        assertThat(response.getBody().schedules().get(0).dayOfWeek()).isEqualTo(ScheduleDay.MON);
    }

    @Test
    @DisplayName("존재하지 않는 강의 상세 조회 시 404 를 반환한다")
    void getLecture_notFound_returns404() {
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/classes/99999", HttpMethod.GET,
            requestEntity(creator.getId(), null),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────

    private <T> HttpEntity<T> requestEntity(Long userId, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private CreateLectureRequest buildCreateRequest(String title, int capacity) {
        LectureScheduleRequest schedule = new LectureScheduleRequest(
            ScheduleDay.MON, LocalTime.of(9, 0), LocalTime.of(11, 0)
        );
        return new CreateLectureRequest(
            title, "설명", 50000, capacity,
            LocalDate.now().plusDays(7), LocalDate.now().plusDays(30),
            List.of(schedule)
        );
    }

    private Long createDraftLecture(Long userId) {
        ResponseEntity<LectureResponse> response = restTemplate.exchange(
            "/classes", HttpMethod.POST,
            requestEntity(userId, buildCreateRequest("강의 " + System.nanoTime(), 10)),
            LectureResponse.class
        );
        return response.getBody().id();
    }

    private void openLecture(Long lectureId, Long userId) {
        restTemplate.exchange(
            "/classes/" + lectureId + "/open", HttpMethod.PATCH,
            requestEntity(userId, null),
            LectureResponse.class
        );
    }
}
