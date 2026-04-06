# 멀티테넌트 + Lock 테스트 프로젝트 — 시현 및 설명 가이드

---

## 1. 프로젝트 한 줄 요약

> **"멀티테넌트는 데이터 분리, Lock은 데이터 보호"**

SaaS 환경에서 여러 고객사(테넌트)가 하나의 서버를 공유할 때 발생하는 두 가지 핵심 문제를 해결한다.

| 문제 | 해결 전략 |
|------|-----------|
| 테넌트 A의 데이터가 테넌트 B에 노출되면 안 된다 | Schema-per-Tenant (PostgreSQL search_path) |
| 동시에 같은 데이터를 수정하면 데이터가 깨진다 | JPA Lock (Pessimistic / Optimistic) + ShedLock |

---

## 2. 기술 스택

| 구분 | 내용 |
|------|------|
| Backend | Spring Boot |
| ORM | Spring Data JPA + Hibernate 7 |
| DB | PostgreSQL (Supabase) |
| 분산 Lock | ShedLock (net.javacrumbs.shedlock) |
| View | Thymeleaf + Bootstrap 5 |

---

## 3. 서버 시작 방법

```bash
# Gradle (Dev 프로파일 자동 활성화 — application-dev.yml 사용)
./gradlew bootRun
```

> `application.yml`에 `spring.profiles.active: dev` 설정 → Supabase PostgreSQL 연결

앱 시작 시 `DataInitializer`가 자동 실행되어 샘플 데이터 삽입:
- `public.TB_FORM_DEFINITION` — townboard 3건, joongang 3건
- `townboard.TB_CONTENT` — 광고 콘텐츠 3건 (READY 상태)
- `joongang.TB_CONTENT` — 뉴스 콘텐츠 3건 (READY 상태)
- 이미 데이터가 있으면 자동 스킵

접속 URL: `http://localhost:8080`

---

## 4. 멀티테넌트 구조 설명

### 4-1. 테넌트 식별 — `TenantInterceptor`

HTTP 요청이 들어오면 가장 먼저 `TenantInterceptor.preHandle()`이 실행된다.
아래 우선순위로 tenantId를 추출해 `TenantContext`(ThreadLocal)에 저장한다.

```
우선순위:
  1. 서브도메인    townboard.localhost → "townboard"
  2. HTTP 헤더    X-Tenant-Id: joongang → "joongang"
  3. 기본값       "default"
```

요청이 완료되면 `afterCompletion()`에서 **반드시 `TenantContext.clear()`를 호출**한다.
→ 스레드 풀 재사용 시 이전 요청의 tenantId가 다음 요청으로 오염되는 것을 방지한다.

**시현 방법 (브라우저):**
1. `http://townboard.localhost:8080` 접속 → 상단 네비게이션에 `tenant: townboard` 표시
2. `http://joongang.localhost:8080` 접속 → `tenant: joongang` 표시

> `hosts` 파일 수정이 필요한 경우:
> `127.0.0.1  townboard.localhost` / `127.0.0.1  joongang.localhost`

**시현 방법 (curl / Postman):**
```bash
curl -H "X-Tenant-Id: townboard" http://localhost:8080/content
curl -H "X-Tenant-Id: joongang" http://localhost:8080/content
```

---

### 4-2. ThreadLocal 기반 테넌트 저장 — `TenantContext`

```java
// 요청 단위로 현재 테넌트를 ThreadLocal에 저장
public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public static void set(String tenantId)  { CURRENT_TENANT.set(tenantId); }
    public static String get()               { return CURRENT_TENANT.get(); }
    public static void clear()               { CURRENT_TENANT.remove(); }
}
```

**ThreadLocal을 사용하는 이유:**
- 하나의 서버에 여러 요청이 동시에 들어와도 각 스레드가 독립된 저장 공간을 갖는다
- A 사용자 요청의 tenantId가 B 사용자 요청에 절대 섞이지 않는다

---

### 4-3. DB 스키마 분리 — `SchemaPerTenantConnectionProvider`

