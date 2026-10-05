package io.finguard.core.dashboard;

/**
 * Dashboard 상단 집계. {@code docs/04-api-contract.md} §15.
 *
 * <p>{@code total}은 {@code allow + block + error + outcomeUnknown}과 같지 않을 수 있다 — 아직
 * 판정이 없는 PROCESSING 기록이 total에만 들어간다. 진행 중인 요청을 숨기지 않기 위해서다.
 *
 * <p>{@code outcomeUnknown}은 결과 기록이 끝내 도착하지 않은 수다. 판정이 없으므로 allow·block에
 * 넣지 않고 따로 센다 — total에만 묻히면 기록 유실이 화면에서 보이지 않는다(docs/06 §25).
 */
public record DashboardSummaryResponse(long total, long allow, long block, long error, long outcomeUnknown) {
}
