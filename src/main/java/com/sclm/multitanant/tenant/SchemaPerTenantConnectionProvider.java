package com.sclm.multitanant.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 테넌트별 스키마로 DB Connection을 전환하는 구현체.
 *
 * Hibernate이 Connection을 요청할 때 getConnection(tenantId)가 호출되며,
 * "SET search_path TO {tenantId}, public" 을 실행해 스키마를 전환한다.
 *
 * search_path에 public을 포함하는 이유:
 *   - public.TB_FORM_DEFINITION (폼 정의)
 *   - public.CM_SHEDLOCK (배치 락)
 *   위 두 테이블은 공통이므로 어느 테넌트에서도 접근 가능해야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaPerTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantId) throws SQLException {
        Connection conn = dataSource.getConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO \"" + tenantId + "\", \"public\"");
        }
        log.debug("[schema] search_path → {}, public", tenantId);
        return conn;
    }

    @Override
    public void releaseConnection(String tenantId, Connection connection) throws SQLException {
        // 커넥션 풀 반납 전 search_path 원복 (다른 요청에 오염되지 않도록)
        try (var stmt = connection.createStatement()) {
            stmt.execute("SET search_path TO \"public\"");
        } catch (SQLException e) {
            log.warn("[schema] search_path 원복 실패: {}", e.getMessage());
        }
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        throw new UnsupportedOperationException("Cannot unwrap as " + unwrapType);
    }
}
