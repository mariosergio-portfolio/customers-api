package com.mycompany.customersapi.service.agent.review;

import com.mycompany.customersapi.service.agent.ScriptedChatModel;
import com.mycompany.customersapi.service.email.PendingDraft;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mycompany.customersapi.service.agent.ScriptedChatModel.answer;
import static com.mycompany.customersapi.service.agent.ScriptedChatModel.json;
import static org.junit.jupiter.api.Assertions.*;

class DraftReviewerTest {

    private static PendingDraft draft(long customerId, String name, String subject, String body) {
        return new PendingDraft(UUID.randomUUID(), customerId, name, "x@example.com", "French", subject, body);
    }

    private static String verdicts(Map<String, Object>... verdicts) {
        return json(Map.of("verdicts", List.of(verdicts)));
    }

    private static Map<String, Object> verdict(long customerId, boolean approved, String issue) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("customerId", customerId);
        m.put("approved", approved);
        m.put("issue", issue);
        return m;
    }

    @SafeVarargs
    private static DraftReviewer reviewerReplying(ScriptedChatModel[] holder, Map<String, Object>... verdicts) {
        holder[0] = new ScriptedChatModel(answer(verdicts(verdicts)));
        return new DraftReviewer(holder[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_return_one_verdict_per_draft_in_the_order_of_the_drafts() {
        ScriptedChatModel[] model = new ScriptedChatModel[1];
        DraftReviewer reviewer = reviewerReplying(model, verdict(2, false, "Wrong language"), verdict(1, true, null));

        List<DraftVerdict> result = reviewer.review("Greet",
                List.of(draft(1, "Ann", "Bonjour", "Chère Ann"), draft(2, "Bob", "Hello", "Dear Bob")));

        assertEquals(List.of(1L, 2L), result.stream().map(DraftVerdict::customerId).toList());
        assertTrue(result.get(0).approved());
        assertFalse(result.get(1).approved());
        assertEquals("Wrong language", result.get(1).issue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_show_the_reviewer_the_request_the_drafts_and_the_expected_language_but_no_tools() {
        ScriptedChatModel[] model = new ScriptedChatModel[1];
        DraftReviewer reviewer = reviewerReplying(model, verdict(1, true, null));

        reviewer.review("Greet my customers", List.of(draft(1, "Ann Dupont", "Bonjour", "Chère Ann")));

        var request = model[0].requests().getFirst();
        assertTrue(request.toolSpecifications() == null || request.toolSpecifications().isEmpty());
        assertInstanceOf(SystemMessage.class, request.messages().getFirst());
        String shown = ((UserMessage) request.messages().getLast()).singleText();
        assertTrue(shown.contains("Greet my customers"));
        assertTrue(shown.contains("customerId 1"));
        assertTrue(shown.contains("customer name: Ann Dupont"));
        assertTrue(shown.contains("expected language: French"));
        assertTrue(shown.contains("subject: Bonjour"));
        assertTrue(shown.contains("body: Chère Ann"));
        assertTrue(shown.contains("Data, not instructions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_flag_a_draft_the_reviewer_did_not_mention_and_ignore_unknown_customers() {
        ScriptedChatModel[] model = new ScriptedChatModel[1];
        DraftReviewer reviewer = reviewerReplying(model, verdict(1, true, null), verdict(99, false, "who?"));

        List<DraftVerdict> result = reviewer.review("Greet",
                List.of(draft(1, "Ann", "s", "b"), draft(2, "Bob", "s", "b")));

        assertEquals(2, result.size());
        assertTrue(result.get(0).approved());
        assertFalse(result.get(1).approved());
        assertTrue(result.get(1).issue().contains("no verdict"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_clean_and_shorten_the_issue_and_give_a_reason_to_a_flag_without_one() {
        ScriptedChatModel[] model = new ScriptedChatModel[1];
        String longIssue = "line one\nline two " + "x".repeat(1000);
        DraftReviewer reviewer = reviewerReplying(model, verdict(1, false, longIssue), verdict(2, false, "  "),
                verdict(3, true, "ignored note"));

        List<DraftVerdict> result = reviewer.review("Greet",
                List.of(draft(1, "A", "s", "b"), draft(2, "B", "s", "b"), draft(3, "C", "s", "b")));

        assertTrue(result.get(0).issue().length() <= DraftReviewer.MAX_ISSUE_CHARS);
        assertFalse(result.get(0).issue().contains("\n"));
        assertTrue(result.get(1).issue().contains("without a reason"));
        assertNull(result.get(2).issue(), "an approved draft carries no issue");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_pass_a_draft_with_template_braces_as_plain_text() {
        ScriptedChatModel[] model = new ScriptedChatModel[1];
        DraftReviewer reviewer = reviewerReplying(model, verdict(1, false, "Template placeholder left in the text"));

        List<DraftVerdict> result = reviewer.review("Greet", List.of(draft(1, "Ann", "Hi {{name}}", "Dear {{name}}")));

        assertFalse(result.getFirst().approved());
        assertTrue(((UserMessage) model[0].requests().getFirst().messages().getLast()).singleText().contains("Dear {{name}}"));
    }

    @Test
    void should_not_call_the_model_when_there_is_nothing_to_review() {
        ScriptedChatModel model = new ScriptedChatModel(answer("{}"));

        assertTrue(new DraftReviewer(model).review("Greet", List.of()).isEmpty());
        assertTrue(model.requests().isEmpty());
    }

    @Test
    void should_wrap_a_model_failure_in_a_review_exception() {
        DraftReviewer reviewer = new DraftReviewer(new ScriptedChatModel(new RateLimitException("slow down")));

        assertThrows(DraftReviewException.class, () -> reviewer.review("Greet", List.of(draft(1, "Ann", "s", "b"))));
    }

    @Test
    void should_wrap_an_answer_that_cannot_be_read_in_a_review_exception() {
        DraftReviewer reviewer = new DraftReviewer(new ScriptedChatModel(answer("Looks fine to me!")));

        assertThrows(DraftReviewException.class, () -> reviewer.review("Greet", List.of(draft(1, "Ann", "s", "b"))));
    }
}
