package com.sclm.multitanant.service;

import com.sclm.multitanant.dto.LockTestResult;
import com.sclm.multitanant.entity.Content;
import com.sclm.multitanant.repository.ContentRepository;
import com.sclm.multitanant.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LockTestService {

    private static final long WORK_SIMULATION_MS = 3000L;

    private final ContentRepository contentRepository;

    /**
     * [시나리오 1] Pessimistic Write Lock
     *
     * 동시에 2개 요청이 같은 contentId를 잠그려 할 때:
     *  - 먼저 들어온 요청 → 즉시 LOCK ACQUIRED
     *  - 나중 요청       → DB 레벨에서 블로킹(WAITING)
     *  - 첫 요청 커밋 후 → 두 번째 요청 진행
     *
     * waitTimeMs로 대기 시간을 확인할 수 있다.
     */
    @Transactional
    public LockTestResult testPessimisticLock(Long contentId, String requestId) {
        String tenantId = TenantContext.get();
        long start = System.currentTimeMillis();

        log.info("[tenant={}] [{}] PESSIMISTIC LOCK 요청 → content #{}", tenantId, requestId, contentId);

        Content content = contentRepository.findByIdWithPessimisticLock(contentId)
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + contentId));

        long lockAcquired = System.currentTimeMillis();
        long waitMs = lockAcquired - start;

        if (waitMs > 200) {
            log.info("[tenant={}] [{}] [WAITING...] {}ms 대기 후 LOCK ACQUIRED", tenantId, requestId, waitMs);
        } else {
            log.info("[tenant={}] [{}] [LOCK ACQUIRED] 즉시 획득", tenantId, requestId);
        }

        // 작업 시뮬레이션
        content.setStatus("PROCESSING");
        contentRepository.save(content);
        log.info("[tenant={}] [{}] [PROCESSING] content #{}", tenantId, requestId, contentId);

        sleep(WORK_SIMULATION_MS);

        content.setStatus("DONE");
        contentRepository.save(content);

        long processMs = System.currentTimeMillis() - lockAcquired;
        log.info("[tenant={}] [{}] [DONE] 처리완료 processTime={}ms", tenantId, requestId, processMs);

        String msg = waitMs > 200
                ? String.format("락 경합 발생 → %dms 대기 후 처리", waitMs)
                : "락 즉시 획득 → 처리 완료";

        return new LockTestResult(requestId, tenantId, "DONE", waitMs, processMs, msg, contentId);
    }

    /**
     * [시나리오 3] SKIP LOCKED 큐 처리
     *
     * 여러 Worker가 동시에 READY 항목을 처리할 때:
     *  - 각 Worker는 서로 다른 행을 잡는다 (이미 잠긴 행은 건너뜀)
     *  - 처리 중인 행을 다른 Worker가 중복 처리하지 않는다
     */
    @Transactional
    public LockTestResult testSkipLocked(String requestId) {
        String tenantId = TenantContext.get();
        long start = System.currentTimeMillis();

        log.info("[tenant={}] [{}] SKIP LOCKED 큐 처리 요청", tenantId, requestId);

        // 스키마 기반: tenantId 파라미터 제거, search_path가 격리 보장
        Optional<Content> opt = contentRepository.findFirstReadyWithSkipLocked();

        if (opt.isEmpty()) {
            log.info("[tenant={}] [{}] [SKIP] 처리 가능한 항목 없음 (모두 잠김 또는 없음)", tenantId, requestId);
            return new LockTestResult(requestId, tenantId, "NO_ITEM", 0, 0,
                    "처리 가능한 항목 없음 (SKIP LOCKED 동작 확인)", null);
        }

        Content item = opt.get();
        item.setStatus("PROCESSING");
        contentRepository.save(item);

        log.info("[tenant={}] [{}] [PROCESSING] content #{} - {}", tenantId, requestId, item.getId(), item.getTitle());

        sleep(2000L);

        item.setStatus("DONE");
        contentRepository.save(item);

        long processMs = System.currentTimeMillis() - start;
        log.info("[tenant={}] [{}] [DONE] content #{} 처리완료 {}ms", tenantId, requestId, item.getId(), processMs);

        return new LockTestResult(requestId, tenantId, "DONE", 0, processMs,
                String.format("content #%d [%s] 처리 완료", item.getId(), item.getTitle()), item.getId());
    }

    /**
     * [시나리오 4] Optimistic Lock
     *
     * 동시에 2개 요청이 같은 contentId를 읽은 뒤 둘 다 수정 시:
     *  - 먼저 커밋한 요청 → version N → N+1 성공
     *  - 나중 커밋 요청  → DB의 version이 이미 N+1 → ObjectOptimisticLockingFailureException
     *
     * 예외는 트랜잭션 커밋 시점에 발생하므로 컨트롤러에서 잡아야 한다.
     */
    @Transactional
    public LockTestResult testOptimisticLock(Long contentId, String requestId) {
        String tenantId = TenantContext.get();
        long start = System.currentTimeMillis();

        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + contentId));

        long versionBefore = content.getVersion();
        log.info("[tenant={}] [{}] OPTIMISTIC LOCK 시작 version={}", tenantId, requestId, versionBefore);

        // 다른 요청이 먼저 커밋할 수 있도록 지연 (충돌 재현)
        sleep(WORK_SIMULATION_MS);

        content.setStatus("DONE");
        // saveAndFlush(): 즉시 UPDATE SQL 실행 → PostgreSQL row lock으로 직렬화
        // → 먼저 실행한 쪽이 row lock 획득, 나중 쪽은 첫 번째 커밋 후 unblock
        // → WHERE version=? 가 0 rows → StaleObjectStateException → CONFLICT
        contentRepository.saveAndFlush(content);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[tenant={}] [{}] OPTIMISTIC LOCK 성공 version {} → {}",
                tenantId, requestId, versionBefore, versionBefore + 1);

        return new LockTestResult(requestId, tenantId, "DONE", 0, elapsed,
                String.format("낙관적 락 성공 (version: %d → %d)", versionBefore, versionBefore + 1),
                contentId);
    }

    /**
     * [시나리오 5] NOWAIT — 즉시 실패 (대기 없음)
     *
     * 다른 트랜잭션이 이미 해당 행을 잠금 중이면:
     *  - FOR UPDATE NOWAIT → 즉시 PessimisticLockException 발생 (블로킹 없음)
     *
     * 예외는 컨트롤러에서 잡아서 의미 있는 결과로 반환한다.
     */
    @Transactional
    public LockTestResult testNowait(Long contentId, String requestId) {
        String tenantId = TenantContext.get();
        long start = System.currentTimeMillis();

        log.info("[tenant={}] [{}] NOWAIT 락 요청 → content #{}", tenantId, requestId, contentId);

        Content content = contentRepository.findByIdWithNowait(contentId)
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + contentId));

        long elapsed = System.currentTimeMillis() - start;
        log.info("[tenant={}] [{}] [LOCK ACQUIRED] NOWAIT 즉시 획득 {}ms", tenantId, requestId, elapsed);

        content.setStatus("PROCESSING");
        contentRepository.save(content);

        sleep(WORK_SIMULATION_MS);

        content.setStatus("DONE");
        contentRepository.save(content);

        long processMs = System.currentTimeMillis() - start;
        log.info("[tenant={}] [{}] [DONE] NOWAIT 처리완료 {}ms", tenantId, requestId, processMs);

        return new LockTestResult(requestId, tenantId, "DONE", elapsed, processMs,
                "NOWAIT 락 즉시 획득 → 처리 완료", contentId);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
