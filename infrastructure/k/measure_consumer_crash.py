"""K scenario 3: SIGKILL core-api (relay + alert consumer) right after a burst of tool calls.

Usage:
    python measure_consumer_crash.py <env-file> [--calls N] [--unknown-wait SECONDS]

The burst mixes normal calls (ALLOW) and CASE_SCOPE_ATTACK calls (policy BLOCK). Right after the
burst core-api is killed, so some events are typically committed to the DB by the consumer but not yet
acknowledged to Kafka. After restart the script checks, from the database:

- this run's AgentRuns produced exactly 2 events each (STARTED + FINALIZED, or STARTED +
  OUTCOME_UNKNOWN for a call cut off by the kill) and every one was processed by the alert consumer;
- per (agent, 60 s bucket), the BLOCK_BURST counter grew by exactly the number of this run's BLOCK
  outcomes in that bucket, and no other BLOCK_BURST counter changed (a redelivered event counted twice,
  a lost one, or unrelated traffic would all break the equality);
- at most one alert per rule/agent/bucket.

Calls in flight at the kill never get an outcome; F1 later turns them into OUTCOME_UNKNOWN, whose
events flow through the same consumer. Prints identifiers, counts and timings only.
"""

import argparse
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from measure_broker_outage import (  # noqa: E402
    CORE_URL_TEMPLATE, DEFAULT_CORE_PORT, MeasureError, Stack, bad_runs, consumed_events, log, read_env,
    run_event_types, run_events, run_filter, start_run, unpublished,
)

TERMINAL_EVENTS = {"TOOL_CALL_FINALIZED", "TOOL_CALL_OUTCOME_UNKNOWN"}

# Short gap so the kill lands while the relay/consumer still have work in flight (rate limits: 10/s, 30/min).
RUN_GAP_SECONDS = 0.3
HEALTH_WAIT_SECONDS = 180
SETTLE_WAIT_SECONDS = 180


def wait_healthy(stack: Stack) -> None:
    deadline = time.monotonic() + HEALTH_WAIT_SECONDS
    while time.monotonic() < deadline:
        out = stack.run("ps", "core-api", "--format", "{{.Status}}", what="ps core-api")
        if "healthy" in out and "unhealthy" not in out:
            return
        time.sleep(2)
    raise MeasureError("core-api did not become healthy")


def scalar(stack: Stack, sql: str) -> int:
    return int(stack.sql(sql)[0][0])


BUCKET_SQL = "(floor(extract(epoch from {ts}) / 60) * 60)::bigint"


def block_outcomes(stack: Stack, run_ids: list[str]) -> Counter:
    """(agentId, bucket epoch) -> number of this run's BLOCK outcome events."""
    bucket = BUCKET_SQL.format(ts="(o.payload::jsonb->>'occurredAt')::timestamptz")
    rows = stack.sql(f"select o.payload::jsonb->>'agentId', {bucket}, count(*) from tool_call_event_outbox o"
                     f" where {run_filter(run_ids)}"
                     " and o.event_type in ('TOOL_CALL_FINALIZED', 'TOOL_CALL_OUTCOME_RESOLVED')"
                     " and o.payload::jsonb->>'decision' = 'BLOCK' group by 1, 2")
    return Counter({(agent, int(b)): int(n) for agent, b, n in rows})


