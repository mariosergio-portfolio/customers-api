package com.mycompany.customersapi.service.agent;

import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.dto.EmailBatchResponse;
import com.mycompany.customersapi.dto.EmailDraftResponse;
import com.mycompany.customersapi.repository.CustomerRepository;
import com.mycompany.customersapi.service.email.EmailBatchService;
import com.mycompany.customersapi.service.email.EmailSender;
import com.mycompany.customersapi.service.email.PendingDraft;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Checks the JPA mappings and queries that unit tests with mocked repositories cannot: session turns and their
 * ordering, draft edits with orphan removal under the unique constraints, the row lock on approval, and
 * discarding. Runs against the local PostgreSQL (no Bedrock, no SES) in its own company, which it empties
 * before and after each test.
 *
 * Skipped by default; run with {@code mvn test -Pintegration}.
 */
@Tag("integration")
@SpringBootTest
class AgentPersistenceIntegrationTest {

    private static final long COMPANY = 9002L;

    @Autowired private AgentSessionService sessions;
    @Autowired private EmailBatchService batches;
    @Autowired private CustomerRepository customers;
    @Autowired private DataSource dataSource;

    @MockitoBean private EmailSender emailSender;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        clean();
        for (long id = 1; id <= 3; id++) {
            jdbc.update("""
                    INSERT INTO customer (customer_pk, import_id, company_id, id, name, email, age, country, phone)
                    VALUES (?, ?, ?, ?, ?, ?, 40, 'France', '+33 000 000 000')
                    """, UUID.randomUUID(), UUID.randomUUID(), COMPANY, id, "Customer " + id, "customer" + id + "@example.com");
        }
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM agent_turn WHERE session_id IN (SELECT session_id FROM agent_session WHERE company_id = ?)", COMPANY);
        jdbc.update("DELETE FROM agent_session WHERE company_id = ?", COMPANY);
        jdbc.update("DELETE FROM email_draft WHERE batch_id IN (SELECT batch_id FROM email_batch WHERE company_id = ?)", COMPANY);
        jdbc.update("DELETE FROM email_batch WHERE company_id = ?", COMPANY);
        jdbc.update("DELETE FROM customer WHERE company_id = ?", COMPANY);
    }

    private PendingDraft draftFor(long customerId, String subject) {
        Customer c = customers.findByCompanyIdAndIdIn(COMPANY, List.of(customerId)).getFirst();
        return new PendingDraft(c.getCustomerPk(), c.getId(), c.getName(), c.getEmail(), "French", subject, "body " + subject);
    }

    private static Set<Long> customerIds(EmailBatchResponse batch) {
        return batch.drafts().stream().map(EmailDraftResponse::customerId).collect(Collectors.toSet());
    }

    // ── sessions ─────────────────────────────────────────────────────────────

    @Test
    void should_store_turns_in_order_and_replay_them_as_alternating_messages() {
        UUID sessionId = sessions.open(COMPANY, null).sessionId();
        UUID batchId = UUID.randomUUID();

        sessions.record(COMPANY, sessionId, "first question", "first answer", batchId);
        sessions.record(COMPANY, sessionId, "second question", "second answer", null);

        AgentSessionService.SessionState state = sessions.open(COMPANY, sessionId);
        assertEquals(List.of("first question", "first answer", "second question", "second answer"),
                state.history().stream().map(m -> m.content().getFirst().text()).toList());
        assertEquals(List.of(ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.USER, ConversationRole.ASSISTANT),
                state.history().stream().map(Message::role).toList());
        assertNull(state.batchId(), "the pointer follows the latest turn");
    }

    @Test
    void should_not_let_another_company_open_the_session() {
        UUID sessionId = UUID.randomUUID();
        sessions.record(COMPANY, sessionId, "q", "a", null);

        var ex = assertThrows(ResponseStatusException.class, () -> sessions.open(COMPANY + 1, sessionId));

        assertEquals(404, ex.getStatusCode().value());
    }

    // ── batches ──────────────────────────────────────────────────────────────

    @Test
    void should_edit_a_batch_in_place_rewriting_adding_and_removing_drafts() {
        EmailBatchResponse created = batches.createBatch(COMPANY, "greet", List.of(draftFor(1, "one"), draftFor(2, "two")));
        assertEquals(Set.of(1L, 2L), customerIds(created));

        EmailBatchResponse edited = batches.updateDrafts(COMPANY, created.batchId(),
                List.of(draftFor(1, "one rewritten"), draftFor(3, "three")));

        assertEquals(created.batchId(), edited.batchId());
        EmailBatchResponse stored = batches.get(COMPANY, created.batchId());
        assertEquals(Set.of(1L, 3L), customerIds(stored), "customer 2 removed, customer 3 added");
        assertEquals("one rewritten", stored.drafts().stream().filter(d -> d.customerId() == 1).findFirst().orElseThrow().subject());
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM email_draft WHERE batch_id = ?", Long.class, created.batchId()));
    }

    @Test
    void should_list_the_open_drafts_for_the_agent_and_stop_once_sent() {
        EmailBatchResponse created = batches.createBatch(COMPANY, "greet", List.of(draftFor(1, "one")));
        assertEquals(1, batches.openDrafts(COMPANY, created.batchId()).size());

        when(emailSender.isConfigured()).thenReturn(true);
        when(emailSender.send(any())).thenReturn("message-1");
        EmailBatchResponse sent = batches.approve(COMPANY, created.batchId());

        assertEquals("SENT", sent.status());
        assertEquals("SENT", batches.get(COMPANY, created.batchId()).drafts().getFirst().status());
        assertTrue(batches.openDrafts(COMPANY, created.batchId()).isEmpty());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> batches.approve(COMPANY, created.batchId())).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> batches.updateDrafts(COMPANY, created.batchId(), List.of(draftFor(1, "late edit")))).getStatusCode().value());
    }

    @Test
    void should_discard_a_batch_and_its_drafts() {
        EmailBatchResponse created = batches.createBatch(COMPANY, "greet", List.of(draftFor(1, "one"), draftFor(2, "two")));

        batches.discard(COMPANY, created.batchId());

        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM email_batch WHERE batch_id = ?", Long.class, created.batchId()));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM email_draft WHERE batch_id = ?", Long.class, created.batchId()));
    }
}
