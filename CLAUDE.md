# 수강 신청 시스템 — Claude 구현 지침서

## 프로젝트 목표

단순 CRUD가 아닌 **동시성 제어**가 핵심이다.
여러 학생이 동시에 같은 강의 마지막 자리를 신청할 때 정원 초과, 중복 신청, 시간 충돌을 애플리케이션과 DB 양쪽에서 반드시 막아야 한다.

---

## 기술 스택

- Java 21, Spring Boot 3.x
- Spring Data JPA (Hibernate)
- PostgreSQL (H2 금지 — 실제 row-level lock 동작 검증 필수)
- Flyway (DB 마이그레이션)
- JUnit 5 + Testcontainers (PostgreSQL 컨테이너로 통합/동시성 테스트)
- Docker Compose (로컬 PostgreSQL 실행)

---

## 프로젝트 구조

```
src/main/java/com/enrollment/
├── domain/           # 엔티티, 도메인 예외, 상태 전이 로직
├── application/      # 서비스, 유스케이스, DTO
├── infrastructure/   # JPA Repository, 락 구현체
└── web/              # Controller, 공통 응답/예외 핸들러
```

---

## 도메인 모델

### 엔티티 목록

#### users (회원)
```
id (PK), name, role (STUDENT | CREATOR)
```
> 테이블명 `users` 사용 — `user`는 PostgreSQL 예약어이므로 충돌 방지

#### lectures (강의)
```
id (PK), creator_id (FK → users), title, description, price,
capacity, confirmed_count, start_date, end_date,
status (DRAFT | OPEN | CLOSED), version
```
- `version`: Optimistic Lock용 (실험/설명 목적 포함)
- Pessimistic Lock이 주요 동시성 전략
> 테이블명 `lectures` 사용 — `class`는 SQL 예약어 및 Java 키워드와 혼동 가능성이 있으므로 사용 금지

#### lecture_schedules (강의 시간표)
```
id (PK), lecture_id (FK → lectures), day_of_week (MON~SUN),
start_time, end_time
```
- 하나의 lectures는 여러 lecture_schedules를 가진다 (OneToMany)
- 같은 강의의 같은 요일 시간 중복 방지 (애플리케이션 레벨 검증)

#### enrollments (수강 신청)
```
id (PK), lecture_id (FK → lectures), student_id (FK → users),
status (PENDING | WAITING | CONFIRMED | CANCELLED),
applied_at, confirmed_at, cancelled_at, version
```
- **UNIQUE constraint**: `(student_id, class_id, status)` where status IN (PENDING, WAITING, CONFIRMED)
  - 또는 partial unique index: `WHERE status != 'CANCELLED'`
- `WAITING`: 대기열 상태 (정원 초과 시 자동 진입)

### 상태 전이

```
강의:       DRAFT → OPEN → CLOSED
수강 신청:  PENDING → CONFIRMED → CANCELLED
            PENDING → CANCELLED
            WAITING → CONFIRMED (취소로 인한 대기열 승격)
            WAITING → CANCELLED
```

CLOSED 전환 시 해당 강의의 모든 PENDING/WAITING Enrollment는 자동 CANCELLED 처리한다.

---

## 핵심 비즈니스 규칙

1. OPEN 상태 강의에만 수강 신청 가능
2. 정원 초과 시 대기열(WAITING)로 진입
3. 결제 확정(PENDING → CONFIRMED) 시 다음을 순서대로 검증:
   - 강의가 OPEN 상태인가
   - confirmedCount < capacity인가
   - 해당 학생의 기존 CONFIRMED 강의와 시간 충돌이 없는가
   - 해당 학생의 중복 CONFIRMED가 없는가
4. CONFIRMED 취소: confirmedAt + 7일 이내, 강의 시작일 이전만 가능
5. PENDING/WAITING 취소: 언제든 가능
6. 학점(credit) 필드는 현재 스코프 외 — 락 전략 설명에만 등장, 구현 제외

---

## 동시성 제어 전략 (핵심)

### 락 순서 — 반드시 아래 순서를 모든 코드 경로에서 동일하게 지킨다

```
1. Student row → Pessimistic Write Lock   (FOR UPDATE)
2. Class row   → Pessimistic Write Lock   (FOR UPDATE)
3. Enrollment 조회
4. PENDING 상태 확인
5. OPEN 상태 확인
6. 시간 충돌 검사 (기존 CONFIRMED 스케줄 vs 신규 스케줄)
7. confirmedCount < capacity 확인
8. Enrollment.status = CONFIRMED
9. Class.confirmedCount += 1
10. commit
```

