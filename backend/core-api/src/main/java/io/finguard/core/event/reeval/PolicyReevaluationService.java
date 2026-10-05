package io.finguard.core.event.reeval;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.dao.DataAccessException;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.core.event.EventRelayProperties;
import jakarta.annotation.PreDestroy;

/**
 * 정책 변경 재평가: 도구 호출 이벤트 토픽을 처음부터(시작 시점의 끝 오프셋까지) 다시 읽어, 판정 입력이
 * 있는 확정 이벤트를 후보 정책으로 판정하고 원래 판정과 비교한다.
 *
 * <p>Kafka를 쓰는 이유가 여기 있다 — 운영 감사 테이블을 전수 스캔하지 않고, 경보 소비자와 무관하게
 * 자기 위치에서 같은 이벤트를 다시 읽는다. consumer group을 쓰지 않고 파티션을 직접 지정해(assign+seek)
 * 읽으므로 경보 소비자의 오프셋에 영향을 주지 않는다.
 *
 * <p>레코드마다 결과와 진행 오프셋을 같은 트랜잭션에 저장한다. 진행 오프셋이 기대값과 같을 때만 갱신하므로
 * 중단 뒤 다시 시작해도 같은 레코드를 두 번 세지 않는다. 판정 입력이 없는 이벤트는 사유별로 센다 —
 * 지어내서 판정하지 않는다.
 */
