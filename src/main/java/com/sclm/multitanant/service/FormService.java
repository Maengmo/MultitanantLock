package com.sclm.multitanant.service;

import com.sclm.multitanant.entity.FormDefinition;
import com.sclm.multitanant.repository.FormDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FormService {

    private final FormDefinitionRepository formDefinitionRepository;

    /**
     * 테넌트 + 폼 타입에 맞는 필드 목록을 순서대로 반환.
     * Thymeleaf에서 동적 렌더링에 사용한다.
     */
    public List<FormDefinition> getFields(String tenantId, String formType) {
        List<FormDefinition> fields = formDefinitionRepository
                .findByTenantIdAndFormTypeOrderByOrderNoAsc(tenantId, formType);
        log.debug("[tenant={}] formType={} → {} 개 필드 조회", tenantId, formType, fields.size());
        return fields;
    }
}
