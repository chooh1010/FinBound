"""K scenario 1: tool calls while the Kafka broker is down; prove no committed outbox event is lost.

Usage (from anywhere):
    python measure_broker_outage.py <env-file> [--before N] [--during N] [--mode stop|kill]

Needs the K stack up (see docker-compose.k.yml) with the relay enabled.
Prints only identifiers, counts, hashes and timings — never prompts, financial payloads or credentials.

The guarantee measured is "no committed outbox event is lost". Only events of THIS run are judged:
outbox rows whose audit event belongs to an AgentRun this script submitted, and there must be exactly
2 per call (STARTED + FINALIZED). For those events:
- outbox (eventId, key, payload hash) == topic (eventId header, record key, SHA-256 of the value);
- every one was processed by the alert consumer (eventId; the consumer does not keep a hash).
The whole retained topic is read (earliest to end offset) and the record count must match.
Duplicates on the topic are allowed (at-least-once) and reported, never hidden.
"""

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

PROJECT = "finguard-k"
INFRA_DIR = Path(__file__).resolve().parent.parent
COMPOSE_FILES = ["docker-compose.yml", "docker-compose.demo.yml", "docker-compose.expose.yml",
                 "f1/docker-compose.f1-repro.yml", "f1/docker-compose.f1-normal.yml", "k/docker-compose.k.yml"]
TOPIC = "finguard.tool-call.events.v1"
CORE_URL_TEMPLATE = "http://127.0.0.1:{port}"
DEFAULT_CORE_PORT = "18080"
INPUT_TEXT = "현재 고객의 신규 대출 심사자료 확인"
FINAL_STATUSES = ("COMPLETED", "ERROR")
RUN_GAP_SECONDS = 3.0
FINAL_WAIT_SECONDS = 30
DRAIN_WAIT_SECONDS = 180
COMMAND_TIMEOUT_SECONDS = 120


class MeasureError(Exception):
    """A precondition or step failed; the run is not valid evidence."""


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def log(message: str) -> None:
    print(f"[{now()}] {message}", flush=True)


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


class Stack:
    def __init__(self, env_file: Path) -> None:
        self.base = ["docker", "compose", "-p", PROJECT, "--project-directory", str(INFRA_DIR),
                     "--env-file", str(env_file)]
        for name in COMPOSE_FILES:
            self.base += ["-f", str(INFRA_DIR / name)]

    def run(self, *args: str, what: str) -> str:
        try:
            result = subprocess.run([*self.base, *args], capture_output=True, text=True, encoding="utf-8",
                                    timeout=COMMAND_TIMEOUT_SECONDS)
        except (subprocess.TimeoutExpired, OSError) as error:
            raise MeasureError(f"{what}: {type(error).__name__}") from None
        if result.returncode != 0:
            raise MeasureError(f"{what}: exit {result.returncode}")
        return result.stdout

    def sql(self, statement: str) -> list[list[str]]:
        out = self.run("exec", "-T", "postgres", "psql", "-U", "finguard", "-d", "finguard", "-A", "-t",
                       "-F", "|", "-c", statement, what="psql")
        return [line.split("|") for line in out.splitlines() if line]


def start_run(base_url: str, env: dict[str, str], scenario: str = "NORMAL_CREDIT_SCORE") -> str:
    body = json.dumps({"employeeId": env["OPERATOR_EMPLOYEE_ID"], "consumerId": "CUST-1001",
                       "taskType": "LOAN_REVIEW", "scenario": scenario,
                       "inputText": INPUT_TEXT}).encode("utf-8")
    request = urllib.request.Request(
        base_url + "/api/v1/agent-runs", data=body, method="POST",
        headers={"Authorization": f"Bearer {env['OPERATOR_CREDENTIAL']}", "Content-Type": "application/json",
                 "X-Request-Id": str(uuid.uuid4())})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())["agentRunId"]
    except urllib.error.HTTPError as error:
        raise MeasureError(f"agent-run create failed http={error.code}") from None
    except (urllib.error.URLError, OSError, ValueError, KeyError) as error:
        raise MeasureError(f"agent-run create failed: {type(error).__name__}") from None


