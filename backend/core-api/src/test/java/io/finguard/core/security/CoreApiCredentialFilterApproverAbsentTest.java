package io.finguard.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 승인자를 설정하지 않으면 APPROVER가 없다. 어떤 토큰도 그 역할이 되지 못한다(docs/04 §2). */
class CoreApiCredentialFilterApproverAbsentTest {

    private final CoreApiCredentialFilter filter = new CoreApiCredentialFilter(
            new CoreApiProperties("viewer-secret", "operator-secret", "EMP-101", null, null),
            mock(CoreApiAuthEventRecorder.class));

    @Test
    void anUnconfiguredApproverCredentialIsJustAnUnknownCredential() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer approver-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void theOperatorStillAuthenticatesWithoutAnApprover() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer operator-secret");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        CoreApiPrincipal principal =
                (CoreApiPrincipal) request.getAttribute(CoreApiCredentialFilter.PRINCIPAL_ATTRIBUTE);
        assertThat(principal.role()).isEqualTo(CoreApiRole.OPERATOR);
    }
}
