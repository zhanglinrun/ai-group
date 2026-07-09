package org.wwz.ai.trigger.http.auth;

import com.aigroup.common.constant.CommonConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 内部/管理接口令牌校验。
 * 这些路径不经 Gateway 路由，直连 8090 即可访问，必须用 X-Internal-Token 收口。
 * 令牌未配置（值为空）时默认放行，保持本地开发可用；配置后强制校验，失败返回 403。
 * 生效路径由 {@code BaseFilterConfig} 的注册 urlPatterns 决定（/api/v1/admin/** 与 /data/**）。
 */
public class InternalApiTokenFilter extends OncePerRequestFilter {

    private final String internalToken;

    public InternalApiTokenFilter(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (StringUtils.isBlank(internalToken)) {
            filterChain.doFilter(request, response);
            return;
        }
        // CORS 预检请求按规范不携带自定义头，放行交给后续 CORS 过滤器处理；实际请求仍强制校验
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(CommonConstant.HEADER_INTERNAL_TOKEN);
        if (!internalToken.equals(provided)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return;
        }
        // 经网关转发的请求（运营端浏览器调用）额外要求 ADMIN 角色：
        // 网关对所有已登录路由都会注入内部令牌，若不校验角色，普通用户 JWT 也能透传到管理接口。
        // 未经网关的直连调用（运维脚本/内部服务）保持原“仅内部令牌”语义。
        if ("true".equalsIgnoreCase(request.getHeader(CommonConstant.HEADER_GATEWAY_REQUEST))) {
            String role = request.getHeader(CommonConstant.HEADER_ROLE);
            if (!"ADMIN".equalsIgnoreCase(role)) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
