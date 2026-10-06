package io.finguard.core.event;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커밋된 아웃박스 행에 피드 번호를 매긴다. docs/04 §18.
 *
 * <p><strong>왜 id가 아니라 별도 번호인가.</strong> bigserial id는 삽입 때 정해진다. id 10인 트랜잭션이 id 11보다 늦게
 * 커밋되면, 11까지 읽은 소비자는 10을 영영 보지 못한다. 시퀀서는 이미 커밋된 행에만 번호를 매기므로, 늦게 커밋된 행은 더
 * 큰 번호를 받는다. 소비자는 {@code feed_seq > 체크포인트}로 읽으면 건너뛰지 않는다.
 *
 * <p>모든 인스턴스가 같은 advisory 잠금 키로 줄을 선다. 잠금을 못 잡으면 이번 주기는 건너뛴다(다른 인스턴스가 하고 있다).
 * 시퀀스는 캐시 없이 만들었다(V12) — 캐시가 있으면 잠금을 잡고도 이미 나간 번호보다 작은 번호가 나올 수 있다. 번호에는
 * 빈칸이 생길 수 있다(롤백). 소비자는 연속을 기대하지 않는다.
 *
 * <p>Kafka로 바꿀 때 이 자리는 "번호 매기기" 대신 "브로커로 보내고 ack를 받은 뒤 표시"가 된다.
 */
@Service
@EnableConfigurationProperties(EventSequencerProperties.class)
public class EventSequencer {

    /** 시퀀서 전용 advisory 잠금 키. 다른 용도와 겹치지 않게 고정한다. */
    static final long LOCK_KEY = 4_811_204_001L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EventSequencerProperties properties;

    public EventSequencer(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, EventSequencerProperties properties) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    /** 번호를 매긴 행 수. 다른 인스턴스가 잠금을 쥐고 있으면 0이다. */
    public int sequenceOnce() {
        Integer sequenced = transaction.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            // 오래 막히지 않는다. 막히면 이번 주기를 실패로 끝내고 다음 주기에 다시 한다.
            jdbc.execute("set local lock_timeout = '2s'");
            jdbc.execute("set local statement_timeout = '10s'");
            List<Long> ids = jdbc.queryForList(
                    "select id from event_outbox where feed_seq is null order by id limit ? for update",
                    Long.class, properties.batchSize());
            // 한 행씩 번호를 받는다. 한 문장으로 여러 행을 갱신하면 nextval이 id 순서로 불린다는 보장이 없다.
            for (Long id : ids) {
                jdbc.update("update event_outbox set feed_seq = nextval('event_feed_seq'),"
                        + " sequenced_at = clock_timestamp() where id = ?", id);
            }
            return ids.size();
        });
        return sequenced == null ? 0 : sequenced;
    }
}