def wait_final(stack: Stack, agent_run_id: str) -> str:
    """The call must finish normally: exactly one audit row, COMPLETED with an ALLOW decision."""
    deadline = time.monotonic() + FINAL_WAIT_SECONDS
    while time.monotonic() < deadline:
        rows = stack.sql(f"select status, coalesce(decision, '-') from audit_events where agent_run_id = '{agent_run_id}'")
        if len(rows) == 1 and rows[0][0] in FINAL_STATUSES:
            if rows[0] != ["COMPLETED", "ALLOW"]:
                raise MeasureError(f"{agent_run_id} finished as {rows[0]}, not COMPLETED/ALLOW")
            return rows[0][0]
        if len(rows) > 1:
            raise MeasureError(f"{agent_run_id} has {len(rows)} audit rows")
        time.sleep(0.5)
    raise MeasureError(f"{agent_run_id} did not finalize within {FINAL_WAIT_SECONDS}s")


def calls(stack: Stack, base_url: str, env: dict[str, str], count: int, label: str) -> list[str]:
    run_ids = []
    for _ in range(count):
        run_id = start_run(base_url, env)
        status = wait_final(stack, run_id)
        run_ids.append(run_id)
        log(f"{label}: {run_id} audit={status}")
        time.sleep(RUN_GAP_SECONDS)
    return run_ids


def unpublished(stack: Stack) -> int:
    return int(stack.sql("select count(*) from tool_call_event_outbox where published_at is null")[0][0])


def outbox_high_water(stack: Stack) -> int:
    return int(stack.sql("select coalesce(max(id), 0) from tool_call_event_outbox")[0][0])


def outbox_events(stack: Stack, above_id: int = 0) -> dict[str, str]:
    rows = stack.sql(f"select event_id, payload_hash from tool_call_event_outbox where id > {int(above_id)}")
    return {event_id: payload_hash for event_id, payload_hash in rows}


def run_filter(run_ids: list[str]) -> str:
    """SQL predicate selecting outbox rows (alias o) of the given AgentRuns."""
    if not run_ids or not all(re.fullmatch(r"[A-Za-z0-9_-]+", r) for r in run_ids):
        raise MeasureError("unexpected agentRunId format")
    ids = ", ".join(f"'{r}'" for r in run_ids)
    return f"o.audit_event_id in (select audit_event_id from audit_events where agent_run_id in ({ids}))"


def run_events(stack: Stack, run_ids: list[str]) -> dict[str, tuple[str, str]]:
    """eventId -> (payload hash, event key) for the outbox events of the given AgentRuns."""
    rows = stack.sql(f"select o.event_id, o.payload_hash, o.event_key from tool_call_event_outbox o"
                     f" where {run_filter(run_ids)}")
    return {event_id: (payload_hash, key) for event_id, payload_hash, key in rows}


def run_event_types(stack: Stack, run_ids: list[str]) -> dict[str, list[str]]:
    """agentRunId -> its outbox event types in id order (every submitted run is present, maybe empty)."""
    rows = stack.sql("select a.agent_run_id, o.event_type from tool_call_event_outbox o"
                     " join audit_events a on a.audit_event_id = o.audit_event_id"
                     f" where {run_filter(run_ids)} order by o.id")
    types: dict[str, list[str]] = {run_id: [] for run_id in run_ids}
    for run_id, event_type in rows:
        types[run_id].append(event_type)
    return types


def bad_runs(types: dict[str, list[str]], allowed_terminals: set[str]) -> dict[str, list[str]]:
    """Runs whose events are not exactly [TOOL_CALL_STARTED, <one allowed terminal event>]."""
    return {run_id: events for run_id, events in types.items()
            if len(events) != 2 or events[0] != "TOOL_CALL_STARTED" or events[1] not in allowed_terminals}


def topic_offset(stack: Stack, time_spec: str) -> int:
    out = stack.run("exec", "-T", "kafka", "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server",
                    "localhost:9092", "--topic-partitions", f"{TOPIC}:0", "--time", time_spec, what="topic offset")
    for line in out.splitlines():
        if line.startswith(f"{TOPIC}:0:"):
            return int(line.rsplit(":", 1)[1])
    raise MeasureError("could not read a topic offset")


def consumed_events(stack: Stack) -> set[str]:
    rows = stack.sql("select event_id from consumed_events where consumer_name = 'anomaly-alert'")
    return {row[0] for row in rows}


def wait_consumed(stack: Stack, expected: set[str]) -> set[str]:
    """Wait until the alert consumer has processed every outbox event (or time out)."""
    deadline = time.monotonic() + DRAIN_WAIT_SECONDS
    consumed = consumed_events(stack)
    while not expected <= consumed and time.monotonic() < deadline:
        time.sleep(1)
        consumed = consumed_events(stack)
    return consumed


