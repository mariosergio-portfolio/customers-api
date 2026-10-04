package com.mycompany.customersapi.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.repository.CustomerRepository;
import com.mycompany.customersapi.service.email.PendingDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DraftEmailsToolTest {

    private CustomerRepository repository;
    private DraftEmailsTool tool;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerRepository.class);
        tool = new DraftEmailsTool(repository, new ObjectMapper(), 3);
        run = new AgentRun(1L);
    }

    private static Customer customer(long id, String name, String country, String email) {
        return Customer.builder().customerPk(UUID.randomUUID()).id(id).companyId(1L).name(name)
                .email(email).country(country).build();
    }

    private void customersExist(Customer... customers) {
        when(repository.findByCompanyIdAndIdIn(eq(1L), any())).thenReturn(List.of(customers));
    }

    private static Document draft(Object customerId, String subject, String body) {
        var builder = Document.mapBuilder();
        if (customerId instanceof Number n) {
            builder.putNumber("customerId", n.longValue());
        } else if (customerId instanceof String s) {
            builder.putString("customerId", s);
        }
        if (subject != null) {
            builder.putString("subject", subject);
        }
        if (body != null) {
            builder.putString("body", body);
        }
        return builder.build();
    }

    private static Document input(Document... drafts) {
        return Document.mapBuilder().putList("drafts", List.of(drafts)).build();
    }

    // ── what is accepted ─────────────────────────────────────────────────────

    @Test
    void should_store_the_draft_with_the_language_of_the_customers_country() {
        Customer ann = customer(7, "Ann Dupont", "France", "ann@example.com");
        customersExist(ann);

        ToolResult result = tool.execute(run, input(draft(7, "Bonjour", "Chère Ann")));

        assertTrue(result.isOk());
        assertEquals(1, result.count());
        PendingDraft stored = run.drafts().iterator().next();
        assertEquals(ann.getCustomerPk(), stored.customerPk());
        assertEquals(7L, stored.customerId());
        assertEquals("Ann Dupont", stored.name());
        assertEquals("ann@example.com", stored.email());
        assertEquals("French", stored.language());
        assertEquals("Bonjour", stored.subject());
        assertEquals("Chère Ann", stored.body());
    }

    @Test
    void should_use_english_when_the_country_is_unknown_or_missing() {
        customersExist(customer(1, "A", "Atlantis", "a@example.com"), customer(2, "B", null, "b@example.com"));

        tool.execute(run, input(draft(1, "s", "b"), draft(2, "s", "b")));

        assertEquals(List.of("English", "English"), run.drafts().stream().map(PendingDraft::language).toList());
    }

    @Test
    void should_accept_a_customer_id_sent_as_a_string_of_digits() {
        customersExist(customer(7, "Ann", "France", "ann@example.com"));

        ToolResult result = tool.execute(run, input(draft("7", "s", "b")));

        assertTrue(result.isOk());
    }

    @Test
    void should_replace_an_earlier_draft_for_the_same_customer() {
        customersExist(customer(7, "Ann", "France", "ann@example.com"));

        tool.execute(run, input(draft(7, "first", "b")));
        tool.execute(run, input(draft(7, "second", "b")));

        assertEquals(1, run.draftCount());
        assertEquals("second", run.drafts().iterator().next().subject());
    }

    // ── what is refused ──────────────────────────────────────────────────────

    @Test
    void should_refuse_a_customer_that_is_not_in_the_company() {
        customersExist();   // the repository only returns this company's customers

        ToolResult result = tool.execute(run, input(draft(99, "s", "b")));

        assertEquals("rejected", result.status());
        assertTrue(result.error().contains("no customer with this id in the company"));
        assertEquals(0, run.draftCount());
    }

    @Test
    void should_keep_the_valid_drafts_and_report_the_refused_ones() {
        customersExist(customer(1, "Ann", "France", "ann@example.com"));

        ToolResult result = tool.execute(run, input(draft(1, "s", "b"), draft(2, "s", "b")));

        assertTrue(result.isOk());
        assertEquals(1, run.draftCount());
        assertTrue(result.content().contains("\"accepted\":1"));
        assertTrue(result.content().contains("no customer with this id in the company"));
    }

    @Test
    void should_refuse_a_customer_without_an_email_address() {
        customersExist(customer(1, "Ann", "France", " "));

        ToolResult result = tool.execute(run, input(draft(1, "s", "b")));

        assertEquals("rejected", result.status());
        assertTrue(result.error().contains("no email address"));
    }

    @Test
    void should_refuse_drafts_beyond_the_batch_cap() {
        customersExist(customer(1, "A", "France", "a@example.com"), customer(2, "B", "France", "b@example.com"),
                customer(3, "C", "France", "c@example.com"), customer(4, "D", "France", "d@example.com"));

        ToolResult result = tool.execute(run, input(draft(1, "s", "b"), draft(2, "s", "b"), draft(3, "s", "b"), draft(4, "s", "b")));

        assertTrue(result.isOk());
        assertEquals(3, run.draftCount());
        assertTrue(result.content().contains("the batch is full"));
    }

    @Test
    void should_refuse_more_than_the_per_call_limit() {
        List<Document> drafts = new ArrayList<>();
        for (int i = 0; i < DraftEmailsTool.MAX_DRAFTS_PER_CALL + 1; i++) {
            drafts.add(draft(i, "s", "b"));
        }

        ToolResult result = tool.execute(run, input(drafts.toArray(new Document[0])));

        assertEquals("rejected", result.status());
        assertEquals(0, run.draftCount());
    }

    @Test
    void should_refuse_missing_fields_and_bad_ids() {
        customersExist(customer(1, "Ann", "France", "ann@example.com"));

        assertEquals("rejected", tool.execute(run, input(draft(1, null, "b"))).status());
        assertEquals("rejected", tool.execute(run, input(draft(1, "s", null))).status());
        assertEquals("rejected", tool.execute(run, input(draft(1, "s", "x".repeat(DraftEmailsTool.MAX_BODY_CHARS + 1)))).status());
        assertEquals("rejected", tool.execute(run, input(draft("abc", "s", "b"))).status());
        assertEquals("rejected", tool.execute(run, input(draft(null, "s", "b"))).status());
        assertEquals(0, run.draftCount());
    }

    @Test
    void should_refuse_a_missing_or_empty_drafts_argument() {
        assertEquals("rejected", tool.execute(run, Document.mapBuilder().build()).status());
        assertEquals("rejected", tool.execute(run, input()).status());
        assertEquals("rejected", tool.execute(run, null).status());
    }

    @Test
    void should_flatten_line_breaks_in_the_subject_so_it_cannot_add_mail_headers() {
        customersExist(customer(1, "Ann", "France", "ann@example.com"));

        tool.execute(run, input(draft(1, "Hello\r\nBcc: attacker@example.com", "b")));

        assertEquals("Hello Bcc: attacker@example.com", run.drafts().iterator().next().subject());
    }
}