```
public 스키마 (모든 테넌트 공통)
  ├── TB_FORM_DEFINITION   (UI 폼 정의 — TENANT_ID 컬럼 있음)
  └── CM_SHEDLOCK          (배치 분산 락 테이블)

townboard 스키마 (townboard 전용)
  └── TB_CONTENT           (TENANT_ID 컬럼 없음 — 스키마 자체가 격리)

joongang 스키마 (joongang 전용)
  └── TB_CONTENT           (TENANT_ID 컬럼 없음 — 스키마 자체가 격리)
```

Hibernate이 DB 커넥션을 요청할 때 `getConnection(tenantId)`가 호출된다.
이 메서드 안에서 아래 SQL을 실행해 현재 세션의 검색 경로(스키마)를 전환한다.

```sql
SET search_path TO "townboard", "public"
```

이후 `SELECT * FROM TB_CONTENT`를 실행하면 자동으로 `townboard.TB_CONTENT`만 바라본다.
커넥션 반납 시 `SET search_path TO "public"`으로 원복한다 → **커넥션 풀 오염 방지**

**컬럼 기반 vs 스키마 기반 비교:**

| 항목 | 컬럼 기반 | 스키마 기반 (현재) |
|------|----------|-----------------|
| 격리 방식 | `WHERE tenant_id = ?` | `SET search_path TO {tenant}` |
| TB_CONTENT 구조 | TENANT_ID 컬럼 있음 | TENANT_ID 컬럼 없음 |
| Repository 쿼리 | `findByTenantId...` | `findAll`, `findById` 단순 쿼리 |
| 격리 강도 | 쿼리 조건 실수 시 오염 가능 | DB 레벨 완전 격리 |
| 신규 테넌트 추가 | 데이터만 추가 | 스키마 + DDL 필요 |

---

### 4-4. Hibernate 연동 — `TenantIdentifierResolver`

Hibernate이 "현재 어떤 테넌트야?"라고 물으면 `TenantContext.get()`을 반환하는 역할이다.
이 값을 받아 `SchemaPerTenantConnectionProvider.getConnection(tenantId)`가 스키마를 전환한다.

**전체 흐름 요약:**

```
HTTP 요청 도착
    ↓
TenantInterceptor.preHandle()
    → tenantId 추출 → TenantContext.set("townboard")
    ↓
Controller → Service → Repository
    ↓
Hibernate이 Connection 요청
    → TenantIdentifierResolver.resolveCurrentTenantIdentifier()
       → TenantContext.get() → "townboard" 반환
    → SchemaPerTenantConnectionProvider.getConnection("townboard")
       → SET search_path TO "townboard", "public"
    ↓
SQL 실행 → townboard.TB_CONTENT 바라봄
    ↓
Hibernate이 Connection 반납
    → SET search_path TO "public"  ← 커넥션 풀 원복
    ↓
TenantInterceptor.afterCompletion()
    → TenantContext.clear()  ← ThreadLocal 정리
```

---

### 4-5. 동적 UI — 테넌트마다 다른 폼

`public.TB_FORM_DEFINITION`에 테넌트별 필드 정의가 저장되어 있다.

| tenantId | fieldKey | fieldType | label |
|----------|----------|-----------|-------|
| townboard | title | text | 제목 |
| townboard | duration | number | 노출시간(초) |
| townboard | displayType | select | 디스플레이 타입 |
| joongang | title | text | 제목 |
| joongang | category | select | 카테고리 |
| joongang | publishDate | date | 게시일 |

**시현 방법:**
- `http://townboard.localhost:8080/content/new` → `duration` 필드 렌더링
- `http://joongang.localhost:8080/content/new` → `category`, `publishDate` 필드 렌더링
- 같은 URL, 같은 컨트롤러 코드인데 테넌트에 따라 화면이 달라진다

---

### 4-6. 데이터 분리 확인

**시현 방법:**
```bash
# townboard 목록 → 광고 콘텐츠 3건만 표시
curl -H "X-Tenant-Id: townboard" http://localhost:8080/content

# joongang 목록 → 뉴스 콘텐츠 3건만 표시
curl -H "X-Tenant-Id: joongang" http://localhost:8080/content
```

