package com.sclm.multitanant.scheduler;

import com.sclm.multitanant.dto.BatchLogEntry;
import com.sclm.multitanant.entity.Content;
import com.sclm.multitanant.repository.ContentRepository;
import com.sclm.multitanant.service.BatchLogStore;
import com.sclm.multitanant.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [시나리오 2] ShedLock 배치 중복 실행 방지
 *
 * 서버를 여러 대 실행해도 이 배치는 한 서버에서만 실행된다.
 * CM_SHEDLOCK 테이블(public 스키마)에 잠금을 기록해 중복 실행을 막는다.
 *
 * 스키마 기반 멀티테넌시 적용 후:
 *   - 테넌트별로 TenantContext를 전환하며 각 스키마에 순차 접근한다.
 *   - 각 contentRepository 호출은 SimpleJpaRepository의 @Transactional(readOnly)로
 *     독립적인 Connection을 열고, getConnection(tenantId)에서 search_path를 전환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentBatchScheduler {

    private final ContentRepository contentRepository;
    private final BatchLogStore batchLogStore;

    // 처리 대상 테넌트 목록.
    // 실제 운영에서는 테넌트 레지스트리(DB 또는 설정파일)에서 동적으로 조회하도록 변경한다.
    private static final List<String> TENANT_LIST = List.of("townboard", "joongang");

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "contentProcessingBatch", lockAtMostFor = "25s", lockAtLeastFor = "5s")
    public void processReadyContent() {
        String batchId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        BatchLogEntry entry = new BatchLogEntry(batchId, LocalDateTime.now());
        batchLogStore.add(entry);

        log.info("[BATCH] ====================================");
        log.info("[BATCH] [LOCK ACQUIRED] contentProcessingBatch 시작 batchId={}", batchId);

        long start = System.currentTimeMillis();
        List<BatchLogEntry.TenantResult> tenantResults = new ArrayList<>();

        for (String tenantId : TENANT_LIST) {
            TenantContext.set(tenantId);
            try {
                List<Content> readyList = contentRepository.findByStatus("READY");
                if (readyList.isEmpty()) {
                    log.info("[BATCH] [{}] 처리할 READY 항목 없음", tenantId);
                } else {
                    log.info("[BATCH] [{}] {} 건 처리 시작", tenantId, readyList.size());
                    for (Content c : readyList) {
                        log.info("[BATCH]   → content #{} [{}]", c.getId(), c.getTitle());
                    }
                }
                tenantResults.add(new BatchLogEntry.TenantResult(
                        tenantId, readyList.size(), "OK",
                        readyList.isEmpty() ? "처리할 항목 없음" : readyList.size() + "건 확인"));
            } catch (Exception e) {
                log.error("[BATCH] [{}] 처리 중 오류: {}", tenantId, e.getMessage());
                tenantResults.add(new BatchLogEntry.TenantResult(tenantId, 0, "ERROR", e.getMessage()));
            } finally {
                TenantContext.clear();
            }
        }

        long durationMs = System.currentTimeMillis() - start;
        entry.setTenantResults(tenantResults);
        entry.setEndTime(LocalDateTime.now());
        entry.setDurationMs(durationMs);
        entry.setStatus("DONE");

        log.info("[BATCH] contentProcessingBatch 완료 batchId={} duration={}ms", batchId, durationMs);
        log.info("[BATCH] ====================================");
    }
}
