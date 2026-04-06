package com.sclm.multitanant.config;

import com.sclm.multitanant.entity.FormDefinition;
import com.sclm.multitanant.repository.FormDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 애플리케이션 시작 시 샘플 데이터를 자동으로 입력한다.
 * 이미 데이터가 있으면 건너뛴다.
 *
 * - TB_FORM_DEFINITION : JPA (public 스키마, TenantContext 없이 접근 가능)
 * - TB_CONTENT         : JdbcTemplate으로 스키마 직접 지정
 *                        (JPA 멀티테넌시는 HTTP 요청 단위 TenantContext가 필요하므로
 *                         초기화 시점에는 JdbcTemplate이 더 안전함)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final FormDefinitionRepository formRepo;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        initShedLockTable();
        initFormDefinitions();
        initSampleContent();
    }

    /** CM_SHEDLOCK 테이블이 없으면 생성 (public 스키마) */
    private void initShedLockTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS public.CM_SHEDLOCK (
                        NAME       VARCHAR(100) NOT NULL,
                        LOCK_UNTIL TIMESTAMP    NOT NULL,
                        LOCKED_AT  TIMESTAMP    NOT NULL,
                        LOCKED_BY  VARCHAR(255) NOT NULL,
                        PRIMARY KEY (NAME)
                    )
                    """);
            log.info("[INIT] CM_SHEDLOCK 테이블 확인 완료");
        } catch (Exception e) {
            log.warn("[INIT] CM_SHEDLOCK 테이블 생성 스킵: {}", e.getMessage());
        }
    }

    /** TB_FORM_DEFINITION 샘플 데이터 (public 스키마, JPA 사용) */
    private void initFormDefinitions() {
        if (formRepo.existsByTenantId("townboard")) {
            log.info("[INIT] FormDefinition 이미 존재 - 스킵");
            return;
        }

        List<FormDefinition> fields = buildFormFields(new Object[][]{
                // townboard
                {"townboard", "CONTENT_CREATE", "title",       "text",   "제목",           true,  1},
                {"townboard", "CONTENT_CREATE", "duration",    "number", "노출시간(초)",    true,  2},
                {"townboard", "CONTENT_CREATE", "displayType", "select", "디스플레이 타입", false, 3},
                // joongang
                {"joongang",  "CONTENT_CREATE", "title",       "text",   "제목",    true,  1},
                {"joongang",  "CONTENT_CREATE", "category",    "select", "카테고리", true,  2},
                {"joongang",  "CONTENT_CREATE", "publishDate", "date",   "게시일",   false, 3},
        });

        formRepo.saveAll(fields);
        log.info("[INIT] FormDefinition 샘플 데이터 {} 건 생성 완료", fields.size());
    }

    /**
     * TB_CONTENT 샘플 데이터.
     * JdbcTemplate으로 스키마를 직접 지정해 삽입한다.
     * TENANT_ID 컬럼 없음 - 스키마 자체가 테넌트를 구분한다.
     */
    private void initSampleContent() {
        insertContentIfEmpty("townboard", List.of(
                new Object[]{"광고 콘텐츠 #1", "{\"duration\":30,\"displayType\":\"banner\"}"},
                new Object[]{"광고 콘텐츠 #2", "{\"duration\":60,\"displayType\":\"popup\"}"},
                new Object[]{"광고 콘텐츠 #3", "{\"duration\":15,\"displayType\":\"banner\"}"}
        ));

        insertContentIfEmpty("joongang", List.of(
                new Object[]{"뉴스 콘텐츠 #1", "{\"category\":\"정치\",\"publishDate\":\"2026-04-03\"}"},
                new Object[]{"뉴스 콘텐츠 #2", "{\"category\":\"경제\",\"publishDate\":\"2026-04-03\"}"},
                new Object[]{"뉴스 콘텐츠 #3", "{\"category\":\"사회\",\"publishDate\":\"2026-04-03\"}"}
        ));
    }

    private void insertContentIfEmpty(String schema, List<Object[]> rows) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".TB_CONTENT", Integer.class);
        if (count != null && count > 0) {
            log.info("[INIT] {}.TB_CONTENT 이미 존재 ({} 건) - 스킵", schema, count);
            return;
        }
        for (Object[] row : rows) {
            jdbcTemplate.update(
                    "INSERT INTO " + schema + ".TB_CONTENT (TITLE, EXTRA_DATA) VALUES (?, ?::jsonb)",
                    row[0], row[1]);
        }
        log.info("[INIT] {}.TB_CONTENT {} 건 생성 완료", schema, rows.size());
    }

    private List<FormDefinition> buildFormFields(Object[][] rows) {
        return Arrays.stream(rows)
                .map(r -> {
                    try {
                        FormDefinition fd = new FormDefinition();
                        setField(fd, "tenantId",  (String)  r[0]);
                        setField(fd, "formType",  (String)  r[1]);
                        setField(fd, "fieldKey",  (String)  r[2]);
                        setField(fd, "fieldType", (String)  r[3]);
                        setField(fd, "label",     (String)  r[4]);
                        setField(fd, "required",  (Boolean) r[5]);
                        setField(fd, "orderNo",   (Integer) r[6]);
                        return fd;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