**규칙:**
- 항상 Student 락을 먼저, Class 락을 나중에 획득
- 여러 Class를 처리할 때: `class_id` 오름차순으로 락 획득
- 여러 Student를 처리할 때: `student_id` 오름차순으로 락 획득
- 서비스 계층에 `acquireStudentLock(studentId)`, `acquireClassLock(classId)` 공통 메서드를 두고 우회 경로를 만들지 않는다

### 대기열 승격 동시성

취소로 `confirmedCount`가 감소하면 WAITING 상태 중 `appliedAt` 가장 이른 1명을 승격한다.
승격 플로우도 동일한 락 순서(Student → Class)를 따른다.

> **트레이드오프 — 동기 승격 vs 비동기 승격**
> - **현재 방식 (동기, 같은 트랜잭션)**: 정합성이 강하고 구현이 단순하다. 단, 취소 트랜잭션이 승격 대상 조회·락 획득·상태 변경까지 포함하므로 트랜잭션이 길어진다. 이 과제 규모(단일 서버, 수백~수천 동시 요청)에서는 적합한 선택이다.
> - **비동기 방식 (Outbox + Event)**: 취소 트랜잭션을 짧게 유지하고 승격을 별도 워커가 처리한다. 운영 환경의 고트래픽 시스템에 적합하지만 구현 복잡도가 높다. 현재 스코프에서는 구현하지 않으며, 향후 확장 시 고려한다.

### Lock Timeout 설정

Spring Boot 3.x는 Jakarta EE 기반이므로 `javax` 네임스페이스 대신 `jakarta`를 사용한다.

```yaml
spring:
  jpa:
    properties:
      jakarta.persistence.lock.timeout: 3000  # 3초 (Spring Boot 3 / Jakarta EE 9+)
```

> `javax.persistence.lock.timeout`은 Spring Boot 2.x / Java EE 환경에서 사용하던 구버전 키다. Spring Boot 3에서는 무시되므로 반드시 `jakarta`로 작성할 것.

`LockTimeoutException` 발생 시 → 409 Conflict 응답

### DB Unique Constraint (최종 방어선)

```sql
-- 활성 신청 중복 방지
CREATE UNIQUE INDEX uq_enrollment_active
  ON enrollment (student_id, class_id)
  WHERE status IN ('PENDING', 'WAITING', 'CONFIRMED');
```

---

## API 설계

### 인증

모든 요청에 `X-User-Id` 헤더 필수. 역할(Role) 확인:
- `POST /classes`: CREATOR만 가능
- `PATCH /classes/{id}/open|close`: 해당 강의의 creatorId와 일치하는 CREATOR만 가능
- `GET /classes/{id}/enrollments`: 해당 강의 CREATOR 또는 ADMIN만 가능
- 나머지 수강 신청 API: STUDENT만 가능

### 강의

```
POST   /classes                              강의 등록 (CREATOR)
PATCH  /classes/{classId}/open              DRAFT → OPEN
PATCH  /classes/{classId}/close             OPEN → CLOSED
GET    /classes?status=OPEN&page=0&size=20  목록 조회
GET    /classes/{classId}                    상세 조회 (confirmedCount 포함)
```

### 수강 신청

```
POST   /classes/{classId}/enrollments            수강 신청 (PENDING 또는 WAITING)
POST   /enrollments/{enrollmentId}/confirm       결제 확정 (PENDING → CONFIRMED)
POST   /enrollments/{enrollmentId}/cancel        취소
GET    /me/enrollments?page=0&size=20            내 신청 목록
GET    /classes/{classId}/enrollments            강의별 수강생 목록 (CREATOR용)
GET    /classes/{classId}/waitlist               대기열 목록 및 내 순서
```

### 공통 응답 형식

```json
// 성공 (목록)
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8
}

// 에러
{
  "code": "ENROLLMENT_CONFLICT",
  "message": "이미 활성 수강 신청이 존재합니다."
}
```

### 주요 HTTP 상태코드

| 상황 | 코드 |
|------|------|
| 정원 초과로 대기열 진입 성공 | 201 Created (status: WAITING) |
| 중복 신청 | 409 Conflict |
| 시간 충돌 | 409 Conflict |
| 존재하지 않는 리소스 | 404 Not Found |
| 권한 없음 | 403 Forbidden |
| 취소 기간 초과 | 422 Unprocessable Entity |
| Lock Timeout | 409 Conflict |

---

## DB 마이그레이션 (Flyway)

```
src/main/resources/db/migration/
├── V1__create_users.sql
├── V2__create_classes.sql
├── V3__create_class_schedules.sql
├── V4__create_enrollments.sql
└── V5__create_indexes.sql
```

