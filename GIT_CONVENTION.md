# Git 커밋 & PR 컨벤션

## 1. 브랜치 전략

```
main
└── codex/feature-enrollment-system   # 전체 기능 작업 브랜치
    ├── codex/feat/domain-model        # 도메인 모델 및 마이그레이션
    ├── codex/feat/lecture-api         # 강의 API
    ├── codex/feat/enrollment-api      # 수강 신청/결제/취소 API
    └── codex/feat/concurrency-test    # 동시성 테스트 및 데이터 생성기
```

- `main`에 직접 커밋 금지 — 반드시 PR을 통해 머지
- 브랜치명은 `codex/<type>/<short-description>` 형식 사용
- 작업 완료 후 머지된 브랜치는 삭제

---

## 2. 커밋 메시지 규칙 (Conventional Commits)

### 형식

```
<type>(<scope>): <subject>

[body]

[footer]
```

### type 목록

| type | 사용 시점 |
|------|-----------|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `test` | 테스트 코드 추가/수정 (프로덕션 코드 변경 없음) |
| `refactor` | 기능 변경 없는 코드 구조 개선 |
| `docs` | 문서 추가/수정 (CLAUDE.md, GIT_CONVENTION.md 등) |
| `chore` | 빌드 설정, 의존성 변경, 환경 파일 수정 |
| `perf` | 성능 개선 |
| `style` | 포매팅, 세미콜론 등 코드 의미 변경 없는 수정 |

### scope 예시 (선택)

`lecture`, `enrollment`, `user`, `concurrency`, `migration`, `seed`

### subject 규칙

- 영문 소문자로 시작, 명령형 동사로 작성 (`add`, `fix`, `remove` 등)
- 마침표(`.`) 없음
- 50자 이내

### 작성 예시

```
feat(lecture): add lecture status transition (DRAFT→OPEN→CLOSED)

feat(enrollment): add pessimistic lock for payment confirmation

fix(enrollment): fix deadlock by enforcing student-before-lecture lock order

test(concurrency): add concurrent enrollment test for capacity=1 case

docs: document locking strategy and lock ordering rules

chore: add docker-compose for local PostgreSQL

refactor(enrollment): extract acquireStudentLock into shared lock helper
```

### body 작성 기준

한 줄 subject만으로 의도 전달이 어려울 때 body를 추가한다.
- 무엇을 왜 변경했는지 서술 (어떻게는 코드가 설명)
- 72자 줄바꿈 권장

```
fix(enrollment): fix deadlock by enforcing student-before-lecture lock order

기존 코드는 일부 경로에서 lecture 락을 먼저 획득한 후 student 락을 잡아
데드락이 발생할 수 있었다. 모든 코드 경로에서 student → lecture 순서를
강제하도록 acquireStudentLock / acquireLectureLock 공통 메서드로 통일했다.
```

### footer 작성 기준

- 관련 이슈 번호: `Closes #123`, `Refs #45`
- 하위 호환 불가 변경: `BREAKING CHANGE: <설명>`

---

## 3. PR 규칙

### PR 단위

기능 단위로 작게 나눈다. 한 PR이 너무 크면 리뷰 품질이 떨어진다.

| PR | 포함 범위 |
|----|-----------|
| 1차 | 프로젝트 세팅 + 도메인 모델 + Flyway 마이그레이션 |
| 2차 | 강의 API (등록, 상태 전이, 조회) |
| 3차 | 수강 신청 / 결제 확정 / 취소 API + 동시성 제어 |
| 4차 | 동시성 테스트 + 대기열 기능 + 대량 데이터 생성기 |

### PR 제목

커밋 메시지 subject와 동일한 형식을 따른다.

```
feat(enrollment): add payment confirmation with pessimistic lock
```

### PR 본문 템플릿

```markdown
## 구현 범위
- [ ] 항목 1
- [ ] 항목 2

## 주요 API
| Method | Path | 설명 |
|--------|------|------|
| POST | /lectures | 강의 등록 |

## 동시성 제어 방식
어떤 락을 어떤 순서로 사용했는지 서술.

## 테스트 방법
로컬에서 검증하는 방법을 단계별로 작성.
```shell
./gradlew test
./gradlew test --tests "*ConcurrencyTest*"
```

## 남은 한계 / 선택 구현 여부
- 미구현 항목이 있으면 이유와 함께 명시
- 향후 개선 아이디어가 있으면 간략히 기재
```

### Merge 전 체크리스트

PR을 머지하기 전 아래 항목을 모두 확인한다.

- [ ] `./gradlew build` 성공
- [ ] 단위 테스트 전체 통과
- [ ] 통합 테스트 전체 통과
- [ ] 동시성 테스트 전체 통과 (`confirmedCount <= capacity` DB 직접 검증 포함)
- [ ] 락 획득 메서드, 상태 전이 메서드, 동시성 관련 코드에 주석 존재 확인
- [ ] 불필요한 generated file 미포함 (`.gitignore` 확인)
- [ ] `main`에 직접 커밋 없음

---

## 4. 금지 사항

| 행위 | 이유 |
|------|------|
| `main` 직접 push | 리뷰 없이 변경사항이 반영됨 |
| `git commit --amend` (push 이후) | 공유된 커밋 히스토리 훼손 |
| `git push --force` (main 브랜치) | 다른 작업자 커밋 유실 위험 |
| `--no-verify` 옵션 사용 | pre-commit hook 우회 금지 |
| 커밋 메시지에 "WIP", "fix", "asdf" 등 의미 없는 내용 | 히스토리 추적 불가 |

---

## 5. 커밋 단위 원칙

- **한 커밋 = 한 가지 논리적 변경** — 빌드가 깨지지 않는 상태여야 한다
- 기능 구현과 테스트는 같은 커밋에 포함해도 되고, 바로 다음 커밋으로 나눠도 된다
- 리팩터링은 기능 변경과 분리하여 별도 커밋으로 작성한다
- 마이그레이션 파일은 해당 엔티티 추가 커밋과 함께 포함한다

---

## 6. 로컬 설정 권장사항

```bash
# 커밋 메시지 템플릿 등록 (선택)
git config commit.template .gitmessage

# .gitmessage 파일 예시
# <type>(<scope>): <subject>
#
# [body: 변경 이유]
#
# [footer: Closes #이슈번호]
```
