package com.mycompany.customersapi.service.agent.lead.tools;

import com.mycompany.customersapi.service.agent.lead.ToolResult;
import com.mycompany.customersapi.service.agent.lead.AgentRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.service.agent.review.DraftReviewException;
import com.mycompany.customersapi.service.agent.review.DraftReviewer;
import com.mycompany.customersapi.service.agent.review.DraftVerdict;
import com.mycompany.customersapi.service.email.PendingDraft;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the agent hand its drafts to the reviewer agent. The reviewer only judges: it cannot change a draft,
 * so fixing what it flags stays with the agent that called this tool. Only drafts without a verdict are sent,
 * which are the new ones and the ones changed since the last review, and the number of reviews per request is capped.
 */
@Component
@Slf4j
public class ReviewDraftsTool implements AgentTool {

    public static final String NAME = "review_drafts";

    private final DraftReviewer reviewer;
    private final ObjectMapper  objectMapper;
    private final int           maxReviews;

    public ReviewDraftsTool(DraftReviewer reviewer,
                            ObjectMapper objectMapper,
                            @Value("${assistant.review.max-rounds:2}") int maxReviews) {
        this.reviewer = reviewer;
        this.objectMapper = objectMapper;
        this.maxReviews = maxReviews;
    }

    @Tool(name = NAME, value = "Asks a separate reviewer agent to check the drafts in the batch that are new or changed since "
            + "the last review. It returns which drafts are approved and which are flagged, with what to fix. It does not "
            + "change any draft. Fix flagged drafts with draft_emails or remove_drafts, then call it again; the number "
            + "of reviews per request is capped.")
    String reviewDrafts(InvocationParameters parameters) {
        AgentRun run = AgentRun.from(parameters);
        return run.report(execute(run));
    }

    ToolResult execute(AgentRun run) {
        List<PendingDraft> toReview = run.draftsToReview();
        if (run.draftCount() == 0) {
            return ToolResult.rejected("There are no drafts to review", null);
        }
        if (toReview.isEmpty()) {
            return ToolResult.rejected("Every draft was already reviewed and has not changed since. Do not review again", null);
        }
        if (run.reviews() >= maxReviews) {
            return ToolResult.rejected("The review limit of " + maxReviews + " per request is reached. Stop revising and tell "
                    + "the user which drafts are still flagged", null);
        }

        int round = run.startReview();
        List<DraftVerdict> verdicts;
        try {
            verdicts = reviewer.review(run.request(), toReview);
        } catch (DraftReviewException e) {
            log.warn("Draft review unavailable: companyId={}, {}", run.companyId(), e.getMessage());
            return ToolResult.failed("The reviewer is not available, so the drafts were not reviewed. Tell the user that.", null);
        }
        run.recordVerdicts(verdicts);

        List<Map<String, Object>> flagged = run.openIssues().stream().map(ReviewDraftsTool::flag).toList();
        int approved = (int) verdicts.stream().filter(DraftVerdict::approved).count();
        String result = json(Map.of("review", round, "maxReviews", maxReviews, "reviewedNow", verdicts.size(),
                "approvedNow", approved, "flagged", flagged));
        return ToolResult.ok(result, null, verdicts.size());
    }

    private static Map<String, Object> flag(DraftVerdict verdict) {
        Map<String, Object> flag = new LinkedHashMap<>();
        flag.put("customerId", verdict.customerId());
        flag.put("issue", verdict.issue());
        return flag;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize a tool result", e);
        }
    }
}