### 필수 인덱스

```sql
-- V5__create_indexes.sql
CREATE INDEX idx_lecture_status ON lectures(status);
CREATE INDEX idx_enrollment_student ON enrollments(student_id);
CREATE INDEX idx_enrollment_lecture ON enrollments(lecture_id);
CREATE UNIQUE INDEX uq_enrollment_active
  ON enrollments(student_id, lecture_id)
  WHERE status IN ('PENDING', 'WAITING', 'CONFIRMED');
```

---

## 테스트 전략

### 단위 테스트 (DB 없음)

- 강의 상태 전이: DRAFT→OPEN→CLOSED, 역방향 시 예외
- 신청 상태 전이: 허용/금지 경로 전체
- 취소 가능 기간: confirmedAt 기준 7일 경계값
- 시간 충돌 계산: 겹침/비겹침/경계(정확히 끝나는 시각)

### 통합 테스트 (Testcontainers PostgreSQL)

- 강의 CRUD 및 상태 전이
- 수강 신청 전체 플로우
- 취소 → 대기열 승격 플로우
- 페이지네이션 응답 구조 검증

### 동시성 테스트 (Testcontainers PostgreSQL)

```java
// 필수 시나리오
// 1. 정원 1명, N명 동시 결제 확정 → confirmedCount == 1 보장
// 2. 정원 N명, N+K명 동시 요청 → confirmedCount == N 보장
// 3. 같은 학생이 시간 겹치는 두 강의 동시 확정 → 1개만 성공
// 4. 같은 학생 같은 강의 중복 신청 → 1개만 활성
// 5. 취소 동시 + 대기열 승격 → 정확히 1명만 승격
```

**검증 기준:**
- `confirmedCount <= capacity` (DB에서 직접 조회)
- `enrollment` 테이블에 활성 중복 없음
- 시간 충돌인 CONFIRMED가 같은 학생에 없음

---

## 대량 데이터 생성기

단순 SQL seed 금지. `DataSeeder` 클래스를 두고 환경별로 규모 조절:

```java
// 생성 목표
// - 학생 10,000명 이상
// - 강사 100명 이상
// - 강좌 500개 이상 (다양한 시간표, 타이트한 정원 포함)
// - 의도적인 시간 충돌 관계, 취소/대기 상태 혼합 데이터

@Profile("dev")
@Component
public class DataSeeder implements ApplicationRunner { ... }
```

---

## 예외 처리

`@ControllerAdvice` 글로벌 핸들러 필수:

정원 초과 시 대기열 진입은 **실패가 아니라 정상 흐름**이다. 예외로 처리하지 않고 서비스 레이어에서 `EnrollmentResult` 같은 결과 객체를 반환하여 컨트롤러가 상태에 따라 응답 코드를 결정한다.

```java
// 수강 신청 결과를 표현하는 값 객체 — 예외 없이 정상 흐름으로 대기열 진입을 표현
public record EnrollmentResult(Enrollment enrollment, boolean waitlisted) {}

// 컨트롤러에서 응답 코드 분기
EnrollmentResult result = enrollmentService.enroll(lectureId, studentId);
return result.waitlisted()
    ? ResponseEntity.status(201).body(result.enrollment())  // 대기열 진입
    : ResponseEntity.status(201).body(result.enrollment()); // 즉시 PENDING
```

`@ControllerAdvice` 글로벌 핸들러 — 진짜 오류 상황만 예외로 처리:

```java
EnrollmentException (base)
├── DuplicateEnrollmentException       → 409  (이미 활성 신청 존재)
├── ScheduleConflictException          → 409  (시간 충돌)
├── CancellationPeriodExpiredException → 422  (취소 기간 초과)
├── InvalidStatusTransitionException   → 422  (허용되지 않는 상태 전이)
└── LectureNotOpenException            → 409  (OPEN 상태 아님)
```

> `CapacityExceededException`은 제거. 정원 초과 → 대기열 진입은 예외가 아닌 정상 비즈니스 흐름이므로 결과 객체로 표현한다.

---

## 커넥션 풀 설정

동시성 테스트에서 커넥션 고갈이 가장 흔한 장애 원인이다.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 5000
      leak-detection-threshold: 10000
```

---

## Docker Compose

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: enrollment
      POSTGRES_USER: app
      POSTGRES_PASSWORD: app
    ports:
      - "5432:5432"
```

---

## Git/PR 규칙

