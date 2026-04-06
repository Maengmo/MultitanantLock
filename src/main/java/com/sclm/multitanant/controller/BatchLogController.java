package com.sclm.multitanant.controller;

import com.sclm.multitanant.dto.BatchLogEntry;
import com.sclm.multitanant.service.BatchLogStore;
import com.sclm.multitanant.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequestMapping("/batch-log")
@RequiredArgsConstructor
public class BatchLogController {

    private final BatchLogStore batchLogStore;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("tenantId", TenantContext.get());
        model.addAttribute("logs", batchLogStore.getRecent());
        return "batch-log/index";
    }

    /** 폴링용 JSON API */
    @GetMapping("/api")
    @ResponseBody
    public ResponseEntity<List<BatchLogEntry>> api() {
        return ResponseEntity.ok(batchLogStore.getRecent());
    }

    @PostMapping("/clear")
    @ResponseBody
    public ResponseEntity<Void> clear() {
        batchLogStore.clear();
        return ResponseEntity.ok().build();
    }
}
