package com.sclm.multitanant.service;

import com.sclm.multitanant.dto.BatchLogEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 배치 실행 이력을 인메모리로 관리한다.
 * 최대 MAX_SIZE 건을 유지하며, 최신 항목이 앞에 온다.
 */
@Component
public class BatchLogStore {

    private static final int MAX_SIZE = 50;
    private final Deque<BatchLogEntry> logs = new ConcurrentLinkedDeque<>();

    public void add(BatchLogEntry entry) {
        logs.addFirst(entry);
        while (logs.size() > MAX_SIZE) {
            logs.removeLast();
        }
    }

    public List<BatchLogEntry> getRecent() {
        return new ArrayList<>(logs);
    }

    public void clear() {
        logs.clear();
    }
}
