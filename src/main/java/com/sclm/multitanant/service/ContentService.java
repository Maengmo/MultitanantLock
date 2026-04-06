package com.sclm.multitanant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclm.multitanant.dto.ContentCreateRequest;
import com.sclm.multitanant.entity.Content;
import com.sclm.multitanant.repository.ContentRepository;
import com.sclm.multitanant.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentService {

    private final ContentRepository contentRepository;
    private final ObjectMapper objectMapper;

    public List<Content> findAll() {
        // 스키마 기반: search_path가 현재 테넌트 스키마로 설정되어 있으므로
        // tenant_id 조건 없이 그냥 조회해도 해당 테넌트 데이터만 반환됨
        log.debug("[tenant={}] 콘텐츠 목록 조회", TenantContext.get());
        return contentRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<Content> findById(Long id) {
        // 스키마 기반: 스키마 자체가 격리를 보장하므로 tenant_id 조건 불필요
        return contentRepository.findById(id);
    }

    @Transactional
    public Content create(ContentCreateRequest request) {
        Content content = new Content();
        content.setTitle(request.getTitle());

        if (request.getExtraFields() != null && !request.getExtraFields().isEmpty()) {
            try {
                content.setExtraData(objectMapper.writeValueAsString(request.getExtraFields()));
            } catch (JsonProcessingException e) {
                log.warn("[tenant={}] extraData 직렬화 실패", TenantContext.get(), e);
            }
        }

        Content saved = contentRepository.save(content);
        log.info("[tenant={}] 콘텐츠 생성 id={}, title={}", TenantContext.get(), saved.getId(), saved.getTitle());
        return saved;
    }

    @Transactional
    public int resetAll() {
        int count = contentRepository.resetAllToReady();
        log.info("[tenant={}] {} 건 READY 초기화 완료", TenantContext.get(), count);
        return count;
    }

    public long count() {
        return contentRepository.count();
    }
}
