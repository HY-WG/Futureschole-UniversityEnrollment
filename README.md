# 수강 신청 시스템 (University Enrollment)

## 프로젝트 개요

여러 학생이 동시에 같은 강의의 마지막 자리를 신청할 때 발생하는 **정원 초과·중복 신청·시간 충돌**을 방지하는 수강 신청 시스템입니다.  
**Pessimistic Lock 기반 동시성 제어**가 핵심이며, 애플리케이션 레벨과 DB 레벨 양쪽에서 정합성을 보장합니다.

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 3.2.5 |
| ORM | Spring Data JPA (Hibernate 6.x) |
| DB | PostgreSQL 16 |
| 마이그레이션 | Flyway 9.22.3 |
| 빌드 | Gradle 8.7 |
| 테스트 | JUnit 5 + Testcontainers 1.19.7 |
| 컨테이너 | Docker / Docker Compose |

---

## 실행 방법

### 사전 요구사항
- Java 21+
- Docker Desktop (가상화 활성화 필요)

### 로컬 DB 실행

```bash
docker-compose up -d
```

### 애플리케이션 실행

```bash
./gradlew bootRun
```

기본 포트: `http://localhost:8080`

### 개발 환경 실행 (DataSeeder 포함)

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

`dev` 프로파일 기동 시 강사 100명, 학생 10,000명, 강좌 500개가 자동 생성됩니다.

---

## 요구사항 해석 및 가정

| 항목 | 해석 및 가정 |
|------|-------------|
| **인증** | 별도 인증 서버 없음. 모든 요청에 `X-User-Id` 헤더로 사용자 식별 |
| **역할** | `STUDENT` (수강 신청) / `CREATOR` (강의 등록·관리) 두 종류만 존재 |
| **결제** | 실제 결제 시스템 없음. `PENDING → CONFIRMED` 전환이 결제 확정을 의미 |
| **정원** | `confirmedCount`(결제 확정 인원)만 정원 소진으로 계산. PENDING 상태는 정원을 점유하지 않음 |
| **대기열** | 정원 초과 시 WAITING 진입. 취소 발생 시 `appliedAt` 기준 선착순 1명 자동 승격 |
| **취소 조건** | CONFIRMED 취소: 확정 후 7일 이내 + 강의 시작일 이전. PENDING/WAITING 취소: 제한 없음 |
| **시간 충돌** | 동일 요일에 시간이 겹치는 강의는 동시 CONFIRMED 불가. 경계(끝 시각 = 시작 시각)는 충돌 없음으로 처리 |
| **학점(credit)** | 요구사항에 등장하지만 현재 스코프 외 — 구현 제외 |

---

## 설계 결정과 이유

### 1. Pessimistic Lock (비관적 락) 전략

낙관적 락(Optimistic Lock) 단독 사용 시 동시 요청이 많으면 재시도 폭주(retry storm) 가능성이 있습니다.  
이 시스템은 **정원 소진 직전** 경쟁이 집중되므로 Pessimistic Write Lock(`SELECT FOR UPDATE`)으로 직렬화합니다.

`@Version` 필드는 Lecture 엔티티에 남겨두되, 실제 충돌 방어는 Pessimistic Lock이 담당합니다.

### 2. 락 순서: Student → Lecture (데드락 방지)

```
1. Student row  FOR UPDATE  (락 순서 1단계)
2. Lecture row  FOR UPDATE  (락 순서 2단계)
```

모든 쓰기 경로(confirm, cancel, 대기열 승격)에서 반드시 이 순서를 지킵니다.  
순서가 뒤바뀌면 두 트랜잭션이 서로를 기다리는 데드락이 발생합니다.

### 3. JOIN FETCH를 락 쿼리에서 분리

`JOIN FETCH + PESSIMISTIC_WRITE` 혼용 시 Hibernate가 **follow-on locking**(HHH000444)으로 폴백합니다.  
초기 SELECT와 별도의 FOR UPDATE 사이에 다른 트랜잭션이 version을 변경하면 `ObjectOptimisticLockingFailureException`이 발생합니다.  
이를 방지하기 위해 락 쿼리는 `SELECT l FROM Lecture l WHERE l.id = :id`로 단순화하고, schedules는 트랜잭션 내 lazy load로 접근합니다.

