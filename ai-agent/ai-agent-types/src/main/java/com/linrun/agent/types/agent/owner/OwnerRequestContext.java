package com.linrun.agent.types.agent.owner;

/**
 * 当前请求登录用户上下文（ownerId = Gateway 注入的 userId）。
 */
public final class OwnerRequestContext {

    private static final ThreadLocal<Long> OWNER_HOLDER = new ThreadLocal<>();

    private OwnerRequestContext() {
    }

    public static void bind(Long ownerId) {
        OWNER_HOLDER.set(ownerId);
    }

    public static Long currentOwnerId() {
        return OWNER_HOLDER.get();
    }

    public static Long requireOwnerId() {
        Long ownerId = OWNER_HOLDER.get();
        if (ownerId == null) {
            throw new IllegalStateException("未登录，缺少用户身份");
        }
        return ownerId;
    }

    public static String requireOwnerIdAsString() {
        return String.valueOf(requireOwnerId());
    }

    public static void clear() {
        OWNER_HOLDER.remove();
    }
}
