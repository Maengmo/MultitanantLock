package com.sclm.multitanant.controller;

import com.sclm.multitanant.service.ContentService;
import com.sclm.multitanant.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class MainController {

    private final ContentService contentService;

    @GetMapping("/")
    public String home(Model model) {
        String tenantId = TenantContext.get();
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("contentCount", contentService.count());
        model.addAttribute("tenantTheme", resolveTenantTheme(tenantId));
        model.addAttribute("tenantLabel", resolveTenantLabel(tenantId));
        return "index";
    }

    private String resolveTenantTheme(String tenantId) {
        return switch (tenantId) {
            case "townboard" -> "theme-townboard";
            case "joongang"  -> "theme-joongang";
            default          -> "theme-default";
        };
    }

    private String resolveTenantLabel(String tenantId) {
        return switch (tenantId) {
            case "townboard" -> "타운보드 (TOWNBOARD)";
            case "joongang"  -> "중앙일보 (JOONGANG)";
            default          -> tenantId.toUpperCase() + " Tenant";
        };
    }
}