### 4. 대기열 승격: 동기 처리 (같은 트랜잭션)

취소 트랜잭션 안에서 승격까지 완료합니다.  
정합성이 강하고 구현이 단순하다는 장점이 있으나, 취소 트랜잭션이 길어집니다.  
고트래픽 운영 환경에서는 Outbox + 비동기 워커 방식으로 전환을 고려할 수 있습니다.

### 5. DB Unique Constraint (최후 방어선)

```sql
CREATE UNIQUE INDEX uq_enrollment_active
  ON enrollments(student_id, lecture_id)
  WHERE status IN ('PENDING', 'WAITING', 'CONFIRMED');
```

애플리케이션 레벨 중복 체크를 통과한 동시 요청도 DB 제약이 차단합니다.  
`DataIntegrityViolationException` → 409 Conflict로 매핑합니다.

### 6. 정원 초과 = 예외가 아닌 정상 흐름

정원 초과 시 대기열 진입은 실패가 아니라 정상 비즈니스 흐름입니다.  
`CapacityExceededException` 없이 `EnrollmentResult(enrollment, waitlisted)` 값 객체로 구분합니다.

---

## 미구현 / 제약사항

| 항목 | 내용 |
|------|------|
| **인증/인가** | JWT, OAuth 등 실제 인증 없음. `X-User-Id` 헤더를 신뢰 |
| **학점(credit)** | 요구사항 문서에 언급되나 현재 스코프에서 제외 |
| **비동기 대기열 승격** | 현재 동기 방식. 대용량 트래픽 환경에서는 Outbox + 이벤트 기반으로 전환 권장 |
| **알림** | 대기열 승격 시 학생 알림 기능 없음 |
| **다중 서버** | 단일 서버 전제. 분산 환경에서는 분산 락(Redis Redlock 등) 추가 필요 |
| **강의 수정** | OPEN 이후 정원·시간표 변경 API 없음 |

---

## AI 활용 범위

이 프로젝트는 **Claude Code (claude-sonnet-4-6)** 를 이용해 전체 구현을 진행했습니다.

- 전체 코드 구조 및 도메인 모델 설계
- API 구현 (강의, 수강 신청, 결제 확정, 취소, 대기열)
- Pessimistic Lock 동시성 제어 로직
- Flyway 마이그레이션 스크립트
- 통합 테스트 및 동시성 테스트 작성
- 테스트 인프라 구성 (Testcontainers 싱글턴 패턴, Apache HttpClient 교체)
- DataSeeder (대량 데이터 생성기)
- 버그 수정 (follow-on locking, Testcontainers 포트 불일치, PATCH 미지원)

**개발자의 역할**: 요구사항 정의(`CLAUDE.md`), 기술 방향 결정, 각 단계 코드 리뷰 및 승인, 테스트 실행 환경 구성(BIOS 가상화 활성화 포함)

---

## API 목록 및 예시

모든 요청에 `X-User-Id: {userId}` 헤더 필수.

### 강의 API

| 메서드 | 경로 | 설명 | 권한 |
|--------|------|------|------|
| `POST` | `/classes` | 강의 등록 | CREATOR |
| `PATCH` | `/classes/{classId}/open` | DRAFT → OPEN | 해당 강의 CREATOR |
| `PATCH` | `/classes/{classId}/close` | OPEN → CLOSED | 해당 강의 CREATOR |
| `GET` | `/classes?status=OPEN&page=0&size=20` | 강의 목록 | 모두 |
| `GET` | `/classes/{classId}` | 강의 상세 | 모두 |

**강의 등록 요청 예시**
```json
POST /classes
X-User-Id: 1

{
  "title": "Java 기초",
  "description": "자바 입문 강의",
  "price": 50000,
  "capacity": 30,
  "startDate": "2026-07-01",
  "endDate": "2026-08-31",
  "schedules": [
    { "dayOfWeek": "TUE", "startTime": "14:00", "endTime": "16:00" },
    { "dayOfWeek": "THU", "startTime": "14:00", "endTime": "16:00" }
  ]
}
```

