"""K scenario 1: tool calls while the Kafka broker is down; prove no committed outbox event is lost.

Usage (from anywhere):
    python measure_broker_outage.py <env-file> [--before N] [--during N] [--mode stop|kill]

Needs the K stack up (see docker-compose.k.yml) with the relay enabled.
Prints only identifiers, counts, hashes and timings — never prompts, financial payloads or credentials.

The guarantee measured is "no committed outbox event is lost": the set of (eventId, payload hash)
in the outbox must equal the set on the topic. Missing, duplicated and mismatched events are
counted separately so a loss cannot hide behind a duplicate.
"""

import argparse
import hashlib
import json
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
        result = subprocess.run([*self.base, *args], capture_output=True, text=True, encoding="utf-8",
                                timeout=COMMAND_TIMEOUT_SECONDS)
        if result.returncode != 0:
            raise MeasureError(f"{what}: exit {result.returncode}")
        return result.stdout

    def sql(self, statement: str) -> list[list[str]]:
        out = self.run("exec", "-T", "postgres", "psql", "-U", "finguard", "-d", "finguard", "-A", "-t",
                       "-F", "|", "-c", statement, what="psql")
        return [line.split("|") for line in out.splitlines() if line]


def start_run(base_url: str, env: dict[str, str]) -> str:
    body = json.dumps({"employeeId": env["OPERATOR_EMPLOYEE_ID"], "consumerId": "CUST-1001",
                       "taskType": "LOAN_REVIEW", "scenario": "NORMAL_CREDIT_SCORE",
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


def wait_final(stack: Stack, agent_run_id: str) -> str:
    deadline = time.monotonic() + FINAL_WAIT_SECONDS
    while time.monotonic() < deadline:
        rows = stack.sql(f"select status from audit_events where agent_run_id = '{agent_run_id}'")
        if rows and rows[0][0] in FINAL_STATUSES:
            return rows[0][0]
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


def outbox_events(stack: Stack) -> dict[str, str]:
    rows = stack.sql("select event_id, payload_hash from tool_call_event_outbox")
    return {event_id: payload_hash for event_id, payload_hash in rows}


def topic_events(stack: Stack, expected: int) -> list[tuple[str, str]]:
    """(eventId header, sha256 of the message value) for every record on the topic."""
    out = stack.run(
        "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "localhost:9092",
        "--topic", TOPIC, "--from-beginning", "--max-messages", str(expected), "--timeout-ms", "20000",
        "--property", "print.headers=true", "--property", "headers.separator=;",
        "--property", "headers.key.separator=:", "--property", "print.key=false",
        "--property", "line.separator=\n", what="consume topic")
    records = []
    for line in out.splitlines():
        if "\t" not in line:
            continue
        headers, value = line.split("\t", 1)
        header_map = dict(h.split(":", 1) for h in headers.split(";") if ":" in h)
        records.append((header_map.get("eventId", ""), hashlib.sha256(value.encode("utf-8")).hexdigest()))
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
        log(f"outbox before: total={len(outbox_events(stack))} unpublished={unpublished(stack)}")
        calls(stack, base_url, env, args.before, "broker up")

        stack.run(args.mode, "kafka", what=f"{args.mode} kafka")
        down_at = time.monotonic()
        log("kafka stopped (graceful)" if args.mode == "stop" else "kafka killed (SIGKILL)")
        calls(stack, base_url, env, args.during, "broker down")
        log(f"while down: unpublished={unpublished(stack)}")

        stack.run("start", "kafka", what="start kafka")
        log("kafka started")
        deadline = time.monotonic() + DRAIN_WAIT_SECONDS
        while unpublished(stack) > 0:
            if time.monotonic() > deadline:
                raise MeasureError(f"outbox did not drain within {DRAIN_WAIT_SECONDS}s")
            time.sleep(1)
        log(f"outbox drained {time.monotonic() - down_at:.1f}s after the broker went down")

        stored = outbox_events(stack)
        on_topic = topic_events(stack, expected=len(stored) * 2)
    except MeasureError as error:
        log(f"INVALID RUN: {error}")
        return 1

    topic_ids = Counter(event_id for event_id, _ in on_topic)
    missing = [e for e in stored if e not in topic_ids]
    duplicated = {e: n for e, n in topic_ids.items() if n > 1}
    unknown = [e for e in topic_ids if e not in stored]
    mismatched = [e for e, h in on_topic if e in stored and stored[e] != h]
    log(f"RESULT outbox={len(stored)} topic_records={len(on_topic)} distinct_on_topic={len(topic_ids)}")
    log(f"RESULT missing={len(missing)} duplicated={len(duplicated)} not_in_outbox={len(unknown)} "
        f"hash_mismatch={len(mismatched)}")
    return 0 if not missing and not mismatched and not unknown else 2


if __name__ == "__main__":
    sys.exit(main())
