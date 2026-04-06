package com.sclm.multitanant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LockTestResult {
    private final String requestId;
    private final String tenantId;
    private final String status;       // DONE, NO_ITEM, ERROR
    private final long waitTimeMs;     // 락 획득까지 대기 시간
    private final long processTimeMs;  // 처리 시간
    private final String message;
    private final Long contentId;
}