### 수강 신청 API

| 메서드 | 경로 | 설명 | 권한 |
|--------|------|------|------|
| `POST` | `/classes/{classId}/enrollments` | 수강 신청 | STUDENT |
| `POST` | `/enrollments/{enrollmentId}/confirm` | 결제 확정 | 본인 STUDENT |
| `POST` | `/enrollments/{enrollmentId}/cancel` | 취소 | 본인 STUDENT |
| `GET` | `/me/enrollments?page=0&size=20` | 내 신청 목록 | STUDENT |
| `GET` | `/classes/{classId}/enrollments` | 강의별 수강생 목록 | 해당 강의 CREATOR |
| `GET` | `/classes/{classId}/waitlist` | 대기열 현황 | STUDENT |

**수강 신청 응답 예시 (정원 여유)**
```json
HTTP 201
{
  "id": 42,
  "lectureId": 7,
  "lectureTitle": "Java 기초",
  "studentId": 100,
  "studentName": "홍길동",
  "status": "PENDING",
  "appliedAt": "2026-05-22T10:00:00"
}
```

**수강 신청 응답 예시 (정원 초과 → 대기)**
```json
HTTP 201
{
  "id": 43,
  "status": "WAITING",
  ...
}
```

**에러 응답 예시**
```json
HTTP 409
{
  "code": "DUPLICATE_ENROLLMENT",
  "message": "이미 활성 수강 신청이 존재합니다."
}
```

### 주요 HTTP 상태코드

| 상황 | 코드 |
|------|------|
| 신청 성공 (PENDING 또는 WAITING) | 201 |
| 중복 신청 / 시간 충돌 / OPEN 아님 / Lock 타임아웃 | 409 |
| 허용되지 않는 상태 전이 / 취소 기간 초과 | 422 |
| 리소스 없음 | 404 |
| 권한 없음 | 403 |
| 헤더 누락 / 유효성 실패 | 400 |

---

## 데이터 모델 설명

### ERD 요약

```
users ──< enrollments >── lectures ──< lecture_schedules
```

### users

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | |
| name | VARCHAR | 이름 |
| role | VARCHAR | `STUDENT` \| `CREATOR` |

> `user`는 PostgreSQL 예약어이므로 테이블명 `users` 사용

### lectures

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | |
| creator_id | BIGINT FK | users 참조 |
| title | VARCHAR | 강의명 |
| price | INT | 수강료 |
| capacity | INT | 정원 |
| confirmed_count | INT | 결제 확정 인원 (정원 소진 기준) |
| status | VARCHAR | `DRAFT` → `OPEN` → `CLOSED` |
| version | BIGINT | Optimistic Lock용 |

> `class`는 SQL 예약어 및 Java 키워드와 혼동 가능하므로 테이블명 `lectures` 사용

### lecture_schedules

| 컬럼 | 타입 | 설명 |
|------|------|------|
| lecture_id | BIGINT FK | ON DELETE CASCADE |
| day_of_week | VARCHAR | `MON`~`SUN` |
| start_time | TIME | |
| end_time | TIME | |

### enrollments

| 컬럼 | 타입 | 설명 |
|------|------|------|
| lecture_id | BIGINT FK | |
| student_id | BIGINT FK | |
| status | VARCHAR | `PENDING` → `CONFIRMED` → `CANCELLED` / `WAITING` → `CONFIRMED` |
| applied_at | TIMESTAMP | 신청 시각 (대기열 순서 기준) |
| confirmed_at | TIMESTAMP | 결제 확정 시각 (취소 가능 기간 계산 기준) |
| version | BIGINT | Optimistic Lock용 |

**Partial Unique Index (핵심 제약)**
```sql
CREATE UNIQUE INDEX uq_enrollment_active
  ON enrollments(student_id, lecture_id)
  WHERE status IN ('PENDING', 'WAITING', 'CONFIRMED');
```
CANCELLED 제외 → 취소 후 재신청 가능

