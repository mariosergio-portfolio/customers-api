package com.mycompany.customersapi.service.agent.lead;

import com.mycompany.customersapi.dto.DraftReviewSummary;
import com.mycompany.customersapi.service.agent.review.DraftVerdict;
import com.mycompany.customersapi.service.email.PendingDraft;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State of one agent run: the company it works for and what its tools have produced so far. A run can start
 * with the drafts of the session's open batch, which the model may then replace or remove.
 *
 * It reaches the tools as an invocation parameter, so the model never sees it and cannot choose the company.
 * A run belongs to one request and its tool calls run one after another, so it needs no locking.
 */
public final class AgentRun {

    private static final String PARAMETER = "run";

    private final Long companyId;
    private final Map<Long, PendingDraft> draftsByCustomerId = new LinkedHashMap<>();
    private final Map<String, ToolResult> outcomesByCallId = new HashMap<>();
    private final String request;
    /** Reviewer verdicts for the drafts as they are now: changing or removing a draft drops its verdict. */
    private final Map<Long, DraftVerdict> verdicts = new LinkedHashMap<>();
    private int reviews;

    /** Outcomes the tools reported that no completed call has claimed yet, oldest first. */
    private final Deque<ToolResult> reported = new ArrayDeque<>();
    private boolean draftsChanged;
    private String lastSql;
    private List<Map<String, Object>> lastRows = List.of();

    public AgentRun(Long companyId) {
        this(companyId, List.of());
    }

    public AgentRun(Long companyId, Collection<PendingDraft> openDrafts) {
        this(companyId, openDrafts, "");
    }

    /** @param request what the user asked for in this request; the reviewer sees it as context */
    public AgentRun(Long companyId, Collection<PendingDraft> openDrafts, String request) {
        this.companyId = companyId;
        this.request = request == null ? "" : request;
        openDrafts.forEach(d -> draftsByCustomerId.put(d.customerId(), d));
    }

    /** The parameters to pass to the assistant so that its tools can find this run. */
    public InvocationParameters asParameters() {
        return InvocationParameters.from(PARAMETER, this);
    }

    public static AgentRun from(InvocationParameters parameters) {
        AgentRun run = parameters.get(PARAMETER);
        if (run == null) {
            throw new IllegalStateException("A tool was called without an agent run");
        }
        return run;
    }

    public Long companyId() {
        return companyId;
    }

    /** Called by a tool with its outcome; returns the text the model receives. */
    public String report(ToolResult result) {
        reported.addLast(result);
        return result.content();
    }

    /**
     * Called after each tool call, including calls the tool never saw (unknown tool, arguments that could not
     * be read): those have no reported outcome, so the text sent back to the model is the refusal.
     *
     * LangChain4j does not tell a tool which call it is serving, so a completed call claims the oldest reported
     * outcome whose text is the text the model received. Matching on that text, not just on order, means a call
     * that never reached a tool cannot take another call's outcome, and an outcome left behind by a call that
     * did not complete is skipped instead of shifting every later call by one.
     */
    public void completeCall(ToolExecution execution) {
        ToolResult outcome = claim(execution.result());
        outcomesByCallId.put(execution.request().id(), outcome != null ? outcome : ToolResult.rejected(execution.result(), null));
    }

    /** Removes and returns the oldest reported outcome with this text, dropping older ones; null if there is none. */
    private ToolResult claim(String resultText) {
        if (reported.stream().noneMatch(r -> r.content().equals(resultText))) {
            return null;
        }
        ToolResult next;
        do {
            next = reported.removeFirst();
        } while (!next.content().equals(resultText));
        return next;
    }

    /** What the tool call with this id did; a call that was never completed counts as rejected. */
    public ToolResult outcomeOf(String callId) {
        return outcomesByCallId.getOrDefault(callId, ToolResult.rejected("The tool call did not run", null));
    }

    public void recordQuery(String sql, List<Map<String, Object>> rows) {
        this.lastSql = sql;
        this.lastRows = rows;
    }

    public String lastSql() {
        return lastSql;
    }

    public List<Map<String, Object>> lastRows() {
        return lastRows;
    }

    /** Stores the draft, replacing an earlier one for the same customer. */
    public void putDraft(PendingDraft draft) {
        draftsByCustomerId.put(draft.customerId(), draft);
        verdicts.remove(draft.customerId());
        draftsChanged = true;
    }

    /** @return true if the customer had a draft, which is now gone */
    public boolean removeDraft(Long customerId) {
        boolean removed = draftsByCustomerId.remove(customerId) != null;
        verdicts.remove(customerId);
        draftsChanged |= removed;
        return removed;
    }

    public boolean hasDraftFor(Long customerId) {
        return draftsByCustomerId.containsKey(customerId);
    }

    public int draftCount() {
        return draftsByCustomerId.size();
    }

    /** True if a tool added, replaced or removed a draft during this run. */
    public boolean draftsChanged() {
        return draftsChanged;
    }

    public Collection<PendingDraft> drafts() {
        return List.copyOf(draftsByCustomerId.values());
    }

    // ── review ───────────────────────────────────────────────────────────────

    public String request() {
        return request;
    }

    /** Counts a call to the reviewer, whether or not it answers, and returns the number so far. */
    public int startReview() {
        return ++reviews;
    }

    public int reviews() {
        return reviews;
    }

    /** Drafts with no verdict yet: new, or changed since they were last reviewed. */
    public List<PendingDraft> draftsToReview() {
        return draftsByCustomerId.values().stream().filter(d -> !verdicts.containsKey(d.customerId())).toList();
    }

    public void recordVerdicts(Collection<DraftVerdict> reviewed) {
        reviewed.stream().filter(v -> draftsByCustomerId.containsKey(v.customerId()))
                .forEach(v -> verdicts.put(v.customerId(), v));
    }

    /** Verdicts that flag a draft still in the batch. */
    public List<DraftVerdict> openIssues() {
        return verdicts.values().stream().filter(v -> !v.approved()).toList();
    }

    /** What the reviewer found in the drafts this request produced; null if the request left the drafts alone. */
    public DraftReviewSummary reviewSummary() {
        if (!draftsChanged || draftsByCustomerId.isEmpty()) {
            return null;
        }
        List<DraftReviewSummary.Issue> issues = openIssues().stream()
                .map(v -> new DraftReviewSummary.Issue(v.customerId(), v.issue())).toList();
        return new DraftReviewSummary(reviews, draftsToReview().isEmpty(), issues);
    }
}
