"""F1 reproduction: start one real AgentRun and report what happened to its audit row.

Usage:
    python repro_outcome_loss.py <env-file> --mode {inject-503,pause,pause-kill}
        [--wait-seconds N] [--pause-seconds N]

Modes (the stack must already be up with the matching overlays, see docker-compose.f1-*.yml):
    inject-503  repro overlay: the outcome PATCH is answered 503 by the core proxy
    pause       pause overlay: freeze core-api once the call reached finance, thaw it later
    pause-kill  pause overlay: same, but SIGKILL core-api at the end of the pause, then restart

The env file is read only for the operator credential; no value is printed.
Prompt text and financial payloads are never printed (AGENTS.md).
The prompt text is fixed on purpose so before/after runs hit the same prompt-risk snapshot.
"""

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

PROJECT = "finguard-f1"
INFRA_DIR = Path(__file__).resolve().parent.parent
COMPOSE_FILES = {
    "inject-503": ["docker-compose.yml", "docker-compose.demo.yml", "docker-compose.expose.yml",
                   "f1/docker-compose.f1-repro.yml"],
    "pause": ["docker-compose.yml", "docker-compose.demo.yml", "docker-compose.expose.yml",
              "f1/docker-compose.f1-repro.yml", "f1/docker-compose.f1-pause.yml"],
}
COMPOSE_FILES["pause-kill"] = COMPOSE_FILES["pause"]

CORE_URL_TEMPLATE = "http://127.0.0.1:{port}"
DEFAULT_CORE_PORT = "18080"
DB_USER = "finguard"
DB_NAME = "finguard"
AGENT_ID = "LOAN-AGENT-01"
AGENT_RUN_ID_PATTERN = re.compile(r"^RUN-[0-9A-F]{32}$")
INPUT_TEXT = "현재 고객의 신규 대출 심사자료 확인"
SCENARIO = "NORMAL_CREDIT_SCORE"

DEFAULT_WAIT_SECONDS = 70
DEFAULT_PAUSE_SECONDS = 5
POLL_INTERVAL_SECONDS = 5
FINANCE_POLL_SECONDS = 0.05
FINANCE_WAIT_SECONDS = 10
CORE_HEALTH_WAIT_SECONDS = 180
COMMAND_TIMEOUT_SECONDS = 60
HTTP_TIMEOUT_SECONDS = 30
BEHAVIOR_WINDOW = "5 minutes"
AUDIT_COLUMNS = ("request_id, status, decision, downstream_reached, response_released, "
                 "requested_at, completed_at")
FINANCE_COUNT_COMMAND = (
    "curl -s -X POST localhost:8080/__admin/requests/count "
    "-d '{\"method\":\"ANY\",\"urlPattern\":\".*\"}'"
)


class ReproError(Exception):
    """A precondition or step failed; the run is not valid evidence."""


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def log(message: str) -> None:
    print(f"[{now()}] {message}", flush=True)


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def run(command: list[str], what: str) -> str:
    """Run a command; on failure raise without echoing its output (it may hold secrets)."""
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                timeout=COMMAND_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as error:
        raise ReproError(f"{what}: timed out") from error
    if result.returncode != 0:
        raise ReproError(f"{what}: exit {result.returncode}")
    return result.stdout.strip()


class Stack:
    def __init__(self, mode: str, env_file: Path) -> None:
        self.base = ["docker", "compose", "-p", PROJECT, "--project-directory", str(INFRA_DIR),
                     "--env-file", str(env_file)]
        for name in COMPOSE_FILES[mode]:
            self.base += ["-f", str(INFRA_DIR / name)]

    def compose(self, *args: str, what: str) -> str:
        return run([*self.base, *args], what)

    def container(self, service: str) -> str:
        container_id = self.compose("ps", "-q", service, what=f"find {service}")
        if not container_id:
            raise ReproError(f"{service} is not running")
        return container_id

    def sql(self, statement: str) -> str:
        return self.compose("exec", "-T", "postgres", "psql", "-U", DB_USER, "-d", DB_NAME,
                            "-A", "-F", " | ", "-c", statement, what="psql")

    def sql_value(self, statement: str) -> str:
        return self.compose("exec", "-T", "postgres", "psql", "-U", DB_USER, "-d", DB_NAME,
                            "-A", "-t", "-c", statement, what="psql")


def record_conditions(stack: Stack) -> None:
    head = run(["git", "-C", str(INFRA_DIR), "rev-parse", "--short", "HEAD"], "git HEAD")
    dirty = run(["git", "-C", str(INFRA_DIR), "status", "--porcelain", "--", "../backend"],
                "git status")
    log(f"git HEAD={head} backend_dirty={'yes' if dirty else 'no'}")
    for service in ("core-api", "gateway", "agent"):
        image = run(["docker", "inspect", "-f", "{{.Image}}", stack.container(service)],
                    f"inspect {service}")
        log(f"image {service}={image[:19]}")
    recent = stack.sql_value(
        f"select count(*) from audit_events where agent_id = '{AGENT_ID}' "
        f"and requested_at > now() - interval '{BEHAVIOR_WINDOW}'")
    # Behavior history feeds the AI behavior risk; record it so runs can be compared.
    log(f"precondition: audits by {AGENT_ID} in last {BEHAVIOR_WINDOW}={recent}")


