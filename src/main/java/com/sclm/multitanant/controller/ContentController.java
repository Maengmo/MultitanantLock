package com.sclm.multitanant.controller;

import com.sclm.multitanant.dto.ContentCreateRequest;
import com.sclm.multitanant.entity.Content;
import com.sclm.multitanant.entity.FormDefinition;
import com.sclm.multitanant.service.ContentService;
import com.sclm.multitanant.service.FormService;
import com.sclm.multitanant.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/content")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;
    private final FormService formService;

    /** 테넌트별 콘텐츠 목록 */
    @GetMapping
    public String list(Model model) {
        String tenantId = TenantContext.get();
        List<Content> contents = contentService.findAll();
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("contents", contents);
        return "content/list";
    }

    /** 동적 폼 - 테넌트별 필드가 다름 */
    @GetMapping("/new")
    public String newForm(Model model) {
        String tenantId = TenantContext.get();
        List<FormDefinition> fields = formService.getFields(tenantId, "CONTENT_CREATE");
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("fields", fields);
        model.addAttribute("request", new ContentCreateRequest());
        return "content/form";
    }

    /** 콘텐츠 생성 */
    @PostMapping
    public String create(@ModelAttribute ContentCreateRequest request,
                         @RequestParam java.util.Map<String, String> allParams,
                         RedirectAttributes redirectAttributes) {
        // extra 필드는 파라미터명이 "extra_fieldKey" 형태로 들어옴
        java.util.Map<String, String> extra = new java.util.LinkedHashMap<>();
        allParams.forEach((key, value) -> {
            if (key.startsWith("extra_")) {
                extra.put(key.substring(6), value);
            }
        });
        request.setExtraFields(extra);

        Content saved = contentService.create(request);
        redirectAttributes.addFlashAttribute("successMsg",
                "콘텐츠 #" + saved.getId() + " [" + saved.getTitle() + "] 이 등록되었습니다.");
        return "redirect:/content";
    }

    /** 콘텐츠 상세 */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Content content = contentService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + id));
        model.addAttribute("tenantId", TenantContext.get());
        model.addAttribute("content", content);
        return "content/detail";
    }
}
