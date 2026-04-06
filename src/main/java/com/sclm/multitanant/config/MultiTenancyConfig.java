package com.sclm.multitanant.config;

import com.sclm.multitanant.tenant.SchemaPerTenantConnectionProvider;
import com.sclm.multitanant.tenant.TenantIdentifierResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Hibernate 멀티테넌시 설정.
 * SchemaPerTenantConnectionProvider, TenantIdentifierResolver 빈을
 * Hibernate 프로퍼티에 등록해 스키마 기반 멀티테넌시를 활성화한다.
 */
@Configuration
@RequiredArgsConstructor
public class MultiTenancyConfig implements HibernatePropertiesCustomizer {

    private final SchemaPerTenantConnectionProvider connectionProvider;
    private final TenantIdentifierResolver tenantIdentifierResolver;

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put("hibernate.multi_tenant_connection_provider", connectionProvider);
        hibernateProperties.put("hibernate.tenant_identifier_resolver", tenantIdentifierResolver);
    }
}
