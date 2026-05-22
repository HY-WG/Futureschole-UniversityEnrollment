package com.enrollment.domain;

import com.enrollment.domain.exception.InvalidStatusTransitionException;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 강의 엔티티. 테이블명 'lectures' — 'class'는 SQL 예약어 및 Java 키워드와 혼동 가능
// version 필드는 Optimistic Lock 용 (Pessimistic Lock 이 주요 동시성 전략)
@Entity
@Table(name = "lectures")
public class Lecture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int confirmedCount;

    private LocalDate startDate;
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LectureStatus status;

    @Version
    private Long version;

    @OneToMany(mappedBy = "lecture", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LectureSchedule> schedules = new ArrayList<>();

    protected Lecture() {}

    public Lecture(User creator, String title, String description, int price, int capacity,
                   LocalDate startDate, LocalDate endDate) {
        this.creator = creator;
        this.title = title;
        this.description = description;
        this.price = price;
        this.capacity = capacity;
        this.confirmedCount = 0;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = LectureStatus.DRAFT;
    }

    // 상태 전이: DRAFT → OPEN
    public void open() {
        if (this.status != LectureStatus.DRAFT) {
            throw new InvalidStatusTransitionException(
                "OPEN 전환은 DRAFT 상태에서만 가능합니다. 현재 상태: " + this.status);
        }
        this.status = LectureStatus.OPEN;
    }

    // 상태 전이: OPEN → CLOSED
    public void close() {
        if (this.status != LectureStatus.OPEN) {
            throw new InvalidStatusTransitionException(
                "CLOSED 전환은 OPEN 상태에서만 가능합니다. 현재 상태: " + this.status);
        }
        this.status = LectureStatus.CLOSED;
    }

    public boolean isOpen() {
        return this.status == LectureStatus.OPEN;
    }

    // confirmedCount < capacity 이면 수용 가능
    public boolean hasCapacity() {
        return this.confirmedCount < this.capacity;
    }

    // CONFIRMED 처리 시 호출 — 결제 확정 1건당 1 증가
    public void incrementConfirmedCount() {
        this.confirmedCount++;
    }

    // CONFIRMED 취소 시 호출 — 취소 1건당 1 감소
    public void decrementConfirmedCount() {
        if (this.confirmedCount <= 0) {
            throw new IllegalStateException("confirmedCount 는 0 미만이 될 수 없습니다.");
        }
        this.confirmedCount--;
    }

    // 양방향 관계 동기화를 포함한 스케줄 추가
    public void addSchedule(LectureSchedule schedule) {
        schedule.setLecture(this);
        this.schedules.add(schedule);
    }

    public Long getId() { return id; }
    public User getCreator() { return creator; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getPrice() { return price; }
    public int getCapacity() { return capacity; }
    public int getConfirmedCount() { return confirmedCount; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public LectureStatus getStatus() { return status; }
    public Long getVersion() { return version; }
    public List<LectureSchedule> getSchedules() { return Collections.unmodifiableList(schedules); }
}
