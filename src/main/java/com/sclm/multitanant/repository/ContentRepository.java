package com.sclm.multitanant.repository;

import com.sclm.multitanant.entity.Content;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * SchemaPerTenantConnectionProvider가 search_path를 테넌트 스키마로 전환하므로
 * 이 Repository는 현재 스키마의 TB_CONTENT만 바라본다.
 */
public interface ContentRepository extends JpaRepository<Content, Long> {

    List<Content> findAllByOrderByCreatedAtDesc();

    List<Content> findByStatus(String status);

    /**
     * [시나리오 1] Pessimistic Write Lock
     * SELECT ... FOR UPDATE → 동시 수정 시 한 요청이 블로킹된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Content c WHERE c.id = :id")
    Optional<Content> findByIdWithPessimisticLock(@Param("id") Long id);

    /**
     * [시나리오 3] SKIP LOCKED 큐 처리
     * 이미 잠긴 행은 건너뛰고 다음 READY 항목을 가져온다.
     * tenant_id 조건 제거 - 현재 스키마(search_path)가 격리를 보장.
     */
    @Query(value = """
            SELECT * FROM TB_CONTENT
            WHERE STATUS = 'READY'
            ORDER BY ID
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<Content> findFirstReadyWithSkipLocked();

    /**
     * [시나리오 4] NOWAIT — 락을 즉시 획득 못 하면 바로 예외
     * SELECT ... FOR UPDATE NOWAIT → 다른 트랜잭션이 락을 갖고 있으면
     * 대기 없이 PessimisticLockException 발생
     */
    @Query(value = "SELECT * FROM TB_CONTENT WHERE ID = :id FOR UPDATE NOWAIT", nativeQuery = true)
    Optional<Content> findByIdWithNowait(@Param("id") Long id);

    /** 테스트 리셋 - 현재 스키마의 모든 콘텐츠를 READY 상태로 초기화 */
    @Modifying
    @Query("UPDATE Content c SET c.status = 'READY'")
    int resetAllToReady();
}
