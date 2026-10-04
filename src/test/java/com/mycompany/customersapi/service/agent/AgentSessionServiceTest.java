package com.mycompany.customersapi.service.agent;

import com.mycompany.customersapi.domain.AgentSession;
import com.mycompany.customersapi.domain.AgentTurn;
import com.mycompany.customersapi.domain.AgentTurnRole;
import com.mycompany.customersapi.repository.AgentSessionRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private AgentSessionRepository repository;
    private Clock clock;
    private AgentSessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentSessionRepository.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AgentSessionService(repository, clock, 24, 2);
    }

    private AgentSession session(UUID id, LocalDateTime updatedAt, String... texts) {
        AgentSession session = AgentSession.builder().sessionId(id).companyId(1L)
                .createdAt(updatedAt).updatedAt(updatedAt).build();
        for (int i = 0; i < texts.length; i++) {
            session.addTurn(i % 2 == 0 ? AgentTurnRole.USER : AgentTurnRole.ASSISTANT, texts[i]);
        }
        return session;
    }

    private LocalDateTime recently() {
        return LocalDateTime.now(clock).minusMinutes(5);
    }

    private static String textOf(ChatMessage message) {
        return message instanceof UserMessage user ? user.singleText() : ((AiMessage) message).text();
    }

    // ── open ─────────────────────────────────────────────────────────────────

    @Test
    void should_start_a_new_session_with_no_history_and_store_nothing_yet() {
        AgentSessionService.SessionState state = service.open(1L, null);

        assertNotNull(state.sessionId());
        assertTrue(state.history().isEmpty());
        assertNull(state.batchId());
        verifyNoInteractions(repository);
    }

    @Test
    void should_return_the_history_in_order_with_alternating_roles_and_the_open_batch() {
        UUID id = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        AgentSession stored = session(id, recently(), "first question", "first answer");
        stored.setBatchId(batch);
        when(repository.findBySessionIdAndCompanyId(id, 1L)).thenReturn(Optional.of(stored));

        AgentSessionService.SessionState state = service.open(1L, id);

        assertEquals(id, state.sessionId());
        assertEquals(batch, state.batchId());
        assertEquals(List.of(ChatMessageType.USER, ChatMessageType.AI),
                state.history().stream().map(ChatMessage::type).toList());
        assertEquals(List.of("first question", "first answer"), state.history().stream().map(AgentSessionServiceTest::textOf).toList());
    }

    @Test
    void should_replay_only_the_last_pairs_and_start_with_a_user_message() {
        UUID id = UUID.randomUUID();
        when(repository.findBySessionIdAndCompanyId(id, 1L)).thenReturn(Optional.of(
                session(id, recently(), "q1", "a1", "q2", "a2", "q3", "a3")));   // maxTurnPairs = 2

        List<ChatMessage> history = service.open(1L, id).history();

        assertEquals(List.of("q2", "a2", "q3", "a3"), history.stream().map(AgentSessionServiceTest::textOf).toList());
        assertEquals(ChatMessageType.USER, history.getFirst().type());
    }

    @Test
    void should_answer_404_for_a_session_that_does_not_exist_for_the_company() {
        when(repository.findBySessionIdAndCompanyId(any(), any())).thenReturn(Optional.empty());

        var ex = assertThrows(ResponseStatusException.class, () -> service.open(2L, UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void should_answer_410_for_an_expired_session() {
        UUID id = UUID.randomUUID();
        when(repository.findBySessionIdAndCompanyId(id, 1L)).thenReturn(Optional.of(
                session(id, LocalDateTime.now(clock).minusHours(25), "q", "a")));

        var ex = assertThrows(ResponseStatusException.class, () -> service.open(1L, id));

        assertEquals(HttpStatus.GONE, ex.getStatusCode());
    }

    // ── record ───────────────────────────────────────────────────────────────

    @Test
    void should_create_the_session_on_its_first_recorded_turn() {
        UUID id = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.record(1L, id, "question", "answer", batch);

        ArgumentCaptor<AgentSession> saved = ArgumentCaptor.forClass(AgentSession.class);
        verify(repository).save(saved.capture());
        AgentSession session = saved.getValue();
        assertEquals(id, session.getSessionId());
        assertEquals(1L, session.getCompanyId());
        assertEquals(batch, session.getBatchId());
        assertEquals(LocalDateTime.now(clock), session.getUpdatedAt());
        assertEquals(List.of(AgentTurnRole.USER, AgentTurnRole.ASSISTANT), session.getTurns().stream().map(AgentTurn::getRole).toList());
        assertEquals(List.of(0, 1), session.getTurns().stream().map(AgentTurn::getSeq).toList());
    }

    @Test
    void should_append_to_an_existing_session_and_update_its_batch() {
        UUID id = UUID.randomUUID();
        AgentSession stored = session(id, recently(), "q1", "a1");
        stored.setBatchId(UUID.randomUUID());
        when(repository.findById(id)).thenReturn(Optional.of(stored));

        service.record(1L, id, "q2", "a2", null);

        assertEquals(List.of(0, 1, 2, 3), stored.getTurns().stream().map(AgentTurn::getSeq).toList());
        assertNull(stored.getBatchId(), "the batch pointer follows the run: cleared when no batch is left");
        assertEquals(LocalDateTime.now(clock), stored.getUpdatedAt());
    }

    @Test
    void should_cut_a_very_long_turn_to_the_stored_limit() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.record(1L, id, "q", "x".repeat(AgentSessionService.MAX_TURN_CHARS + 500), null);

        ArgumentCaptor<AgentSession> saved = ArgumentCaptor.forClass(AgentSession.class);
        verify(repository).save(saved.capture());
        assertEquals(AgentSessionService.MAX_TURN_CHARS, saved.getValue().getTurns().get(1).getText().length());
    }
}
