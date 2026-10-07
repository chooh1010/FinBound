package io.finguard.gateway.client.impl;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.finguard.gateway.client.CoreClient;
import io.finguard.gateway.client.HttpFailures;
import io.finguard.gateway.dto.AuditOutcome;
import io.finguard.gateway.dto.AuditStart;
import io.finguard.gateway.dto.BehaviorHistory;
import io.finguard.gateway.dto.ResolvedContext;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.exception.AuditOutcomeConflictException;
import io.finguard.gateway.exception.AuditOutcomeRejectedException;
import io.finguard.gateway.exception.AuditWriteException;
import io.finguard.gateway.exception.BehaviorHistoryUnavailableException;
import io.finguard.gateway.exception.CoreUnavailableException;
import io.finguard.gateway.exception.DuplicateRequestException;
import io.finguard.gateway.identity.VerifiedAgentIdentity;

@Component
@Profile("real-core")
public class CoreClientImpl implements CoreClient {

    private static final String SERVICE_CREDENTIAL_HEADER = "X-FinGuard-Service-Credential";
    private static final String VERIFIED_AGENT_HEADER = "X-Verified-Agent-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACEPARENT_HEADER = "Traceparent";

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalCredential;

    public CoreClientImpl(@Value("${finguard.core.base-url}") String baseUrl,
                          @Value("${finguard.credentials.internal-service}") String internalCredential,
                          @Value("${finguard.timeouts.core-ms}") long timeoutMs) {
        this.restClient = RestClient.builder().requestFactory(requestFactory(timeoutMs)).build();
        this.baseUrl = baseUrl;
        this.internalCredential = internalCredential;
    }

    @Override
    public ResolvedContext resolveContext(VerifiedAgentIdentity identity,
                                          ToolCallRequest request,
                                          String requestId,
                                          String traceparent) {
        try {
            ResolvedContext response = restClient.post()
                .uri(baseUrl + "/internal/v1/context/resolve")
                .headers(headers -> internalHeaders(headers, identity.agentId(), requestId, traceparent))
                .body(Map.of(
                    "requestId", requestUuid(requestId),
                    "verifiedAgentId", identity.agentId(),
                    "agentRunId", request.agentRunId(),
                    "passportId", request.passportId(),
                    "targetConsumerId", request.targetConsumerId(),
                    "requestedTool", request.tool(),
                    "requestedData", Set.copyOf(request.requestedData())))
                .retrieve()
                .body(ResolvedContext.class);
            if (response == null || response.references() == null || response.scopeStatus() == null) {
                throw new CoreUnavailableException("Core context response is incomplete");
            }
            return response;
        } catch (RestClientException | InvalidMediaTypeException e) {
            throw new CoreUnavailableException("Core context API call failed", HttpFailures.sanitized(e));
        }
    }

    @Override
    public void createAudit(VerifiedAgentIdentity identity, AuditStart auditStart, String traceparent) {
        try {
            restClient.post()
                .uri(baseUrl + "/internal/v1/audits")
                .headers(headers -> internalHeaders(headers, identity.agentId(), auditStart.requestId(), traceparent))
                .body(auditStart)
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new DuplicateRequestException("Core audit request is duplicate", HttpFailures.sanitized(e));
            }
            throw new AuditWriteException("Core audit create failed", HttpFailures.sanitized(e));
        } catch (RestClientException | InvalidMediaTypeException e) {
            throw new AuditWriteException("Core audit create failed", HttpFailures.sanitized(e));
        }
    }

    @Override
    public void updateAuditOutcome(VerifiedAgentIdentity identity,
                                   String requestId,
                                   AuditOutcome outcome,
                                   String traceparent) {
        try {
            restClient.patch()
                .uri(baseUrl + "/internal/v1/audits/{requestId}/outcome", requestId)
                .headers(headers -> internalHeaders(headers, identity.agentId(), requestId, traceparent))
                .body(outcome)
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // 409만 따로 본다. 같은 결과의 재전송은 Core가 200으로 받으므로 409는 다른 결과와의 충돌이다.
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new AuditOutcomeConflictException("Core audit outcome conflicts with the recorded outcome",
                    HttpFailures.sanitized(e));
            }
            // 400은 Core가 본문 형식·불변식 위반으로 확실히 거절한 것이다(docs/04 §11 검사 순서). 다시 보내도
            // 같은 이유로 거절되므로 따로 센다. 401·403(자격 교체), 404, 408·429 등은 다시 보내면 될 수도 있어
            // 단정하지 않고 전달 미확인으로 둔다 — 끝내 기록되지 않았는지는 Core의 조정 배치가 판단한다.
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new AuditOutcomeRejectedException("Core audit outcome rejected", HttpFailures.sanitized(e));
            }
            throw new AuditWriteException("Core audit outcome update failed", HttpFailures.sanitized(e));
        } catch (RestClientException | InvalidMediaTypeException e) {
            throw new AuditWriteException("Core audit outcome update failed", HttpFailures.sanitized(e));
        }
    }

    @Override
    public BehaviorHistory behaviorHistory(VerifiedAgentIdentity identity,
                                           String window,
                                           String requestId,
                                           String traceparent) {
        try {
            BehaviorHistory response = restClient.get()
                .uri(baseUrl + "/internal/v1/agents/{agentId}/behavior-history?window={window}",
                    identity.agentId(), window)
                .headers(headers -> internalHeaders(headers, identity.agentId(), requestId, traceparent))
                .retrieve()
                .body(BehaviorHistory.class);
            if (response == null) {
                throw new BehaviorHistoryUnavailableException("Core behavior history response is empty", null);
            }
            return response;
        } catch (RestClientException | InvalidMediaTypeException e) {
            throw new BehaviorHistoryUnavailableException("Core behavior history API call failed",
                HttpFailures.sanitized(e));
        }
    }

    @Override
    public void recordAuthFailure(String requestId, String traceparent, String reasonCode) {
        try {
            restClient.post()
                .uri(baseUrl + "/internal/v1/security-events/auth-failure")
                .headers(headers -> {
                    headers.set(SERVICE_CREDENTIAL_HEADER, internalCredential);
                    headers.set(REQUEST_ID_HEADER, requestId);
                    setIfPresent(headers, TRACEPARENT_HEADER, traceparent);
                })
                .body(Map.of(
                    "requestId", requestId,
                    "traceId", traceparent == null ? "" : traceparent,
                    "eventType", "AUTH_FAILURE",
                    "reasonCode", reasonCode,
                    "credentialType", "AGENT_SERVICE",
                    "occurredAt", Instant.now()))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException | InvalidMediaTypeException e) {
            throw new CoreUnavailableException("Core security event API call failed", HttpFailures.sanitized(e));
        }
    }

    private void internalHeaders(HttpHeaders headers, String agentId, String requestId, String traceparent) {
        headers.set(SERVICE_CREDENTIAL_HEADER, internalCredential);
        headers.set(VERIFIED_AGENT_HEADER, agentId);
        headers.set(REQUEST_ID_HEADER, requestId);
        setIfPresent(headers, TRACEPARENT_HEADER, traceparent);
    }

    private void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }

    private String requestUuid(String requestId) {
        return UUID.fromString(requestId).toString();
    }

    private JdkClientHttpRequestFactory requestFactory(long timeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofMillis(timeoutMs))
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofMillis(timeoutMs));
        return factory;
    }
}
