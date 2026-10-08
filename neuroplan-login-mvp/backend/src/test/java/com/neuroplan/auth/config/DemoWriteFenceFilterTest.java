package com.neuroplan.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DemoWriteFenceFilterTest {
    @Test
    void blocksBusinessWriteWhenFenceIsEnabled() throws Exception {
        DemoWriteFenceFilter filter = new DemoWriteFenceFilter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/learning/plans/1/steps/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("DEMO_WRITE_FENCE");
        assertThat(chainCalled).isFalse();
    }

    @Test
    void allowsLoginAndTokenRefreshWhenFenceIsEnabled() throws Exception {
        DemoWriteFenceFilter filter = new DemoWriteFenceFilter(true);
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletRequest refresh = new MockHttpServletRequest("POST", "/api/auth/refresh");
        AtomicBoolean loginChainCalled = new AtomicBoolean(false);
        AtomicBoolean refreshChainCalled = new AtomicBoolean(false);

        filter.doFilter(login, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> loginChainCalled.set(true));
        filter.doFilter(refresh, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> refreshChainCalled.set(true));

        assertThat(loginChainCalled).isTrue();
        assertThat(refreshChainCalled).isTrue();
    }

    @Test
    void allowsReadRequestWhenFenceIsEnabled() throws Exception {
        DemoWriteFenceFilter filter = new DemoWriteFenceFilter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/learning/state");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
    }

    @Test
    void allowsWriteWhenFenceIsDisabled() throws Exception {
        DemoWriteFenceFilter filter = new DemoWriteFenceFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/learning/plans/1/steps/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
    }
}
