package io.finguard.core.event;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 지정한 표에 특정 값을 가진 행이 들어오면 삽입을 실패시키는 트리거. 트랜잭션 중간의 쓰기 실패를 만들어, 함께 묶인
 * 변경이 모두 되돌아가는지 본다. 값은 시험이 정한 상수다.
 */
final class PoisonedInserts {

    private PoisonedInserts() {
    }

    static void install(JdbcTemplate jdbc, String table, String column, String value) {
        jdbc.execute("""
                create function poisoned_event() returns trigger language plpgsql as $$
                begin
                    if new.%s::text = '%s' then
                        raise exception 'poisoned write';
                    end if;
                    return new;
                end;
                $$""".formatted(column, value.replace("'", "''")));
        jdbc.execute("create trigger poisoned_event before insert on " + table
                + " for each row execute function poisoned_event()");
    }

    static void remove(JdbcTemplate jdbc, String table) {
        jdbc.execute("drop trigger if exists poisoned_event on " + table);
        jdbc.execute("drop function if exists poisoned_event()");
    }
}
