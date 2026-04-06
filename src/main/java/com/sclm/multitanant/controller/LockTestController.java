package com.sclm.multitanant.controller;

import com.sclm.multitanant.dto.LockTestResult;
import com.sclm.multitanant.entity.Content;
import com.sclm.multitanant.service.ContentService;
import com.sclm.multitanant.service.LockTestService;
import com.sclm.multitanant.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/lock-test")
@RequiredArgsConstructor
public class LockTestController {

    private final LockTestService lockTestService;
    private final ContentService contentService;

    /** Lock 테스트 대시보드 */
    @GetMapping
    public String index(Model model) {
        String tenantId = TenantContext.get();
        List<Content> contents = contentService.findAll();
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("contents", contents);
        return "lock-test/index";
    }

    /**
     * [시나리오 1] Pessimistic Lock 테스트
     * 동시에 2개의 JS fetch()로 호출 → 하나는 대기, 하나는 즉시 처리
     */
    @PostMapping("/pessimistic")
    @ResponseBody
    public ResponseEntity<LockTestResult> pessimisticLock(@RequestBody Map<String, Object> body) {
        Long contentId = Long.parseLong(body.get("contentId").toString());
        String requestId = body.containsKey("requestId")
                ? body.get("requestId").toString()
                : "REQ-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        log.info("[tenant={}] [{}] Pessimistic Lock 테스트 시작", TenantContext.get(), requestId);
        LockTestResult result = lockTestService.testPessimisticLock(contentId, requestId);
        return ResponseEntity.ok(result);
    }

    /**
     * [시나리오 3] SKIP LOCKED 큐 처리 테스트
     * 동시에 여러 Worker가 각자 다른 항목을 처리하는지 확인
     */
    @PostMapping("/skip-locked")
    @ResponseBody
    public ResponseEntity<LockTestResult> skipLocked(@RequestBody Map<String, Object> body) {
        String requestId = body.containsKey("requestId")
                ? body.get("requestId").toString()
                : "WORKER-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        log.info("[tenant={}] [{}] SKIP LOCKED 테스트 시작", TenantContext.get(), requestId);
        LockTestResult result = lockTestService.testSkipLocked(requestId);
        return ResponseEntity.ok(result);
    }

    /** 테스트 리셋 - 모든 콘텐츠를 READY 상태로 복구 */
    @PostMapping("/reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reset() {
        int count = contentService.resetAll();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "resetCount", count,
                "message", count + "건 READY 상태로 초기화됨"
        ));
    }

    /**
     * [시나리오 4] Optimistic Lock 테스트
     * 동시에 2개 요청 → 먼저 커밋한 쪽은 성공, 나중은 CONFLICT
     */
    @PostMapping("/optimistic")
    @ResponseBody
    public ResponseEntity<LockTestResult> optimisticLock(@RequestBody Map<String, Object> body) {
        Long contentId = Long.parseLong(body.get("contentId").toString());
        String requestId = body.containsKey("requestId")
                ? body.get("requestId").toString()
                : "OPT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String tenantId = TenantContext.get();

        try {
            LockTestResult result = lockTestService.testOptimisticLock(contentId, requestId);
            return ResponseEntity.ok(result);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("[tenant={}] [{}] OPTIMISTIC LOCK 충돌: version 불일치", tenantId, requestId);
            return ResponseEntity.ok(new LockTestResult(
                    requestId, tenantId, "CONFLICT", 0, 0,
                    "낙관적 락 충돌 — 다른 요청이 먼저 커밋함 (version 불일치)", contentId));
        }
    }

    /**
     * [시나리오 5] NOWAIT 테스트
     * 락을 즉시 획득 못 하면 대기 없이 FAILED 반환
     */
    @PostMapping("/nowait")
    @ResponseBody
    public ResponseEntity<LockTestResult> nowait(@RequestBody Map<String, Object> body) {
        Long contentId = Long.parseLong(body.get("contentId").toString());
        String requestId = body.containsKey("requestId")
                ? body.get("requestId").toString()
                : "NW-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String tenantId = TenantContext.get();

        try {
            LockTestResult result = lockTestService.testNowait(contentId, requestId);
            return ResponseEntity.ok(result);
        } catch (CannotAcquireLockException | jakarta.persistence.PessimisticLockException e) {
            log.warn("[tenant={}] [{}] NOWAIT 락 획득 실패: 이미 잠김", tenantId, requestId);
            return ResponseEntity.ok(new LockTestResult(
                    requestId, tenantId, "FAILED", 0, 0,
                    "NOWAIT — 락 획득 실패 (다른 트랜잭션이 점유 중, 즉시 포기)", contentId));
        }
    }

    /** 현재 콘텐츠 상태 조회 (실시간 갱신용) */
    @GetMapping("/status")
    @ResponseBody
    public ResponseEntity<List<Content>> getStatus() {
        return ResponseEntity.ok(contentService.findAll());
    }
}
