package com.sclm.multitanant.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class ContentCreateRequest {
    private String title;
    /** 테넌트별 동적 필드 (fieldKey → value) */
    private Map<String, String> extraFields;
}
