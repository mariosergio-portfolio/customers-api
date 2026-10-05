package com.mycompany.customersapi.service.agent.lead.tools;

import com.mycompany.customersapi.service.agent.lead.ToolResult;
import com.mycompany.customersapi.service.agent.lead.AgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.service.email.PendingDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
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

    @Test
    void should_remove_the_requested_drafts_and_keep_the_others() {
        ToolResult result = tool.execute(run, List.of(2L));

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
        ToolResult result = tool.execute(run, List.of(1L, 99L));

        assertTrue(result.isOk());
        assertTrue(result.content().contains("\"notInBatch\":[99]"));
        assertEquals(2, run.draftCount());
    }

    @Test
    void should_reject_when_nothing_was_removed() {
        ToolResult result = tool.execute(run, List.of(99L));

        assertEquals("rejected", result.status());
        assertEquals(3, run.draftCount());
        assertFalse(run.draftsChanged());
    }

    @Test
    void should_ignore_null_ids_and_reject_a_missing_or_empty_ids_argument() {
        assertEquals("rejected", tool.execute(run, List.of()).status());
        assertEquals("rejected", tool.execute(run, Arrays.asList((Long) null)).status());
        assertEquals("rejected", tool.execute(run, null).status());
        assertEquals(3, run.draftCount());
    }
}
