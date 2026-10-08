package com.neuroplan.auth.config;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T5 DR 전환 중 ROSA Backend가 기존 Writer에 사용자 데이터를 추가로 기록하지 못하게 한다.
 * 로그인·토큰 갱신·로그아웃은 세션 유지에 필요한 인증 동작이므로 명시적으로 허용한다.
 */
@Component
public class DemoWriteFenceFilter extends OncePerRequestFilter {
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> AUTH_SESSION_OPERATIONS = Set.of(
            "POST /api/auth/login",
            "POST /api/auth/refresh",
            "POST /api/auth/logout",
            "POST /api/auth/reauth",
            "DELETE /api/auth/reauth"
    );

    private final boolean enabled;

    public DemoWriteFenceFilter(@Value("${app.demo-write-fence.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !enabled
                || !path.startsWith("/api/")
                || isAuthenticationSessionOperation(request.getMethod(), path)
                || !WRITE_METHODS.contains(request.getMethod());
    }

    private boolean isAuthenticationSessionOperation(String method, String path) {
        return AUTH_SESSION_OPERATIONS.contains(method + " " + path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":503,\"error\":\"Service Unavailable\","
                        + "\"message\":\"DEMO_WRITE_FENCE is enabled; write requests are temporarily blocked.\"}"
        );
    }
}
