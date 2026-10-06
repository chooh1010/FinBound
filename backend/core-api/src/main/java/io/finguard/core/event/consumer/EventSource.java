package io.finguard.core.event.consumer;

import java.util.List;

/**
 * 소비자가 의존하는 유일한 이벤트 출처. docs/04 §18.
 *
 * <p>소비자는 아웃박스·피드·브로커를 모른다. 체크포인트는 해석하지 않고 저장했다가 그대로 돌려준다 — 피드 구현은 피드
 * 번호를, 나중의 Kafka 구현은 파티션별 오프셋을 담는다. 전달은 최소 1회다. 같은 이벤트가 다시 올 수 있다.
 */
public interface EventSource {

    /**
     * {@code from} 다음의 이벤트를 최대 {@code maxEvents}개 돌려준다. {@code from}이 null이면 처음부터다.
     *
     * @throws IllegalStateException 체크포인트를 이어 쓸 수 없을 때(예: 피드 세대가 바뀜). 추측해서 넘기지 않는다
     */
    Batch poll(Checkpoint from, int maxEvents);

    /** 불투명 체크포인트. */
    record Checkpoint(String token) {
    }

    /** {@code next}는 이 배치를 모두 처리한 뒤 저장할 체크포인트다. */
    record Batch(List<Received> events, Checkpoint next) {
    }

    /** 받은 그대로의 이벤트 문자열과 그 해시. 소비자는 해시를 다시 계산해 확인한 뒤에만 해석한다. */
    record Received(String eventJson, String eventHash) {
    }
}
