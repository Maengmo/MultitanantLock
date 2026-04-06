package com.sclm.multitanant.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Hibernate이 DB 연결 시 "현재 테넌트가 누구냐"를 물어볼 때 응답하는 구현체.
 * TenantInterceptor가 TenantContext에 저장한 값을 그대로 반환한다.
 * 값이 없으면(배치 초기화 등) public 스키마를 기본으로 사용.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    private static final String DEFAULT_SCHEMA = "public";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenant = TenantContext.get();
        return (tenant != null && !tenant.isBlank()) ? tenant : DEFAULT_SCHEMA;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