@Service
public class PolicyReevaluationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyReevaluationService.class);
    private static final int PARTITION = 0;
    /** 끝에 닿지 못한 채 이만큼 아무것도 읽지 못하면 실패로 남긴다(브로커 장애 등). 단일 스레드를 막지 않는다. */
    private static final Duration NO_PROGRESS_LIMIT = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate recordTransaction;
    private final CandidatePolicyClient candidate;
    private final KafkaProperties kafkaProperties;
    private final EventRelayProperties relayProperties;
    private final ReevaluationProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "policy-reevaluation");
        thread.setDaemon(true);
        return thread;
    });

    public PolicyReevaluationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            CandidatePolicyClient candidate,
            KafkaProperties kafkaProperties,
            EventRelayProperties relayProperties,
            ReevaluationProperties properties,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.recordTransaction = new TransactionTemplate(transactionManager);
        this.candidate = candidate;
        this.kafkaProperties = kafkaProperties;
        this.relayProperties = relayProperties;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 실행을 만들고(정책 해시·오프셋 범위 고정) 백그라운드에서 돌린다. 실행 ID를 돌려준다. */
    public String start(String label) {
        String policyHash = candidate.policyHash();
        String runId = "REEVAL-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        TopicPartition partition = partition();
        long start;
        long end;
        try (KafkaConsumer<String, String> consumer = consumer()) {
            start = consumer.beginningOffsets(List.of(partition)).get(partition);
            end = consumer.endOffsets(List.of(partition)).get(partition);
        }
        jdbc.update(
                "insert into policy_reevaluation_runs (run_id, label, candidate_policy_hash, topic, start_offset,"
                        + " end_offset_exclusive, next_offset, status) values (?, ?, ?, ?, ?, ?, ?, 'RUNNING')",
                runId, label, policyHash, relayProperties.topic(), start, end, start);
        log.info("Policy reevaluation started runId={} offsets=[{}, {}) policyHash={}", runId, start, end, policyHash);
        executor.submit(() -> execute(runId));
        return runId;
    }

    /** 멈춘(FAILED) 실행을 next_offset부터 이어 간다. 정책이 바뀌었거나 FAILED가 아니면 거절한다. */
    public void resume(String runId) {
        Map<String, Object> run = jdbc.queryForMap("select * from policy_reevaluation_runs where run_id = ?", runId);
        if (!"FAILED".equals(run.get("status"))) {
            throw new IllegalStateException("Only a failed run can be resumed");
        }
        if (!candidate.policyHash().equals(run.get("candidate_policy_hash"))) {
            throw new IllegalStateException("Candidate policy changed since the run started; start a new run");
        }
        jdbc.update("update policy_reevaluation_runs set status = 'RUNNING', failure = null"
                + " where run_id = ? and status = 'FAILED'", runId);
        executor.submit(() -> execute(runId));
    }

    /**
     * 기동할 때 RUNNING으로 남은 실행은 이전 프로세스가 끝내지 못한 것이다(이 인스턴스 하나가 유일한 실행자).
     * 중단됨으로 바꿔 재개할 수 있게 한다 — 영원히 RUNNING으로 남지 않게.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void markInterruptedRuns() {
        int interrupted;
        try {
            interrupted = jdbc.update("update policy_reevaluation_runs set status = 'FAILED',"
                    + " failure = 'INTERRUPTED' where status = 'RUNNING'");
        } catch (DataAccessException exception) {
            // 정리 작업이 기동을 막지 않는다(예: 스키마를 만들지 않는 DDL 추출 컨텍스트). 남은 실행은 재개 API로 다룬다.
            log.warn("Could not check for interrupted policy reevaluation runs: {}",
                    exception.getClass().getSimpleName());
            return;
        }
        if (interrupted > 0) {
            log.warn("Marked {} interrupted policy reevaluation runs as FAILED (resumable)", interrupted);
        }
    }

    /** 실행 하나를 끝 오프셋까지 처리한다. 테스트에서 직접 부를 수 있게 공개한다. */
    public void execute(String runId) {
        try {
            Map<String, Object> run = jdbc.queryForMap(
                    "select next_offset, end_offset_exclusive, candidate_policy_hash from policy_reevaluation_runs"
                            + " where run_id = ?", runId);
            long next = ((Number) run.get("next_offset")).longValue();
            long end = ((Number) run.get("end_offset_exclusive")).longValue();
            String policyHash = (String) run.get("candidate_policy_hash");
            long lastProgress = System.nanoTime();
            TopicPartition partition = partition();
            try (KafkaConsumer<String, String> consumer = consumer()) {
                consumer.assign(List.of(partition));
                consumer.seek(partition, next);
                while (next < end) {
                    // 한 실행은 한 정책으로만 판정한다. 도중에 후보 정책이 바뀌면 결과가 섞이므로 멈춘다.
                    if (!candidate.policyHash().equals(policyHash)) {
                        throw new IllegalStateException("CANDIDATE_POLICY_CHANGED");
                    }
                    var records = consumer.poll(properties.pollTimeout());
                    if (!records.isEmpty()) {
                        lastProgress = System.nanoTime();
                    } else if (System.nanoTime() - lastProgress > NO_PROGRESS_LIMIT.toNanos()) {
                        throw new IllegalStateException("NO_PROGRESS");
                    }
                    if (records.isEmpty() && consumer.position(partition) >= end) {
                        // 끝에 닿았는데 범위를 다 읽지 못했다 — 중간이 비었다. 조용히 완료로 두지 않는다.
                        throw new IllegalStateException("Reevaluation range has a gap");
                    }
                    for (ConsumerRecord<String, String> record : records) {
                        if (record.offset() >= end) {
                            break;
                        }
                        if (record.offset() < next) {
                            continue;
                        }
                        long offset = record.offset();
                        recordTransaction.executeWithoutResult(status -> apply(runId, offset, record.value()));
                        next = offset + 1;
                    }
                }
            }
            jdbc.update("update policy_reevaluation_runs set status = 'COMPLETED', completed_at = now()"
                    + " where run_id = ?", runId);
            log.info("Policy reevaluation completed runId={}", runId);
        } catch (RuntimeException exception) {
            String failure = exception instanceof IllegalStateException && exception.getMessage() != null
                    && exception.getMessage().matches("[A-Z_]+")
                    ? exception.getMessage()
                    : exception.getClass().getSimpleName();
            log.warn("Policy reevaluation failed runId={} cause={}", runId, failure);
            try {
                jdbc.update("update policy_reevaluation_runs set status = 'FAILED', failure = ? where run_id = ?",
                        failure, runId);
            } catch (RuntimeException markFailure) {
                // 실패 기록마저 못 하면 로그로 남긴다. 다음 기동 때 RUNNING이 중단됨으로 바뀐다.
                log.error("Could not mark policy reevaluation run as failed runId={}", runId, markFailure);
            }
        }
    }

    private void apply(String runId, long offset, String value) {
        Outcome outcome = classify(runId, offset, value);
        // 진행 오프셋이 이 레코드를 가리킬 때만 센다 — 이어 가기·중복 실행에서 같은 레코드를 두 번 세지 않는다.
        int advanced = jdbc.update(
                "update policy_reevaluation_runs set next_offset = ?, " + outcome.column + " = " + outcome.column
                        + " + 1" + (outcome.changed ? ", changed = changed + 1" : "")
                        + " where run_id = ? and next_offset = ?",
                offset + 1, runId, offset);
        if (advanced == 0) {
            throw new IllegalStateException("Reevaluation offset moved under the run");
        }
    }

    private Outcome classify(String runId, long offset, String value) {
        JsonNode event;
        try {
            event = value == null ? null : objectMapper.readTree(value);
        } catch (IOException exception) {
            event = null;
        }
        if (event == null || !event.isObject()) {
            return Outcome.UNREADABLE;
        }
        String type = event.path("eventType").asText();
        if (!type.equals("TOOL_CALL_FINALIZED") && !type.equals("TOOL_CALL_OUTCOME_RESOLVED")) {
            return Outcome.NOT_AN_OUTCOME;
        }
        String eligibility = event.path("replayEligibility").asText();
        if (eligibility.equals("NO_POLICY_DECISION")) {
            return Outcome.NO_POLICY_DECISION;
        }
        if (!eligibility.equals("ELIGIBLE") || !event.has("policyInput") || !event.path("decision").isTextual()) {
            return Outcome.INPUT_MISSING;
        }
        String original = event.get("decision").asText();
        String candidateDecision = candidate.decide(event.get("policyInput"));
        boolean changed = !original.equals(candidateDecision);
        int inserted = jdbc.update(
                "insert into policy_reevaluation_results (run_id, event_id, topic_offset, audit_event_id, event_type,"
                        + " original_decision, candidate_decision, changed) values (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " on conflict do nothing",
                runId, UUID.fromString(event.path("eventId").asText()), offset, event.path("auditEventId").asText(),
                type, original, candidateDecision, changed);
        if (inserted == 0) {
            // 같은 eventId를 이미 이 실행에서 판정했다(릴레이 재전송). 판정 집계에 두 번 넣지 않는다.
            return Outcome.DUPLICATE;
        }
        return changed ? Outcome.EVALUATED_CHANGED : Outcome.EVALUATED;
    }

    private TopicPartition partition() {
        return new TopicPartition(relayProperties.topic(), PARTITION);
    }

    private KafkaConsumer<String, String> consumer() {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties(null);
        // 그룹 없이 읽는다 — 경보 소비자의 오프셋과 무관하고, 커밋할 오프셋도 없다(진행은 DB에 둔다).
        config.remove(ConsumerConfig.GROUP_ID_CONFIG);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 시작 위치가 보존 기간으로 이미 지워졌으면 끝으로 건너뛰지 말고 실패한다 — 재생 창 밖이다.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(config);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
    }

    private enum Outcome {
        EVALUATED("evaluated", false),
        EVALUATED_CHANGED("evaluated", true),
        NO_POLICY_DECISION("no_policy_decision", false),
        INPUT_MISSING("input_missing", false),
        NOT_AN_OUTCOME("not_an_outcome", false),
        DUPLICATE("duplicate", false),
        UNREADABLE("unreadable", false);

        private final String column;
        private final boolean changed;

        Outcome(String column, boolean changed) {
            this.column = column;
            this.changed = changed;
        }
    }
}
