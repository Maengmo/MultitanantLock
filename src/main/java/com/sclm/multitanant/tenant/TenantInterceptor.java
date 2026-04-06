package com.sclm.multitanant.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 요청 진입 시 Host 서브도메인 또는 X-Tenant-Id 헤더에서 테넌트를 추출한다.
 *
 * 우선순위:
 *  1. 서브도메인 (townboard.localhost → townboard)
 *  2. X-Tenant-Id 헤더 (로컬 테스트용)
 *  3. 기본값: "default"
 *
 * ---------------------------------------------------------------
 * [스키마 분리로 전환 시 - 이 클래스는 수정 없이 그대로 사용 가능]
 *
 * TenantContext.set(tenantId) 까지는 동일하고,
 * 그 이후 DB 연결 시점에 스키마를 자동으로 전환하려면 아래를 추가 구현한다:
 *
 *  1. CurrentTenantIdentifierResolver 구현
 *     → Hibernate가 연결 시 tenantId를 물어볼 때 TenantContext.get() 반환
 *     @Override public String resolveCurrentTenantIdentifier() {
 *         return TenantContext.get();
 *     }
 *
 *  2. MultiTenantConnectionProvider 구현
 *     → tenantId를 받아 "SET search_path TO {tenantId}" 실행 후 Connection 반환
 *     @Override public Connection getConnection(Object tenantId) {
 *         Connection conn = dataSource.getConnection();
 *         conn.createStatement().execute("SET search_path TO " + tenantId);
 *         return conn;
 *     }
 *
 *  3. application.yml 에 아래 추가
 *     spring.jpa.properties.hibernate.multiTenancy: SCHEMA
 *     spring.jpa.properties.hibernate.tenant_identifier_resolver: (위 1번 빈)
 *     spring.jpa.properties.hibernate.multi_tenant_connection_provider: (위 2번 빈)
 *
 * 이렇게 하면 요청마다 스키마가 자동 전환되고,
 * Repository의 WHERE tenant_id = ? 조건은 전부 제거할 수 있다.
 * ---------------------------------------------------------------
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        String host = request.getServerName();
        String tenantId = extractFromSubdomain(host);

        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = request.getHeader("X-Tenant-Id");
        }

        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = "default";
        }

        TenantContext.set(tenantId);
        log.debug("[tenant={}] {} {}", tenantId, request.getMethod(), request.getRequestURI());

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        TenantContext.clear();
    }

    /**
     * 서브도메인 추출:
     *  - townboard.localhost     → townboard
     *  - townboard.clm.joins.net → townboard
     *  - localhost               → null
     */
    private String extractFromSubdomain(String host) {
        if (host == null) return null;
        // IP 주소이면 서브도메인 없음
        if (host.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) return null;

        String[] parts = host.split("\\.");
        if (parts.length >= 2) {
            String sub = parts[0];
            // localhost 단독이면 서브도메인 아님
            if (!"localhost".equalsIgnoreCase(sub)) {
                return sub;
            }
        }
        return null;
    }
}
