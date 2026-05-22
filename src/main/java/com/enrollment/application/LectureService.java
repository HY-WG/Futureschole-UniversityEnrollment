package com.enrollment.application;

import com.enrollment.application.dto.*;
import com.enrollment.application.exception.ForbiddenException;
import com.enrollment.application.exception.ResourceNotFoundException;
import com.enrollment.domain.*;
import com.enrollment.infrastructure.EnrollmentRepository;
import com.enrollment.infrastructure.LectureRepository;
import com.enrollment.infrastructure.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 강의 등록, 상태 전이(DRAFT→OPEN→CLOSED), 조회 처리
@Service
@Transactional(readOnly = true)
public class LectureService {

    private final LectureRepository lectureRepository;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;

    public LectureService(LectureRepository lectureRepository,
                          UserRepository userRepository,
                          EnrollmentRepository enrollmentRepository) {
        this.lectureRepository = lectureRepository;
        this.userRepository = userRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    // 강의 등록 — CREATOR 역할만 가능
    @Transactional
    public LectureResponse createLecture(CreateLectureRequest request, Long creatorId) {
        User creator = userRepository.findById(creatorId)
            .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다: " + creatorId));
        if (creator.getRole() != UserRole.CREATOR) {
            throw new ForbiddenException("강의 등록은 CREATOR 역할만 가능합니다.");
        }

        Lecture lecture = new Lecture(
            creator,
            request.title(),
            request.description(),
            request.price(),
            request.capacity(),
            request.startDate(),
            request.endDate()
        );
        request.schedules().forEach(s ->
            lecture.addSchedule(new LectureSchedule(lecture, s.dayOfWeek(), s.startTime(), s.endTime()))
        );
        return LectureResponse.from(lectureRepository.save(lecture));
    }

    // 강의 OPEN 전환 — 해당 강의의 CREATOR 본인만 가능
    @Transactional
    public LectureResponse openLecture(Long lectureId, Long userId) {
        Lecture lecture = findLectureOrThrow(lectureId);
        validateLectureCreator(lecture, userId);
        lecture.open();
        return LectureResponse.from(lecture);
    }

    // 강의 CLOSED 전환 — CLOSED 시 해당 강의의 PENDING/WAITING 수강 신청을 일괄 CANCELLED 처리
    @Transactional
    public LectureResponse closeLecture(Long lectureId, Long userId) {
        Lecture lecture = findLectureOrThrow(lectureId);
        validateLectureCreator(lecture, userId);
        lecture.close();
        enrollmentRepository.cancelPendingAndWaitingByLectureId(lectureId, LocalDateTime.now());
        return LectureResponse.from(lecture);
    }

    // 강의 목록 — 상태 필터 + 페이지네이션 (schedules 미포함)
    public PageResponse<LectureSummaryResponse> getLectures(LectureStatus status, Pageable pageable) {
        Page<Lecture> lectures = (status != null)
            ? lectureRepository.findByStatusWithCreator(status, pageable)
            : lectureRepository.findAllWithCreator(pageable);
        return PageResponse.from(lectures.map(LectureSummaryResponse::from));
    }

    // 강의 상세 — creator, schedules 포함
    public LectureResponse getLecture(Long lectureId) {
        Lecture lecture = lectureRepository.findByIdWithSchedules(lectureId)
            .orElseThrow(() -> new ResourceNotFoundException("강의를 찾을 수 없습니다: " + lectureId));
        return LectureResponse.from(lecture);
    }

    private Lecture findLectureOrThrow(Long lectureId) {
        return lectureRepository.findByIdWithSchedules(lectureId)
            .orElseThrow(() -> new ResourceNotFoundException("강의를 찾을 수 없습니다: " + lectureId));
    }

    // 강의 CREATOR 본인 여부 및 CREATOR 역할 확인
    private void validateLectureCreator(Lecture lecture, Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다: " + userId));
        if (user.getRole() != UserRole.CREATOR) {
            throw new ForbiddenException("CREATOR 역할만 강의 상태를 변경할 수 있습니다.");
        }
        if (!lecture.getCreator().getId().equals(userId)) {
            throw new ForbiddenException("해당 강의의 등록자만 상태를 변경할 수 있습니다.");
        }
    }
}
