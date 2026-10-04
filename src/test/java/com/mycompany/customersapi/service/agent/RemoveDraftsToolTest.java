package com.mycompany.customersapi.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.service.email.PendingDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemoveDraftsToolTest {

    private RemoveDraftsTool tool;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        tool = new RemoveDraftsTool(new ObjectMapper());
        run = new AgentRun(1L, List.of(draft(1), draft(2), draft(3)));
    }

    private static PendingDraft draft(long customerId) {
        return new PendingDraft(UUID.randomUUID(), customerId, "Customer " + customerId, "c" + customerId + "@example.com",
                "French", "subject", "body");
    }

    private static Document ids(Document... ids) {
        return Document.mapBuilder().putList("customerIds", List.of(ids)).build();
    }

    @Test
    void should_remove_the_requested_drafts_and_keep_the_others() {
        ToolResult result = tool.execute(run, ids(Document.fromNumber(2L)));

        assertTrue(result.isOk());
        assertEquals(1, result.count());
        assertEquals(2, run.draftCount());
        assertFalse(run.hasDraftFor(2L));
        assertTrue(run.hasDraftFor(1L) && run.hasDraftFor(3L));
        assertTrue(run.draftsChanged());
        assertTrue(result.content().contains("\"removed\":[2]"));
    }

    @Test
    void should_report_ids_that_have_no_draft_and_still_remove_the_others() {
        ToolResult result = tool.execute(run, ids(Document.fromNumber(1L), Document.fromNumber(99L)));

        assertTrue(result.isOk());
        assertTrue(result.content().contains("\"notInBatch\":[99]"));
        assertEquals(2, run.draftCount());
    }

    @Test
    void should_accept_ids_sent_as_strings_of_digits() {
        ToolResult result = tool.execute(run, ids(Document.fromString("3")));

        assertTrue(result.isOk());
        assertFalse(run.hasDraftFor(3L));
    }

    @Test
    void should_reject_when_nothing_was_removed() {
        ToolResult result = tool.execute(run, ids(Document.fromNumber(99L)));

        assertEquals("rejected", result.status());
        assertEquals(3, run.draftCount());
        assertFalse(run.draftsChanged());
    }

    @Test
    void should_reject_a_missing_empty_or_invalid_ids_argument() {
        assertEquals("rejected", tool.execute(run, Document.mapBuilder().build()).status());
        assertEquals("rejected", tool.execute(run, ids()).status());
        assertEquals("rejected", tool.execute(run, ids(Document.fromString("abc"), Document.fromNumber(1.5))).status());
        assertEquals("rejected", tool.execute(run, null).status());
        assertEquals(3, run.draftCount());
    }
}
