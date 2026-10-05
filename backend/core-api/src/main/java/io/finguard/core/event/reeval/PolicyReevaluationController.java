package io.finguard.core.event.reeval;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.finguard.core.security.CoreApiRole;
import io.finguard.core.security.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 정책 변경 재평가 실행과 결과 조회. 결과에는 식별자·판정만 담는다(원본 Prompt·금융 응답 없음).
 */
@RestController
public class PolicyReevaluationController {

    private final PolicyReevaluationService reevaluations;
    private final JdbcTemplate jdbc;

    public PolicyReevaluationController(PolicyReevaluationService reevaluations, JdbcTemplate jdbc) {
        this.reevaluations = reevaluations;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/policy-reevaluations")
    @RequiresRole(CoreApiRole.OPERATOR)
    public ResponseEntity<Map<String, String>> start(@Valid @RequestBody StartRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", reevaluations.start(request.label())));
    }

    @PostMapping("/api/v1/policy-reevaluations/{runId}/resume")
    @RequiresRole(CoreApiRole.OPERATOR)
    public ResponseEntity<Void> resume(@PathVariable String runId) {
        reevaluations.resume(runId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/v1/policy-reevaluations/{runId}")
    @RequiresRole({CoreApiRole.VIEWER, CoreApiRole.OPERATOR})
    public ResponseEntity<Map<String, Object>> find(@PathVariable String runId) {
        List<Map<String, Object>> runs = jdbc.queryForList(
                "select run_id, label, candidate_policy_hash, topic, start_offset, end_offset_exclusive, next_offset,"
                        + " status, evaluated, changed, no_policy_decision, input_missing, not_an_outcome, unreadable,"
                        + " duplicate,"
                        + " failure, created_at, completed_at from policy_reevaluation_runs where run_id = ?",
                runId);
        if (runs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>(runs.getFirst());
        body.put("changes", jdbc.queryForList(
                "select event_id, topic_offset, audit_event_id, event_type, original_decision, candidate_decision"
                        + " from policy_reevaluation_results where run_id = ? and changed order by topic_offset",
                runId));
        return ResponseEntity.ok(body);
    }

    public record StartRequest(@NotBlank @Size(max = 128) String label) {
    }
}
