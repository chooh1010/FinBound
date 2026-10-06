package io.finguard.core.event.consumer;

import org.springframework.stereotype.Component;

import io.finguard.core.event.EventFeed;

/**
 * Core 안 소비자용 이벤트 출처. HTTP 피드와 같은 조회를 직접 부른다(같은 프로세스라 HTTP를 거칠 이유가 없다).
 *
 * <p>체크포인트 토큰은 {@code 세대:피드번호}다. 세대가 바뀌었으면(데이터베이스를 새로 만듦) 번호가 처음부터 다시 시작했으므로
 * 이어 읽지 않고 멈춘다.
 */
@Component
public class FeedEventSource implements EventSource {

    private final EventFeed feed;

    public FeedEventSource(EventFeed feed) {
        this.feed = feed;
    }

    @Override
    public Batch poll(Checkpoint from, int maxEvents) {
        long after = 0;
        String expectedGeneration = null;
        if (from != null) {
            String[] parts = from.token().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("Unreadable feed checkpoint");
            }
            expectedGeneration = parts[0];
            try {
                after = Long.parseLong(parts[1]);
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Unreadable feed checkpoint", exception);
            }
        }
        EventFeed.Page page = feed.read(after, Math.min(maxEvents, EventFeed.MAX_LIMIT));
        if (expectedGeneration != null && !expectedGeneration.equals(page.generation())) {
            throw new IllegalStateException("Event feed generation changed; the checkpoint no longer applies");
        }
        return new Batch(
                page.events().stream().map(entry -> new Received(entry.eventJson(), entry.eventHash())).toList(),
                new Checkpoint(page.generation() + ":" + page.nextAfter()));
    }
}