def topic_events(stack: Stack) -> list[tuple[str, str, str]]:
    """(eventId header, record key, sha256 of the value) for every retained record."""
    expected = topic_offset(stack, "-1") - topic_offset(stack, "-2")
    out = stack.run(
        "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "localhost:9092",
        "--topic", TOPIC, "--from-beginning", "--max-messages", str(expected), "--timeout-ms", "20000",
        "--property", "print.headers=true", "--property", "headers.separator=;",
        "--property", "headers.key.separator=:", "--property", "print.key=true",
        "--property", "line.separator=\n", what="consume topic")
    records = []
    for line in out.splitlines():
        if "\t" not in line:
            continue
        if line.count("\t") < 2:
            raise MeasureError("unexpected console consumer output format")
        headers, key, value = line.split("\t", 2)
        header_map = dict(h.split(":", 1) for h in headers.split(";") if ":" in h)
        records.append((header_map.get("eventId", ""), key, hashlib.sha256(value.encode("utf-8")).hexdigest()))
    if len(records) != expected:
        raise MeasureError(f"read {len(records)} topic records, retained range holds {expected}")
    return records


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("env_file", type=Path)
    parser.add_argument("--before", type=int, default=3)
    parser.add_argument("--during", type=int, default=5)
    parser.add_argument("--mode", choices=["stop", "kill"], default="stop")
    args = parser.parse_args()
    env = read_env(args.env_file.resolve())
    stack = Stack(args.env_file.resolve())
    base_url = CORE_URL_TEMPLATE.format(port=env.get("CORE_API_PORT", DEFAULT_CORE_PORT))
    try:
        log(f"mode={args.mode} before={args.before} during={args.during}")
        high_water = outbox_high_water(stack)
        log(f"outbox before: high_water_id={high_water} unpublished={unpublished(stack)}")
        run_ids = calls(stack, base_url, env, args.before, "broker up")

        stack.run(args.mode, "kafka", what=f"{args.mode} kafka")
        down_at = time.monotonic()
        log("kafka stopped (graceful)" if args.mode == "stop" else "kafka killed (SIGKILL)")
        run_ids += calls(stack, base_url, env, args.during, "broker down")
        log(f"while down: unpublished={unpublished(stack)}")

        stack.run("start", "kafka", what="start kafka")
        log("kafka started")
        deadline = time.monotonic() + DRAIN_WAIT_SECONDS
        while unpublished(stack) > 0:
            if time.monotonic() > deadline:
                raise MeasureError(f"outbox did not drain within {DRAIN_WAIT_SECONDS}s")
            time.sleep(1)
        log(f"outbox drained {time.monotonic() - down_at:.1f}s after the broker went down")

        stored = run_events(stack, run_ids)
        wrong = bad_runs(run_event_types(stack, run_ids), {"TOOL_CALL_FINALIZED"})
        if wrong:
            raise MeasureError(f"{len(wrong)} AgentRuns do not have exactly STARTED + FINALIZED: {wrong}")
        others = set(outbox_events(stack, above_id=high_water)) - set(stored)
        log(f"outbox events after the high-water mark not from this run: {len(others)}")
        on_topic = [r for r in topic_events(stack) if r[0] in stored]
        consumed = wait_consumed(stack, set(stored))
    except MeasureError as error:
        log(f"INVALID RUN: {error}")
        return 1

    topic_ids = Counter(event_id for event_id, _, _ in on_topic)
    missing = [e for e in stored if e not in topic_ids]
    duplicated = {e: n for e, n in topic_ids.items() if n > 1}
    mismatched = [e for e, k, h in on_topic if stored[e] != (h, k)]
    log(f"RESULT this run: outbox_events={len(stored)} topic_records={len(on_topic)} distinct_on_topic={len(topic_ids)}")
    log(f"RESULT missing={len(missing)} key_or_hash_mismatch={len(mismatched)} "
        f"duplicated={len(duplicated)} (duplicates are allowed: at-least-once)")
    not_consumed = set(stored) - consumed
    log(f"RESULT consumer (eventId only): processed={len(set(stored) & consumed)} not_processed={len(not_consumed)}")
    ok = not missing and not mismatched and not not_consumed
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
