package io.finguard.core.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import io.finguard.core.domain.SecuredAgentInput;

public interface SecuredAgentInputRepository extends JpaRepository<SecuredAgentInput, String> {

    Optional<SecuredAgentInput> findByInputRefAndAgentRunId(String inputRef, String agentRunId);

    /** 실행 하나에는 입력이 하나다(AgentRunService). 승인 요청이 같은 입력인지 확인할 해시를 가져온다. */
    Optional<SecuredAgentInput> findFirstByAgentRunId(String agentRunId);
}
