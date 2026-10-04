package com.mycompany.customersapi.service.agent;

import com.mycompany.customersapi.domain.AgentSession;
import com.mycompany.customersapi.domain.AgentTurn;
import com.mycompany.customersapi.domain.AgentTurnRole;
import com.mycompany.customersapi.repository.AgentSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Conversation memory for the company agent, kept in the database so any instance can continue a session.
 *
 * Only the user's prompts and the agent's final answers are remembered, not the tool calls: a follow-up sees
 * what was said, and the agent re-reads the data if it needs it. A session is stored when a run succeeds, so a
 * failed first request leaves nothing behind. A session belongs to one company and expires after a period
 * without use.
 */
@Service
public class AgentSessionService {

    static final int MAX_TURN_CHARS = 10_000;

    private final AgentSessionRepository repository;
    private final Clock                  clock;
    private final Duration               ttl;
    private final int                    maxTurnPairs;

    public AgentSessionService(AgentSessionRepository repository,
                               Clock clock,
                               @Value("${assistant.session.ttl-hours:24}") int ttlHours,
                               @Value("${assistant.session.max-turns:10}") int maxTurnPairs) {
        this.repository = repository;
        this.clock = clock;
        this.ttl = Duration.ofHours(ttlHours);
        this.maxTurnPairs = maxTurnPairs;
    }

    /** What a run starts from: the session id (new if none was given), the recent history and the open batch. */
    public record SessionState(UUID sessionId, List<Message> history, UUID batchId) {
    }

    /**
     * @param sessionId the session to continue, or null to start a new one
     * @throws ResponseStatusException 404 if the session does not exist for this company, 410 if it expired
     */
    @Transactional(readOnly = true)
    public SessionState open(Long companyId, UUID sessionId) {
        if (sessionId == null) {
            return new SessionState(UUID.randomUUID(), List.of(), null);
        }
        AgentSession session = repository.findBySessionIdAndCompanyId(sessionId, companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + sessionId));
        if (LocalDateTime.now(clock).isAfter(session.getUpdatedAt().plus(ttl))) {
            throw new ResponseStatusException(HttpStatus.GONE, "Session " + sessionId + " expired; start a new one");
        }
        return new SessionState(sessionId, history(session), session.getBatchId());
    }

    /** Stores the prompt and the answer, creating the session on its first successful turn. */
    @Transactional
    public void record(Long companyId, UUID sessionId, String prompt, String answer, UUID batchId) {
        LocalDateTime now = LocalDateTime.now(clock);
        AgentSession session = repository.findById(sessionId).orElseGet(() -> AgentSession.builder()
                .sessionId(sessionId).companyId(companyId).createdAt(now).build());
        session.addTurn(AgentTurnRole.USER, truncate(prompt));
        session.addTurn(AgentTurnRole.ASSISTANT, truncate(answer));
        session.setBatchId(batchId);
        session.setUpdatedAt(now);
        repository.save(session);
    }

    /** The last maxTurnPairs prompt/answer pairs, oldest first, always starting with a user message. */
    private List<Message> history(AgentSession session) {
        List<AgentTurn> turns = session.getTurns();
        int from = Math.max(0, turns.size() - 2 * maxTurnPairs);
        if (from % 2 == 1) {
            from++;   // turns come in pairs; never start on an answer
        }
        return turns.subList(from, turns.size()).stream().map(AgentSessionService::toMessage).toList();
    }

    private static Message toMessage(AgentTurn turn) {
        return Message.builder()
                .role(turn.getRole() == AgentTurnRole.USER ? ConversationRole.USER : ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText(turn.getText()))
                .build();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_TURN_CHARS ? text : text.substring(0, MAX_TURN_CHARS);
    }
}
