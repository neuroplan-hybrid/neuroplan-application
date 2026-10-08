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
 * T5 DR 전환 중 ROSA Backend가 기존 Writer에 DB 변경을 추가로 기록하지 못하게 한다.
 * 인증 API도 세션·쿼터를 기록하므로, Fence가 켜진 동안에는 API 쓰기를 모두 차단한다.
 */
@Component
public class DemoWriteFenceFilter extends OncePerRequestFilter {
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    // 아래 조회 API는 호출 시 내부에서 계정별 기본 데이터를 upsert한다.
    private static final Set<String> LAZY_WRITE_READ_OPERATIONS = Set.of(
            "GET /api/ai/quota",
            "GET /api/ai/preferences"
    );

    private final boolean enabled;

    public DemoWriteFenceFilter(@Value("${app.demo-write-fence.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String operation = request.getMethod() + " " + path;
        return !enabled
                || !path.startsWith("/api/")
                || (!WRITE_METHODS.contains(request.getMethod())
                && !LAZY_WRITE_READ_OPERATIONS.contains(operation));
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
                        + "\"message\":\"DEMO_WRITE_FENCE is enabled; database-changing requests are temporarily blocked.\"}"
        );
    }
}
