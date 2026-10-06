package io.finguard.core.event;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 내부 이벤트 피드. docs/04 §18.
 *
 * <p>경로가 {@code /internal/}이 아니라 {@code /feed/}인 이유: 인증 필터는 경로를 서블릿 등록 패턴으로만 가른다(요청 URI를
 * 다시 검사하면 인코딩·경로 매개변수로 우회된 이력이 있다 — InternalCredentialFilter). 피드 Credential과 내부 Credential을
 * 서로 다른 패턴에 붙여, 한쪽 Credential로 다른 쪽 경로를 쓸 수 없게 한다.
 */
@RestController
public class EventFeedController {

    static final String PATH = "/feed/v1/events";

    private final EventFeed feed;

    public EventFeedController(EventFeed feed) {
        this.feed = feed;
    }

    @GetMapping(PATH)
    public EventFeed.Page events(
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "100") int limit) {
        return feed.read(after, limit);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalidCursor() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "after는 0 이상의 정수, limit는 1~" + EventFeed.MAX_LIMIT + "입니다.");
        problem.setProperty("reasonCode", "INVALID_FEED_CURSOR");
        return ResponseEntity.badRequest().body(problem);
    }
}
