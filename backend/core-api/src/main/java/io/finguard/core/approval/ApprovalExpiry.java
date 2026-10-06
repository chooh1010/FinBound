package io.finguard.core.approval;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.repository.ApprovalRequestRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 기한이 지난 승인 요청을 EXPIRED로 바꾼다. 조정 배치(OutcomeReconciler)와 같은 모양이다.
 *
 * <p>행마다 독립 트랜잭션에서 행을 잠그고, 그다음 DB 시각을 읽어 상태와 기한을 다시 확인한다. 고른 뒤 잠그기 전에
 * 승인되거나 쓰였으면 할 일이 없다. 한 행의 실패로 배치를 멈추지 않는다.
 */
@Service
@EnableConfigurationProperties({ApprovalExpiryProperties.class, ApprovalProperties.class})
public class ApprovalExpiry {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpiry.class);

    private final ApprovalRequestRepository approvalRequests;
    private final ApprovalEventWriter approvalEvents;
    private final TransactionTemplate rowTransaction;
    private final ApprovalExpiryProperties properties;
    private final Counter expiredCounter;
    private final Counter rowFailureCounter;

    public ApprovalExpiry(
            ApprovalRequestRepository approvalRequests,
            ApprovalEventWriter approvalEvents,
            PlatformTransactionManager transactionManager,
            ApprovalExpiryProperties properties,
            MeterRegistry meterRegistry) {
        this.approvalRequests = approvalRequests;
        this.approvalEvents = approvalEvents;
        this.rowTransaction = new TransactionTemplate(transactionManager);
        this.rowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.expiredCounter = Counter.builder("approval.request.expired")
                .description("Approval requests expired by the batch (committed)")
                .register(meterRegistry);
        this.rowFailureCounter = Counter.builder("approval.request.expiry.row.failures")
                .description("Approval requests the expiry batch could not process; retried next run")
                .register(meterRegistry);
    }

    public int expireOnce() {
        // 고를 때의 기준 시각은 한 번 정해 둔다(인덱스가 기한 범위로 찾게). 확정은 행을 잠근 뒤 DB 시각으로 다시 한다.
        List<String> dueIds = approvalRequests.findDueIds(approvalRequests.databaseNow(), properties.batchSize());
        int expired = 0;
        for (String approvalRequestId : dueIds) {
            if (expireIfStillDue(approvalRequestId)) {
                expired++;
            }
        }
        return expired;
    }

    /** 한 행을 다시 확인하고 만료시킨다. 고른 뒤 승인·사용됐거나 다른 쪽이 잡고 있으면 아무것도 하지 않는다. */
    boolean expireIfStillDue(String approvalRequestId) {
        Boolean expired;
        try {
            expired = rowTransaction.execute(status -> {
                ApprovalRequest request = approvalRequests.findForUpdateSkipLocked(approvalRequestId).orElse(null);
                if (request == null || !request.expireIfDue(approvalRequests.databaseNow())) {
                    return false;
                }
                // 저장과 이번 전이의 이벤트 v2 기록을 한 곳에서 한다(ApprovalEventWriter).
                approvalEvents.save(request);
                return true;
            });
        } catch (RuntimeException exception) {
            rowFailureCounter.increment();
            log.error("Approval expiry failed for a row approvalRequestId={}", approvalRequestId, exception);
            return false;
        }
        if (!Boolean.TRUE.equals(expired)) {
            return false;
        }
        expiredCounter.increment();
        log.info("Approval request expired approvalRequestId={}", approvalRequestId);
        return true;
    }
}
