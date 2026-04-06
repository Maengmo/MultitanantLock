package com.sclm.multitanant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 테넌트별 동적 UI 폼 정의.
 * 같은 FORM_TYPE이라도 TENANT_ID에 따라 다른 필드를 렌더링한다.
 */
@Entity
@Table(name = "TB_FORM_DEFINITION")
@Getter
@NoArgsConstructor
public class FormDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "TENANT_ID", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "FORM_TYPE", nullable = false, length = 50)
    private String formType;

    @Column(name = "FIELD_KEY", nullable = false, length = 100)
    private String fieldKey;

    @Column(name = "FIELD_TYPE", nullable = false, length = 50)
    private String fieldType;

    @Column(name = "LABEL", length = 200)
    private String label;

    @Column(name = "REQUIRED")
    private Boolean required;

    @Column(name = "ORDER_NO")
    private Integer orderNo;

    @Column(name = "CREATED_AT")
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
}
