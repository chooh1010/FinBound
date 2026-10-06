package io.finguard.core.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지금 Credential의 역할. 화면이 어떤 메뉴를 보일지 정하는 데만 쓴다 — 권한 판단은 서버가 Endpoint마다 한다(docs/04 §15.1).
 */
@RestController
public class MeController {

    @GetMapping("/api/v1/me")
    @RequiresRole({CoreApiRole.VIEWER, CoreApiRole.OPERATOR, CoreApiRole.APPROVER})
    public ResponseEntity<MeResponse> me(CoreApiPrincipal principal) {
        return ResponseEntity.ok(new MeResponse(principal.role(), principal.employeeId()));
    }

    public record MeResponse(CoreApiRole role, String employeeId) {
    }
}