### 상태 전이

```
강의:       DRAFT → OPEN → CLOSED
수강 신청:  PENDING → CONFIRMED → CANCELLED
            PENDING → CANCELLED
            WAITING → CONFIRMED  (대기열 자동 승격)
            WAITING → CANCELLED
```

---

## 테스트 실행 방법

### 사전 요구사항
- Docker Desktop 실행 중 (Testcontainers가 PostgreSQL 컨테이너를 자동 기동)
- BIOS/UEFI에서 가상화(VT-x / AMD-V) 활성화 필요

### 전체 테스트 실행

```bash
./gradlew test
```

**기대 결과: 60개 전체 통과**

| 테스트 클래스 | 종류 | 수량 |
|--------------|------|------|
| `LectureTest`, `LectureScheduleTest`, `EnrollmentTest` | 도메인 단위 | 25개 |
| `LectureApiTest` | 통합 (강의 API) | 11개 |
| `EnrollmentApiTest` | 통합 (수강 신청 API) | 9개 |
| `ConcurrentEnrollmentTest` | 동시성 (14 시나리오) | 14개 |

### 동시성 테스트 시나리오

| # | 내용 | 검증 항목 |
|---|------|-----------|
| 1 | 정원 1명, 10명 동시 확정 | `confirmedCount == 1` |
| 2 | 정원 5명, 10명 동시 확정 | `confirmedCount == 5` |
| 3 | 동일 학생, 시간 충돌 강의 동시 확정 | 1개만 CONFIRMED |
| 4 | 동일 학생, 동일 강의 동시 중복 신청 | 활성 신청 1개 (DB unique) |
| 5 | CONFIRMED 동시 취소 + 대기열 승격 | `confirmedCount ≤ capacity` |
| 6 | confirm과 cancel 동시 발생 (같은 강의) | `confirmedCount`가 DB 집계와 일치, 음수·초과 없음 |
| 7 | 다수 동시 취소 + 대기열 | 승격 인원이 취소 인원 초과 안 함 |
| 8 | 승격 대상자(WAITING 1순위)가 동시에 직접 취소 | 데드락 없이 처리, count 정합성 유지 |
| 11 | 1분만 겹치는 강의 2개 동시 확정 | 경계값 겹침도 감지 → 1개만 CONFIRMED |
| 12 | 강의 close와 학생 confirm 동시 발생 | 강의 반드시 CLOSED, `confirmedCount` DB와 일치 |
| 14 | 같은 enrollment에 confirm 5회 동시 요청 | 1번만 성공, `confirmedCount == 1` |
| 15 | 같은 CONFIRMED enrollment에 cancel 5회 동시 요청 | 1번만 성공, `confirmedCount == 0` |
| 20 | 대기열 10명, 취소 1회 발생 | `appliedAt` 가장 이른 학생만 승격 |
| 22 | 다른 학생 동시 확정, 한 명은 시간 충돌 | 충돌 없는 학생만 CONFIRMED |

---

## AI 사용 내역

| 단계 | 작업 내용 |
|------|-----------|
| PR #1 | 프로젝트 초기 세팅, 도메인 엔티티(User/Lecture/LectureSchedule/Enrollment), Flyway 마이그레이션(V1~V5), 도메인 단위 테스트 |
| PR #2 | 강의 API (CRUD, DRAFT→OPEN→CLOSED 상태 전이), LectureService, 통합 테스트 |
| PR #3 | 수강 신청/결제 확정/취소 API, Pessimistic Lock 동시성 제어, 대기열 승격, GlobalExceptionHandler |
| PR #4 | 동시성 테스트 5종 (ExecutorService + CountDownLatch), DataSeeder (JdbcTemplate 대량 삽입), gradlew 생성 |
| PR #5 | 테스트 버그 수정: follow-on locking 해결, Testcontainers 싱글턴 패턴, Apache HttpClient 교체, 락 타임아웃 분리 |
