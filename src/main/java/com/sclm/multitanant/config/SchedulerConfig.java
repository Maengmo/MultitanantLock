package com.sclm.multitanant.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * ShedLock 설정.
 * CM_SHEDLOCK 테이블을 공유해 여러 서버가 동시에 배치를 실행하지 못하도록 한다.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "30s")
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withTableName("CM_SHEDLOCK")
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }
}
