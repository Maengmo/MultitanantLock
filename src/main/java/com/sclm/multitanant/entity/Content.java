package com.sclm.multitanant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 테넌트별 콘텐츠.
 *
 * 스키마 분리 기반이므로 TENANT_ID 컬럼 없음.
 * 어느 스키마에 접속했느냐(search_path)가 곧 테넌트 격리를 보장한다.
 *
 * - status  : READY → PROCESSING → DONE
 * - version : Optimistic Lock 테스트용 (@Version)
 * - extraData: 테넌트별 동적 필드를 JSONB로 저장
 */
@Entity
@Table(name = "TB_CONTENT",
        indexes = {
            @Index(name = "idx_content_status", columnList = "STATUS")
        })
@Getter
@Setter
@NoArgsConstructor
public class Content {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "TITLE", nullable = false, length = 200)
    private String title;

    /** READY → PROCESSING → DONE */
    @Column(name = "STATUS", length = 20)
    private String status;

    /** 테넌트별 추가 필드 (JSONB) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "EXTRA_DATA", columnDefinition = "jsonb")
    private String extraData;

    /** Optimistic Lock 버전 */
    @Version
    @Column(name = "VERSION")
    private Long version;

    @Column(name = "CREATED_AT")
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "READY";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
