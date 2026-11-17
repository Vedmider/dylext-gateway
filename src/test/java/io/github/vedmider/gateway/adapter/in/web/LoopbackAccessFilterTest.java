package io.github.vedmider.gateway.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LoopbackAccessFilterTest {

    private MockHttpServletResponse pass(LoopbackAccessFilter filter, String path, String addr,
                                         String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRemoteAddr(addr);
        if (token != null) {
            req.addHeader("X-Gateway-Token", token);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void loopbackCallerIsAllowed() throws Exception {
        var res = pass(new LoopbackAccessFilter(true, ""), "/mcp", "127.0.0.1", null);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void nonLoopbackCallerToGuardedPathIsForbidden() throws Exception {
        var res = pass(new LoopbackAccessFilter(true, ""), "/api/ingest", "10.0.0.5", null);
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void nonLoopbackWithValidTokenIsAllowed() throws Exception {
        var res = pass(new LoopbackAccessFilter(true, "s3cret"), "/mcp", "10.0.0.5", "s3cret");
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void unguardedPathIsNeverBlocked() throws Exception {
        var res = pass(new LoopbackAccessFilter(true, ""), "/actuator/health", "10.0.0.5", null);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void disablingLoopbackOnlyAllowsEveryone() throws Exception {
        var res = pass(new LoopbackAccessFilter(false, ""), "/mcp", "10.0.0.5", null);
        assertThat(res.getStatus()).isEqualTo(200);
    }
}
