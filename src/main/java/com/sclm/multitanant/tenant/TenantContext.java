package com.sclm.multitanant.tenant;

/**
 * 요청 단위로 현재 Tenant를 ThreadLocal에 저장/조회한다.
 * 요청 완료 후 반드시 clear() 해야 메모리 누수가 없다.
 */
public class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static String get() {
        return CURRENT_TENANT.get();
    }

    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
