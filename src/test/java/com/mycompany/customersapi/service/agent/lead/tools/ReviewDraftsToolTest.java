package com.mycompany.customersapi.service.agent.lead.tools;

import com.mycompany.customersapi.service.agent.lead.ToolResult;
import com.mycompany.customersapi.service.agent.lead.AgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.service.agent.review.DraftReviewException;
import com.mycompany.customersapi.service.agent.review.DraftReviewer;
import com.mycompany.customersapi.service.agent.review.DraftVerdict;
import com.mycompany.customersapi.service.email.PendingDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewDraftsToolTest {

    private DraftReviewer reviewer;
    private ReviewDraftsTool tool;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        reviewer = mock(DraftReviewer.class);
        tool = new ReviewDraftsTool(reviewer, new ObjectMapper(), 2);
        run = new AgentRun(1L, List.of(), "Greet my customers");
    }

    private static PendingDraft draft(long customerId, String subject) {
        return new PendingDraft(UUID.randomUUID(), customerId, "Name " + customerId, "c" + customerId + "@example.com",
                "French", subject, "Body of " + subject);
    }

    private static DraftVerdict ok(long customerId) {
        return new DraftVerdict(customerId, true, null);
    }

    private static DraftVerdict flagged(long customerId, String issue) {
        return new DraftVerdict(customerId, false, issue);
    }

    @Test
    void should_reject_a_review_when_there_are_no_drafts() {
        ToolResult result = tool.execute(run);

        assertEquals("rejected", result.status());
        verify(reviewer, never()).review(any(), anyList());
    }

    @Test
    void should_send_the_drafts_and_the_users_request_to_the_reviewer_and_report_what_it_flagged() {
        run.putDraft(draft(1, "A"));
        run.putDraft(draft(2, "B"));
        when(reviewer.review(eq("Greet my customers"), anyList())).thenReturn(List.of(ok(1), flagged(2, "Wrong language")));

        ToolResult result = tool.execute(run);

        assertTrue(result.isOk());
        assertEquals(2, result.count());
        assertTrue(result.content().contains("Wrong language"));
        assertTrue(result.content().contains("\"approvedNow\":1"));
        assertEquals(1, run.reviews());
        assertEquals(List.of(2L), run.openIssues().stream().map(DraftVerdict::customerId).toList());
    }

    @Test
    void should_only_review_drafts_that_are_new_or_changed_since_the_last_review() {
        run.putDraft(draft(1, "A"));
        run.putDraft(draft(2, "B"));
        when(reviewer.review(any(), anyList())).thenReturn(List.of(ok(1), flagged(2, "Too long")));
        tool.execute(run);

        run.putDraft(draft(2, "B fixed"));
        when(reviewer.review(any(), anyList())).thenReturn(List.of(ok(2)));
        ToolResult second = tool.execute(run);

        assertTrue(second.isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PendingDraft>> sent = ArgumentCaptor.forClass(List.class);
        verify(reviewer, times(2)).review(any(), sent.capture());
        assertEquals(List.of(2L), sent.getAllValues().get(1).stream().map(PendingDraft::customerId).toList());
        assertTrue(run.openIssues().isEmpty());
    }

    @Test
    void should_refuse_to_review_again_when_nothing_changed() {
        run.putDraft(draft(1, "A"));
        when(reviewer.review(any(), anyList())).thenReturn(List.of(flagged(1, "Too long")));
        tool.execute(run);

        ToolResult again = tool.execute(run);

        assertEquals("rejected", again.status());
        assertEquals(1, run.reviews());
        verify(reviewer, times(1)).review(any(), anyList());
    }

    @Test
    void should_refuse_when_the_review_limit_is_reached() {
        run.putDraft(draft(1, "A"));
        when(reviewer.review(any(), anyList())).thenReturn(List.of(flagged(1, "x")));
        tool.execute(run);
        run.putDraft(draft(1, "A2"));
        tool.execute(run);
        run.putDraft(draft(1, "A3"));

        ToolResult third = tool.execute(run);

        assertEquals("rejected", third.status());
        assertTrue(third.error().contains("limit"));
        verify(reviewer, times(2)).review(any(), anyList());
        assertFalse(run.reviewSummary().allDraftsReviewed(), "the last version of the draft was never reviewed");
    }

    @Test
    void should_report_a_failed_review_without_failing_the_request_and_keep_the_drafts_unreviewed() {
        run.putDraft(draft(1, "A"));
        when(reviewer.review(any(), anyList())).thenThrow(new DraftReviewException("boom", null));

        ToolResult result = tool.execute(run);

        assertEquals("failed", result.status());
        assertFalse(result.content().contains("boom"), "the model is not shown the internal error");
        assertEquals(1, run.reviews());
        assertEquals(1, run.draftsToReview().size());
    }

    @Test
    void should_drop_the_verdict_of_a_draft_that_is_removed() {
        run.putDraft(draft(1, "A"));
        when(reviewer.review(any(), anyList())).thenReturn(List.of(flagged(1, "Bad")));
        tool.execute(run);

        run.removeDraft(1L);

        assertTrue(run.openIssues().isEmpty());
        assertNull(run.reviewSummary(), "no drafts left, nothing to summarise");
    }

    @Test
    void should_summarise_open_issues_for_the_response() {
        run.putDraft(draft(1, "A"));
        run.putDraft(draft(2, "B"));
        when(reviewer.review(any(), anyList())).thenReturn(List.of(ok(1), flagged(2, "Wrong language")));
        tool.execute(run);

        var summary = run.reviewSummary();

        assertEquals(1, summary.reviews());
        assertTrue(summary.allDraftsReviewed());
        assertEquals(1, summary.openIssues().size());
        assertEquals(2L, summary.openIssues().getFirst().customerId());
    }
}