def block_counters(stack: Stack) -> Counter:
    bucket = BUCKET_SQL.format(ts="window_start")
    rows = stack.sql(f"select agent_id, {bucket}, event_count from alert_counters where rule = 'BLOCK_BURST'")
    return Counter({(agent, int(b)): int(n) for agent, b, n in rows})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("env_file", type=Path)
    parser.add_argument("--calls", type=int, default=14)
    parser.add_argument("--unknown-wait", type=int, default=75)
    args = parser.parse_args()
    env = read_env(args.env_file.resolve())
    stack = Stack(args.env_file.resolve())
    base_url = CORE_URL_TEMPLATE.format(port=env.get("CORE_API_PORT", DEFAULT_CORE_PORT))
    try:
        log(f"calls={args.calls} (every other one CASE_SCOPE_ATTACK)")
        counters_before = block_counters(stack)
        run_ids = []
        for i in range(args.calls):
            scenario = "CASE_SCOPE_ATTACK" if i % 2 else "NORMAL_CREDIT_SCORE"
            run_id = start_run(base_url, env, scenario)
            run_ids.append(run_id)
            log(f"call {i + 1}: {run_id} scenario={scenario}")
            if i == args.calls - 1:
                break
            time.sleep(RUN_GAP_SECONDS)
        # Kill right after the last call is accepted, before looking at anything, so that call is still
        # in flight. The snapshot below is taken after the kill: it shows how much work was left.
        stack.run("kill", "core-api", what="kill core-api")
        stored_at_kill = run_events(stack, run_ids)
        processed_at_kill = len(consumed_events(stack) & set(stored_at_kill))
        unpublished_at_kill = unpublished(stack)
        log(f"core-api killed (SIGKILL) right after the last call; at kill: outbox_events={len(stored_at_kill)}"
            f" unpublished={unpublished_at_kill} processed={processed_at_kill} of {2 * args.calls} expected")
        # The scenario only means something if the kill interrupted work; otherwise it is a plain restart.
        if processed_at_kill == 2 * args.calls:
            raise MeasureError("every event was already processed at the kill: nothing was interrupted")
        stack.run("start", "core-api", what="start core-api")
        wait_healthy(stack)
        log("core-api restarted and healthy")
        log(f"waiting {args.unknown_wait}s so F1 can turn calls cut off by the kill into OUTCOME_UNKNOWN")
        time.sleep(args.unknown_wait)

        deadline = time.monotonic() + SETTLE_WAIT_SECONDS
        while True:
            stored = run_events(stack, run_ids)
            consumed = consumed_events(stack)
            if len(stored) == 2 * args.calls and unpublished(stack) == 0 and set(stored) <= consumed:
                break
            if time.monotonic() > deadline:
                raise MeasureError("relay/consumer did not settle")
            time.sleep(2)
        return judge(stack, args.calls, run_ids, stored, consumed, counters_before)
    except MeasureError as error:
        log(f"INVALID RUN: {error}")
        return 1


def judge(stack: Stack, calls: int, run_ids: list[str], stored: dict, consumed: set[str],
          counters_before: Counter) -> int:
    wrong = bad_runs(run_event_types(stack, run_ids), TERMINAL_EVENTS)
    not_consumed = set(stored) - consumed
    blocks_this_run = block_outcomes(stack, run_ids)
    counters_after = block_counters(stack)
    counter_delta = Counter({k: counters_after[k] - counters_before[k]
                             for k in set(counters_after) | set(counters_before)
                             if counters_after[k] != counters_before[k]})
    unknown_events = scalar(stack, "select count(*) from tool_call_event_outbox"
                            " where event_type = 'TOOL_CALL_OUTCOME_UNKNOWN'")
    alerts = stack.sql("select rule, count(*) from security_alerts group by rule order by rule")
    duplicate_alerts = scalar(stack, "select count(*) from (select rule, agent_id, window_start from security_alerts"
                              " group by rule, rule_version, agent_id, window_start having count(*) > 1) d")
    log(f"RESULT this run: outbox_events={len(stored)} processed={len(consumed & set(stored))}"
        f" not_processed={len(not_consumed)} runs_without_exactly_started_plus_terminal={len(wrong)}")
    log(f"RESULT BLOCK outcomes this run={sum(blocks_this_run.values())} in {len(blocks_this_run)} buckets;"
        f" BLOCK_BURST counter growth={sum(counter_delta.values())} in {len(counter_delta)} buckets;"
        f" per-bucket equal={blocks_this_run == counter_delta}")
    log(f"RESULT OUTCOME_UNKNOWN events={unknown_events} alerts={dict((r, int(c)) for r, c in alerts)}"
        f" duplicate_alert_keys={duplicate_alerts}")
    if not blocks_this_run:
        log("RESULT no BLOCK outcome in this run: the counter check is vacuous")
    ok = (not wrong and len(stored) == 2 * calls and not not_consumed and blocks_this_run == counter_delta
          and sum(blocks_this_run.values()) > 0 and duplicate_alerts == 0)
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
