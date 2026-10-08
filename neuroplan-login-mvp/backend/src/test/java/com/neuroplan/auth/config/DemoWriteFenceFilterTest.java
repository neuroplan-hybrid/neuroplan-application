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
    void blocksAuthenticationSessionWriteWhenFenceIsEnabled() throws Exception {
        DemoWriteFenceFilter filter = new DemoWriteFenceFilter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
    }

    @Test
    void blocksReadOperationThatPerformsLazyWriteWhenFenceIsEnabled() throws Exception {
        DemoWriteFenceFilter filter = new DemoWriteFenceFilter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/quota");
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
    }

    @Test
    void allowsReadOnlyBusinessAndHealthRequestsWhenFenceIsEnabled() throws Exception {
        DemoWriteFenceFilter filter = new DemoWriteFenceFilter(true);
        MockHttpServletRequest learningState = new MockHttpServletRequest("GET", "/api/learning/state");
        MockHttpServletRequest routingHealth = new MockHttpServletRequest("GET", "/actuator/health/routing");
        AtomicBoolean learningStateChainCalled = new AtomicBoolean(false);
        AtomicBoolean routingHealthChainCalled = new AtomicBoolean(false);

        filter.doFilter(learningState, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> learningStateChainCalled.set(true));
        filter.doFilter(routingHealth, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> routingHealthChainCalled.set(true));

        assertThat(learningStateChainCalled).isTrue();
        assertThat(routingHealthChainCalled).isTrue();
    }

    @Test
    void allowsWriteWhenFenceIsDisabled() throws Exception {
        DemoWriteFenceFilter filter = new DemoWriteFenceFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/learning/plans/1/steps/1");
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
    }
}