def start_run(base_url: str, credential: str, employee_id: str) -> str:
    body = json.dumps({
        "employeeId": employee_id,
        "consumerId": "CUST-1001",
        "taskType": "LOAN_REVIEW",
        "scenario": SCENARIO,
        "inputText": INPUT_TEXT,
    }).encode("utf-8")
    request = urllib.request.Request(
        base_url + "/api/v1/agent-runs", data=body, method="POST",
        headers={
            "Authorization": f"Bearer {credential}",
            "Content-Type": "application/json",
            "X-Request-Id": str(uuid.uuid4()),
        },
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            payload = json.loads(response.read())
            status = response.status
    except urllib.error.HTTPError as error:
        raise ReproError(f"agent-run create failed http={error.code}") from None
    elapsed_ms = int((time.monotonic() - started) * 1000)
    agent_run_id = str(payload.get("agentRunId", ""))
    if not AGENT_RUN_ID_PATTERN.match(agent_run_id):
        raise ReproError("agent-run create returned an unexpected id format")
    log(f"agent-run created http={status} elapsed_ms={elapsed_ms} "
        f"agentRunId={agent_run_id} runStatus={payload.get('status')}")
    return agent_run_id


def finance_request_count(stack: Stack) -> int:
    output = stack.compose("exec", "-T", "finance-proxy", "sh", "-c", FINANCE_COUNT_COMMAND,
                           what="finance-proxy count")
    return int(json.loads(output)["count"])


def wait_until_finance_reached(stack: Stack, before: int) -> None:
    deadline = time.monotonic() + FINANCE_WAIT_SECONDS
    while finance_request_count(stack) <= before:
        if time.monotonic() >= deadline:
            raise ReproError("the call never reached finance (blocked or failed before downstream)")
        time.sleep(FINANCE_POLL_SECONDS)
    log("finance-proxy received the downstream call")


def wait_core_healthy(stack: Stack) -> None:
    container_id = stack.container("core-api")
    deadline = time.monotonic() + CORE_HEALTH_WAIT_SECONDS
    while time.monotonic() < deadline:
        health = run(["docker", "inspect", "-f", "{{.State.Health.Status}}", container_id],
                     "inspect core-api health")
        if health == "healthy":
            return
        time.sleep(1)
    raise ReproError(f"core-api not healthy within {CORE_HEALTH_WAIT_SECONDS}s")


def pause_core(stack: Stack, finance_before: int, pause_seconds: int, kill: bool) -> None:
    wait_until_finance_reached(stack, finance_before)
    stack.compose("pause", "core-api", what="pause core-api")
    log("core-api paused")
    try:
        time.sleep(pause_seconds)
        if kill:
            # Requests the frozen process had not handled die with it, like a crash.
            stack.compose("kill", "core-api", what="kill core-api")
            log("core-api killed (SIGKILL) while paused")
            stack.compose("start", "core-api", what="start core-api")
            wait_core_healthy(stack)
            log("core-api restarted and healthy")
            return
    finally:
        # Never leave core-api frozen if the script is interrupted mid-pause.
        if "core-api" in stack.compose("ps", "--status", "paused", "--services", what="ps paused"):
            stack.compose("unpause", "core-api", what="unpause core-api")
            log(f"core-api unpaused after {pause_seconds}s")


def observe(stack: Stack, agent_run_id: str, wait_seconds: int) -> None:
    deadline = time.monotonic() + wait_seconds
    while True:
        run_status = stack.sql_value(
            f"select status from agent_runs where agent_run_id = '{agent_run_id}'")
        log(f"agent_runs.status={run_status}")
        print(stack.sql(f"select {AUDIT_COLUMNS} from audit_events "
                        f"where agent_run_id = '{agent_run_id}' order by requested_at"), flush=True)
        if time.monotonic() >= deadline:
            return
        time.sleep(POLL_INTERVAL_SECONDS)


def gateway_outcome_errors(stack: Stack, since: str) -> list[str]:
    output = stack.compose("logs", "--no-log-prefix", "--since", since, "gateway",
                           what="gateway logs")
    return [line for line in output.splitlines() if "Audit outcome update failed" in line]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("env_file", type=Path)
    parser.add_argument("--mode", required=True, choices=sorted(COMPOSE_FILES))
    parser.add_argument("--wait-seconds", type=int, default=DEFAULT_WAIT_SECONDS)
    parser.add_argument("--pause-seconds", type=int, default=DEFAULT_PAUSE_SECONDS)
    args = parser.parse_args()

    env = read_env(args.env_file.resolve())
    stack = Stack(args.mode, args.env_file.resolve())
    base_url = CORE_URL_TEMPLATE.format(port=env.get("CORE_API_PORT", DEFAULT_CORE_PORT))
    since = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    try:
        log(f"mode={args.mode}")
        record_conditions(stack)
        finance_before = finance_request_count(stack) if args.mode != "inject-503" else 0
        agent_run_id = start_run(base_url, env["OPERATOR_CREDENTIAL"], env["OPERATOR_EMPLOYEE_ID"])
        if args.mode != "inject-503":
            pause_core(stack, finance_before, args.pause_seconds, kill=args.mode == "pause-kill")
        observe(stack, agent_run_id, args.wait_seconds)
        errors = gateway_outcome_errors(stack, since)
    except ReproError as error:
        log(f"INVALID RUN: {error}")
        return 1

    log(f"gateway 'Audit outcome update failed' lines: {len(errors)}")
    for line in errors:
        print("  " + line[:300], flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