확인 포인트:
- townboard 응답에 "광고 콘텐츠"만 나온다
- joongang 응답에 "뉴스 콘텐츠"만 나온다
- 두 목록이 절대 겹치지 않는다
- 서버 로그: `[schema] search_path → townboard, public`

> `Content` 엔티티에 `TENANT_ID` 컬럼이 없다 → 스키마 자체가 테넌트 격리를 보장하기 때문

---

## 5. Lock 테스트 시현 — `/lock-test` 페이지

모든 Lock 시나리오는 **Lock 테스트 대시보드** 한 페이지에서 버튼 클릭만으로 시현 가능하다.

**URL:** `http://townboard.localhost:8080/lock-test`

버튼을 클릭하면 JavaScript가 **동시에 2~3개의 HTTP 요청**을 서버로 전송한다.
각 요청의 결과(대기시간, 처리시간, 상태)가 실시간으로 카드에 표시된다.

---

### 시나리오 1: Pessimistic Write Lock (비관적 락)

**핵심 개념:**
"충돌이 발생할 것이라고 비관적으로 가정"
→ 조회 시점에 미리 DB 레벨 Lock을 건다.
→ Lock을 잡은 트랜잭션이 완료될 때까지 다른 요청은 DB에서 블로킹된다.

**실행 SQL:**
```sql
SELECT * FROM TB_CONTENT WHERE ID = ? FOR UPDATE
```

**동작 흐름:**
```
REQ-1 → SELECT FOR UPDATE  → [LOCK ACQUIRED 즉시 획득]
                               3초간 PROCESSING 작업...
REQ-2 → SELECT FOR UPDATE  → [WAITING... DB 레벨 블로킹]
                               (REQ-1 커밋 완료까지 대기)
         → [LOCK ACQUIRED] 3,021ms 대기 후 획득
         → 이후 PROCESSING → DONE
```

**구현 코드:**
```java
// ContentRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM Content c WHERE c.id = :id")
Optional<Content> findByIdWithPessimisticLock(@Param("id") Long id);
```