> **커밋 메시지, PR 제목/본문 작성, 브랜치 전략, 금지 사항의 상세 규칙은 [GIT_CONVENTION.md](./GIT_CONVENTION.md)를 참조한다.**
> 커밋 생성 또는 PR 작성 전에 반드시 해당 문서를 먼저 읽을 것.

**브랜치:** `main` ← `codex/feature-enrollment-system`

**커밋 형식 요약 (Conventional Commits — 상세는 GIT_CONVENTION.md §2):**
```
feat(lecture): add lecture status transition
feat(enrollment): add pessimistic lock for payment confirmation
test(concurrency): add concurrent enrollment test for capacity=1 case
docs: document locking strategy and lock ordering rules
```

**PR 단위 (상세 템플릿은 GIT_CONVENTION.md §3):**
1. 프로젝트 세팅 + 도메인 모델 + Flyway 마이그레이션
2. 강의 API (CRUD + 상태 전이)
3. 수강 신청/결제 확정/취소 API + 동시성 제어
4. 동시성 테스트 + 대기열 + 대량 데이터 생성기

**주석 규칙 (인수인계 기준):**

처음 보는 사람이 코드를 읽고 동작을 이해할 수 있도록 기능 단위로 주석을 작성한다.

- **클래스 상단**: 이 클래스가 무엇을 책임지는지 한 줄 설명
  ```java
  // 수강 신청 결제 확정 처리. Student → Class 순서로 락을 잡아 동시성 충돌을 방지한다.
  @Service
  public class EnrollmentConfirmService { ... }
  ```
- **메서드 상단**: 동작 흐름과 핵심 제약을 서술. 특히 락 획득, 상태 전이, 검증 순서가 있는 메서드는 반드시 작성
  ```java
  // 결제 확정 순서: Student 락 → Class 락 → 시간 충돌 검사 → 정원 확인 → 상태 변경
  // 락 순서 변경 시 데드락 발생 가능 — 절대 바꾸지 말 것
  public void confirm(Long enrollmentId, Long studentId) { ... }
  ```
- **비즈니스 규칙 인라인 주석**: 숫자 상수, 조건 분기, DB 쿼리에 이유가 있으면 바로 옆에 작성
  ```java
  if (ChronoUnit.DAYS.between(enrollment.getConfirmedAt(), now) > 7) { // 결제 후 7일 초과 시 취소 불가
  ```
- **락 관련 코드**: 락을 거는 이유와 보호 대상을 명시
  ```java
  // Class row 락: confirmedCount 동시 증가로 인한 정원 초과 방지
  classRepository.findByIdWithLock(classId);
  ```
- **DB 마이그레이션 파일**: 각 SQL 파일 상단에 변경 목적과 영향 범위 주석
  ```sql
  -- 활성 수강 신청(PENDING/WAITING/CONFIRMED) 중복 방지용 partial unique index
  -- CANCELLED는 제외하여 재신청이 가능하도록 함
  CREATE UNIQUE INDEX uq_enrollment_active ...
  ```
- **테스트 메서드**: 시나리오 설명 + 기대 결과를 첫 줄에
  ```java
  // 시나리오: 정원 1명인 강의에 3명이 동시에 결제 확정 → 정확히 1명만 CONFIRMED
  @Test void concurrentConfirm_onlyOneSucceeds() { ... }
  ```

**주석을 쓰지 않아도 되는 경우:**
- 메서드 이름만으로 동작이 명확한 단순 getter/setter
- 표준 CRUD 흐름에서 특이사항이 없는 코드

**Merge 전 체크리스트:**
- [ ] build 성공
- [ ] unit test 전체 통과
- [ ] integration test 전체 통과
- [ ] concurrency test 전체 통과 (confirmedCount <= capacity 검증 포함)
- [ ] 락 획득 메서드, 상태 전이 메서드, 동시성 관련 코드에 주석 존재 확인
- [ ] generated file 제외 (.gitignore 확인)

---

## 구현 시 주의사항 요약

1. **락 순서를 절대 바꾸지 마라** — Student 락 먼저, Class 락 나중. 모든 서비스 메서드에서 동일.
2. **DB unique constraint가 최후 방어선** — 애플리케이션 검증만 믿지 않는다.
3. **H2 사용 금지** — Pessimistic lock, partial unique index 동작이 다르다.
4. **Testcontainers로 동시성 테스트** — `ExecutorService` + `CountDownLatch` 패턴 사용.
5. **대기열 승격도 동일한 락 순서** — 취소 트랜잭션 안에서 승격까지 완료.
6. **`confirmedCount`는 DB에서 직접 읽어 검증** — 캐시된 값 신뢰 금지.
