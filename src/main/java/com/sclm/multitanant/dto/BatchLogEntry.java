package com.sclm.multitanant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class BatchLogEntry {

    private final String batchId;
    private final LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;                  // RUNNING, DONE, FAILED
    private List<TenantResult> tenantResults;
    private long durationMs;
    private String errorMessage;

    public BatchLogEntry(String batchId, LocalDateTime startTime) {
        this.batchId = batchId;
        this.startTime = startTime;
        this.status = "RUNNING";
    }

    @Getter
    @AllArgsConstructor
    public static class TenantResult {
        private final String tenantId;
        private final int readyCount;
        private final String status;        // OK, ERROR
        private final String message;
    }
}