**시현 방법:**
1. Lock 테스트 대시보드에서 콘텐츠 선택 (예: #1)
2. "⚡ 동시 2개 요청 실행" 버튼 클릭
3. REQ-1 카드: `대기시간 0ms` → 즉시 처리 시작
4. REQ-2 카드: `대기시간 ~3,000ms` → REQ-1 완료 후 처리 시작
5. 로그 박스에서 실시간 확인

**확인 포인트:**
- REQ-2의 `waitTimeMs` > 200ms → 대기가 실제로 발생했음을 수치로 증명
- 두 요청 모두 최종 상태 `DONE` → 데이터 손상 없이 순차 처리됨
- 로그: `[WAITING...] 3021ms 대기 후 LOCK ACQUIRED`

**curl로 시현:**
```bash
# 터미널 1
curl -X POST http://localhost:8080/lock-test/pessimistic \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: townboard" \
  -d '{"contentId": 1, "requestId": "REQ-A"}'

# 터미널 2 (거의 동시에 실행)
curl -X POST http://localhost:8080/lock-test/pessimistic \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: townboard" \
  -d '{"contentId": 1, "requestId": "REQ-B"}'
```

| 요청 | waitTimeMs | 결과 |
|------|-----------|------|
| REQ-A | ~0ms | 즉시 락 획득, DONE |
| REQ-B | ~3,000ms | REQ-A 커밋 후 락 획득, DONE |

---

### 시나리오 2: ShedLock (배치 분산 락)

**핵심 개념:**
서버가 여러 대 떠 있는 클러스터 환경에서 같은 배치가 동시에 실행되면 데이터 중복 처리가 발생한다.
ShedLock은 `public.CM_SHEDLOCK` 테이블을 **공유 락 저장소**로 사용해 이를 방지한다.

**CM_SHEDLOCK 테이블:**
```
NAME (PK)              | LOCK_UNTIL              | LOCKED_AT               | LOCKED_BY
contentProcessingBatch | 2026-04-06 12:00:25.000 | 2026-04-06 12:00:00.000 | server-1
```

**동작 원리:**
```
배치 실행 시도 (서버 A, B 동시에)

  서버 A → INSERT INTO CM_SHEDLOCK ... (lock_until = now + 25s)
           → 성공 → 락 획득 → 배치 실행
  서버 B → INSERT 시도 → lock_until이 미래 → 실패 → 배치 SKIP

  배치 완료 후:
    → UPDATE CM_SHEDLOCK SET lock_until = now()  (락 해제)

  서버 다운 시:
    → lockAtMostFor=25s 경과 후 자동 해제 (데드락 방지)
```

**스케줄러 코드:**
```java
@Scheduled(fixedDelay = 30_000)   // 30초마다 실행
@SchedulerLock(
    name = "contentProcessingBatch",
    lockAtMostFor = "25s",   // 서버 다운 시 최대 25초 후 자동 해제
    lockAtLeastFor = "5s"    // 배치가 빨리 끝나도 최소 5초는 락 유지
                             // → 30초 주기 내 재진입 방지
)
public void processReadyContent() { ... }
```

**멀티테넌트 배치의 특징 — TenantContext 직접 전환:**
```java
for (String tenantId : List.of("townboard", "joongang")) {
    TenantContext.set(tenantId);      // Hibernate이 getConnection("townboard") 호출
    try {
        List<Content> readyList = contentRepository.findByStatus("READY");
        // → SET search_path TO "townboard", "public" → townboard.TB_CONTENT 조회
    } finally {
        TenantContext.clear();         // 반드시 정리 (다음 루프에 오염 방지)
    }
}
```
HTTP 요청이 아닌 스케줄러에서는 `TenantInterceptor`가 동작하지 않으므로
배치 내에서 `TenantContext`를 직접 전환하며 각 테넌트 스키마를 순회한다.

**시현 방법:**
1. 서버 실행 후 30초 대기 (배치 자동 실행)
2. 서버 콘솔 로그 확인:
   ```
   [BATCH] ====================================
   [BATCH] [LOCK ACQUIRED] contentProcessingBatch 시작 batchId=A1B2C3D4
   [BATCH] [townboard] 3건 처리 시작
   [BATCH]   → content #1 [광고 콘텐츠 #1]
   [BATCH]   → content #2 [광고 콘텐츠 #2]
   [BATCH] [joongang] 처리할 READY 항목 없음
   [BATCH] contentProcessingBatch 완료 batchId=A1B2C3D4 duration=42ms
   [BATCH] ====================================
   ```
3. `/batch-log` 페이지에서 배치 실행 이력 확인

**서버 2대로 중복 방지 시현:**
```bash
# 서버 1 (포트 8080)
./gradlew bootRun

# 서버 2 (포트 8081 — 별도 터미널)
./gradlew bootRun --args='--server.port=8081'
```
- 서버 1 로그: `[BATCH] [LOCK ACQUIRED] contentProcessingBatch 시작`
- 서버 2 로그: 아무 배치 로그 없음 (ShedLock이 실행 자체를 차단)

**DB에서 직접 확인:**
```sql
SELECT * FROM public.CM_SHEDLOCK WHERE NAME = 'contentProcessingBatch';
```

---

### 시나리오 3: SKIP LOCKED (큐 처리)

**핵심 개념:**
여러 Worker가 READY 상태 항목을 동시에 처리할 때, 한 Worker가 처리 중인 항목을
다른 Worker가 중복 처리하지 않도록 **잠긴 행을 건너뛴다.**

**FOR UPDATE 옵션 비교:**

| SQL 옵션 | 잠긴 행 만나면 | 사용 목적 |
|----------|---------------|-----------|
| `FOR UPDATE` | 잠금 해제까지 대기 | 단일 행 수정 보호 (시나리오 1) |
| `FOR UPDATE NOWAIT` | 즉시 예외 발생 | 시나리오 5 |
| `FOR UPDATE SKIP LOCKED` | 해당 행 건너뛰고 다음 행 | 분산 큐 처리 (이 시나리오) |
| `FOR SHARE` | 공유 락 (읽기 허용, 쓰기 차단) | 참조 무결성 보호 |

**동작 흐름:**
```
초기 상태: content #1, #2, #3 모두 READY

Worker A → SELECT FOR UPDATE SKIP LOCKED → content #1 잠금 → PROCESSING
Worker B → SELECT FOR UPDATE SKIP LOCKED → #1 건너뜀 → content #2 잠금 → PROCESSING
Worker C → SELECT FOR UPDATE SKIP LOCKED → #1, #2 건너뜀 → content #3 잠금 → PROCESSING
Worker D → SELECT FOR UPDATE SKIP LOCKED → 모두 잠김 → NO_ITEM (항목 없음)
```

**구현 코드:**
```java
// ContentRepository.java
@Query(value = """
    SELECT * FROM TB_CONTENT
    WHERE STATUS = 'READY'
    ORDER BY ID
    FOR UPDATE SKIP LOCKED
    LIMIT 1
    """, nativeQuery = true)
Optional<Content> findFirstReadyWithSkipLocked();
```

**시현 방법:**
1. "↺ 전체 READY 초기화" 버튼 클릭 (모든 콘텐츠 READY로 리셋)
2. "⚡ Worker 3개 동시 실행" 버튼 클릭
3. WORKER-1, 2, 3이 각자 다른 content를 처리하는 것을 카드에서 확인
4. 4번째 Worker가 있다면 `NO_ITEM` 반환

**curl로 시현 (터미널 3개 동시에):**
```bash
curl -X POST http://localhost:8080/lock-test/skip-locked \
  -H "Content-Type: application/json" -H "X-Tenant-Id: townboard" \
  -d '{"requestId": "WORKER-1"}' &

curl -X POST http://localhost:8080/lock-test/skip-locked \
  -H "Content-Type: application/json" -H "X-Tenant-Id: townboard" \
  -d '{"requestId": "WORKER-2"}' &

curl -X POST http://localhost:8080/lock-test/skip-locked \
  -H "Content-Type: application/json" -H "X-Tenant-Id: townboard" \
  -d '{"requestId": "WORKER-3"}' &
```

예상 결과:
- WORKER-1 → `content #1 [광고 콘텐츠 #1] 처리 완료`
- WORKER-2 → `content #2 [광고 콘텐츠 #2] 처리 완료`
- WORKER-3 → `content #3 [광고 콘텐츠 #3] 처리 완료`

---

### 시나리오 4: Optimistic Lock (낙관적 락)

**핵심 개념:**
"충돌이 드물 것이라고 낙관적으로 가정"
→ Lock 없이 읽고, **커밋 시점에 `@Version`으로 충돌을 감지한다.**
→ 먼저 커밋한 쪽은 성공, 나중에 커밋하려는 쪽은 `CONFLICT` 예외가 발생한다.

**엔티티 설정:**
```java
// Content.java
@Version
@Column(name = "VERSION")
private Long version;
```

**동작 흐름:**
```
REQ-1: content 읽기  → version=1
REQ-2: content 읽기  → version=1  (동시에 읽음)

REQ-1: 3초 작업 후 커밋
       → UPDATE TB_CONTENT SET STATUS='DONE', VERSION=2
          WHERE ID=1 AND VERSION=1  → 1 row affected → 성공 ✅

REQ-2: 3초 작업 후 커밋
       → UPDATE TB_CONTENT SET STATUS='DONE', VERSION=2
          WHERE ID=1 AND VERSION=1  → 0 rows (이미 version=2) → 실패 ❌
       → ObjectOptimisticLockingFailureException 발생
       → 컨트롤러에서 잡아서 CONFLICT 반환
```

**시현 방법:**
1. 콘텐츠 선택 (드롭다운에 현재 version 표시: `[version=1]`)
2. "⚡ 동시 2개 요청 실행" 클릭
3. OPT-1: 초록 배지 → `낙관적 락 성공 (version: 1 → 2)`
4. OPT-2: 빨간 배지 → `낙관적 락 충돌 — 다른 요청이 먼저 커밋함 (version 불일치)`

**예외 처리 코드:**
```java
// LockTestController.java
try {
    LockTestResult result = lockTestService.testOptimisticLock(contentId, requestId);
    return ResponseEntity.ok(result);
} catch (ObjectOptimisticLockingFailureException e) {
    return ResponseEntity.ok(new LockTestResult(
        requestId, tenantId, "CONFLICT", 0, 0,
        "낙관적 락 충돌 — 다른 요청이 먼저 커밋함 (version 불일치)", contentId));
}
```

**비관적 락 vs 낙관적 락 비교:**

| 항목 | Pessimistic Lock | Optimistic Lock |
|------|-----------------|-----------------|
| 충돌 처리 | 사전 차단 (대기) | 사후 감지 (예외) |
| 적합한 상황 | 충돌 빈번, 짧은 트랜잭션 | 충돌 드물고 읽기가 많은 경우 |
| DB 부하 | Lock 경합으로 부하 높음 | Lock 없어 읽기 성능 좋음 |
| 사용자 경험 | 잠시 대기 후 성공 | 즉시 실패 → 재시도 필요 |
| 구현 방식 | `SELECT FOR UPDATE` | `@Version` + 커밋 시 version 체크 |

---

### 시나리오 5: FOR UPDATE NOWAIT

**핵심 개념:**
비관적 락의 변형.
락을 즉시 획득하지 못하면 **대기 없이 바로 `PessimisticLockException`** 을 발생시킨다.
대기 자체가 허용되지 않는 실시간 처리 시스템에서 사용한다.

**동작 흐름:**
```
NW-1 → SELECT FOR UPDATE NOWAIT → [LOCK ACQUIRED] → 3초간 작업 중...
NW-2 → SELECT FOR UPDATE NOWAIT → 이미 잠김
        → 즉시 PessimisticLockException (대기 시간 0ms)
        → "NOWAIT — 락 획득 실패 (즉시 포기)"
```

**구현 코드:**
```java
// ContentRepository.java
@Query(value = "SELECT * FROM TB_CONTENT WHERE ID = :id FOR UPDATE NOWAIT",
       nativeQuery = true)
Optional<Content> findByIdWithNowait(@Param("id") Long id);
```

**시현 방법:**
1. 콘텐츠 선택 후 "⚡ 동시 2개 요청 실행" 클릭
2. NW-1: 정상 DONE 처리
3. NW-2: 대기시간 0ms, 즉시 FAILED 상태 표시

**시나리오 1(Pessimistic)과의 차이점 비교:**

| | Pessimistic Lock | NOWAIT |
|---|---|---|
| 경합 발생 시 | DB에서 대기 (블로킹) | 즉시 예외 발생 |
| waitTimeMs | 수천 ms | ~0ms |
| 결과 | 결국 성공 (DONE) | 즉시 실패 (FAILED) |
| 적합한 경우 | 대기 허용, 데이터 일관성 중요 | 응답 시간 보장 필요, 재시도 구현 시 |

---

## 6. 테스트 초기화

Lock 테스트 후 콘텐츠 상태가 PROCESSING/DONE으로 변경된 경우 READY로 되돌리기:

- **UI:** Lock 테스트 대시보드 우측 상단 "↺ 전체 READY 초기화" 버튼
- **API:** `POST /lock-test/reset` (헤더: `X-Tenant-Id: townboard`)
- **효과:** 현재 테넌트 스키마의 모든 `TB_CONTENT`를 `STATUS = 'READY'`로 초기화

---

## 7. 전체 API 엔드포인트

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/` | 홈 (테넌트별 테마) |
| GET | `/content` | 콘텐츠 목록 |
| GET | `/content/new` | 콘텐츠 등록 폼 (동적 UI) |
| POST | `/content` | 콘텐츠 등록 |
| GET | `/content/{id}` | 콘텐츠 상세 |
| GET | `/lock-test` | Lock 테스트 대시보드 |
| POST | `/lock-test/pessimistic` | 시나리오 1: Pessimistic Lock |
| POST | `/lock-test/skip-locked` | 시나리오 3: SKIP LOCKED |
| POST | `/lock-test/optimistic` | 시나리오 4: Optimistic Lock |
| POST | `/lock-test/nowait` | 시나리오 5: NOWAIT |
| POST | `/lock-test/reset` | 콘텐츠 상태 READY로 초기화 |
| GET | `/lock-test/status` | 현재 콘텐츠 상태 조회 (실시간) |
| GET | `/batch-log` | 배치 실행 이력 조회 |

---

## 8. 로그로 동작 검증하기

서버 콘솔 로그에서 다음 패턴을 확인하면 각 기능이 정상 동작했다는 증거가 된다.

| 로그 패턴 | 의미 |
|-----------|------|
| `[tenant=townboard] GET /lock-test` | 테넌트 추출 정상 |
| `[schema] search_path → townboard, public` | 스키마 전환 성공 |
| `[LOCK ACQUIRED] 즉시 획득` | Pessimistic Lock 선점 |
| `[WAITING...] 3021ms 대기 후 LOCK ACQUIRED` | Lock 경합 발생, 대기 후 획득 |
| `[PROCESSING] content #1` | 작업 처리 중 |
| `[DONE] 처리완료 processTime=3012ms` | 작업 완료 |
| `[BATCH] [LOCK ACQUIRED] contentProcessingBatch` | ShedLock 획득, 배치 실행 |
| `[SKIP] 처리 가능한 항목 없음 (SKIP LOCKED 동작)` | SKIP LOCKED 동작 확인 |

**로그 레벨 설정 (`application.yml`):**
```yaml
logging:
  level:
    com.sclm.multitanant: DEBUG       # 테넌트/CRUD 상세 로그
    org.hibernate.SQL: DEBUG           # 실행 SQL 출력
    org.hibernate.orm.jdbc.bind: TRACE # SQL 바인딩 파라미터
    net.javacrumbs.shedlock: DEBUG     # ShedLock 획득/스킵 로그
```

---

## 9. 핵심 클래스 요약

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `TenantContext` | tenant | ThreadLocal로 요청 단위 tenantId 저장/조회/정리 |
| `TenantInterceptor` | tenant | 서브도메인/헤더에서 tenantId 추출, 요청 전/후 처리 |
| `TenantIdentifierResolver` | tenant | Hibernate에게 현재 tenantId 제공 |
| `SchemaPerTenantConnectionProvider` | tenant | `SET search_path` 실행, 스키마 전환 핵심 구현체 |
| `MultiTenancyConfig` | config | 위 두 빈을 Hibernate 프로퍼티에 등록 |
| `Content` | entity | 테넌트별 콘텐츠, `@Version`으로 낙관적 락 지원 |
| `FormDefinition` | entity | 테넌트별 동적 UI 폼 정의 (public 스키마) |
| `ContentRepository` | repository | Pessimistic / Skip Locked / NOWAIT 쿼리 정의 |
| `LockTestService` | service | 각 Lock 시나리오 비즈니스 로직 (waitMs 측정 포함) |
| `LockTestController` | controller | Lock 시나리오 API 엔드포인트, 예외 처리 |
| `ContentBatchScheduler` | scheduler | ShedLock + 멀티테넌트 TenantContext 전환 배치 |
| `BatchLogStore` | service | 배치 실행 이력 인메모리 저장 (UI 표시용) |
| `DataInitializer` | config | 앱 시작 시 CM_SHEDLOCK 테이블 + 샘플 데이터 자동 생성 |

---

## 10. 최종 검증 체크리스트

- [ ] `townboard.localhost`와 `joongang.localhost` 접속 시 상단에 각각 다른 테넌트 표시
- [ ] `/content` 목록에서 테넌트별로 다른 데이터만 조회됨
- [ ] `/content/new` 폼이 테넌트별로 다른 필드를 렌더링함
- [ ] Pessimistic Lock: REQ-2의 waitTimeMs > 200ms 확인
- [ ] ShedLock: 서버 2대 중 1대만 배치 로그 출력
- [ ] SKIP LOCKED: Worker 3개가 서로 다른 content 처리
- [ ] Optimistic Lock: OPT-1 성공, OPT-2 CONFLICT 반환
- [ ] NOWAIT: NW-2가 대기 없이 즉시 FAILED 반환
