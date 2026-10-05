"""F1 x K: a call cut off mid-flight by a core-api SIGKILL must surface as OUTCOME_UNKNOWN (F1),
travel through the outbox and Kafka, and raise an OUTCOME_UNKNOWN alert (K4).

Usage:
    python measure_unknown_flow.py <env-file> [--kill-after SECONDS] [--unknown-wait SECONDS]

Prints identifiers, statuses and timings only.
"""

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from measure_broker_outage import (  # noqa: E402
    CORE_URL_TEMPLATE, DEFAULT_CORE_PORT, MeasureError, Stack, log, read_env, start_run,
)
from measure_consumer_crash import wait_healthy  # noqa: E402

POLL_SECONDS = 2
SETTLE_SECONDS = 120


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("env_file", type=Path)
    parser.add_argument("--kill-after", type=float, default=0.4)
    parser.add_argument("--unknown-wait", type=int, default=70)
    args = parser.parse_args()
    env = read_env(args.env_file.resolve())
    stack = Stack(args.env_file.resolve())
    base_url = CORE_URL_TEMPLATE.format(port=env.get("CORE_API_PORT", DEFAULT_CORE_PORT))
    try:
        run_id = start_run(base_url, env)
        time.sleep(args.kill_after)
        stack.run("kill", "core-api", what="kill core-api")
        finance = stack.run("exec", "-T", "gateway", "sh", "-c", "echo $MOCK_FINANCE_URL", what="gateway env").strip()
        log(f"{run_id} started; core-api killed {args.kill_after}s later; gateway finance target={finance}")
        stack.run("start", "core-api", what="start core-api")
        wait_healthy(stack)
        log("core-api restarted")
        rows = stack.sql(f"select audit_event_id, status from audit_events where agent_run_id = '{run_id}'")
        log(f"audit rows right after restart: {rows}")
        if not rows:
            raise MeasureError("the kill landed before the audit start record; rerun with a larger --kill-after")
        if len(rows) != 1:
            raise MeasureError(f"expected one audit row for the run, found {len(rows)}")
        audit_id = rows[0][0]
        time.sleep(args.unknown_wait)
        deadline = time.monotonic() + SETTLE_SECONDS
        while True:
            status = stack.sql(f"select status from audit_events where audit_event_id = '{audit_id}'")[0][0]
            events = [r[0] for r in stack.sql(
                f"select event_type from tool_call_event_outbox where audit_event_id = '{audit_id}' order by id")]
            published = int(stack.sql(
                f"select count(*) from tool_call_event_outbox where audit_event_id = '{audit_id}'"
                " and published_at is not null")[0][0])
            consumed = int(stack.sql(
                "select count(*) from consumed_events c join tool_call_event_outbox o on o.event_id = c.event_id"
                f" where c.consumer_name = 'anomaly-alert' and o.audit_event_id = '{audit_id}'")[0][0])
            alerts = stack.sql(
                "select a.rule, a.window_start from security_alerts a join tool_call_event_outbox o"
                " on o.event_type = 'TOOL_CALL_OUTCOME_UNKNOWN'"
                " and a.agent_id = o.payload::jsonb->>'agentId'"
                " and a.window_start = to_timestamp(floor(extract(epoch from"
                " (o.payload::jsonb->>'occurredAt')::timestamptz) / 60) * 60)"
                " and a.created_at >= o.created_at"
                f" where a.rule = 'OUTCOME_UNKNOWN' and o.audit_event_id = '{audit_id}'")
            if (alerts and published == len(events) and consumed == len(events)) or time.monotonic() > deadline:
                break
            time.sleep(POLL_SECONDS)
    except MeasureError as error:
        log(f"INVALID RUN: {error}")
        return 1
    log(f"RESULT audit status={status} events={events} published={published}/{len(events)}"
        f" consumed={consumed}/{len(events)} unknown_alert={'yes' if alerts else 'no'}")
    ok = (status == "OUTCOME_UNKNOWN" and events == ["TOOL_CALL_STARTED", "TOOL_CALL_OUTCOME_UNKNOWN"]
          and published == len(events) and consumed == len(events) and bool(alerts))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
