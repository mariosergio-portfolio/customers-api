package com.mycompany.customersapi.service.agent;

import com.mycompany.customersapi.service.email.PendingDraft;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.Collection;
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

    private ToolResult pendingOutcome;
    private boolean draftsChanged;
    private String lastSql;
    private List<Map<String, Object>> lastRows = List.of();

    AgentRun(Long companyId) {
        this(companyId, List.of());
    }

    public AgentRun(Long companyId, Collection<PendingDraft> openDrafts) {
        this.companyId = companyId;
        openDrafts.forEach(d -> draftsByCustomerId.put(d.customerId(), d));
    }

    /** The parameters to pass to the assistant so that its tools can find this run. */
    public InvocationParameters asParameters() {
        return InvocationParameters.from(PARAMETER, this);
    }

    static AgentRun from(InvocationParameters parameters) {
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
    String report(ToolResult result) {
        pendingOutcome = result;
        return result.content();
    }

    /**
     * Called after each tool call, including calls the tool never saw (unknown tool, arguments that could not
     * be read): those have no reported outcome, so the text sent back to the model is the refusal.
     */
    public void completeCall(ToolExecution execution) {
        ToolResult outcome = pendingOutcome != null ? pendingOutcome : ToolResult.rejected(execution.result(), null);
        outcomesByCallId.put(execution.request().id(), outcome);
        pendingOutcome = null;
    }

    /** What the tool call with this id did; a call that was never completed counts as rejected. */
    public ToolResult outcomeOf(String callId) {
        return outcomesByCallId.getOrDefault(callId, ToolResult.rejected("The tool call did not run", null));
    }

    void recordQuery(String sql, List<Map<String, Object>> rows) {
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
    void putDraft(PendingDraft draft) {
        draftsByCustomerId.put(draft.customerId(), draft);
        draftsChanged = true;
    }

    /** @return true if the customer had a draft, which is now gone */
    boolean removeDraft(Long customerId) {
        boolean removed = draftsByCustomerId.remove(customerId) != null;
        draftsChanged |= removed;
        return removed;
    }

    boolean hasDraftFor(Long customerId) {
        return draftsByCustomerId.containsKey(customerId);
    }

    int draftCount() {
        return draftsByCustomerId.size();
    }

    /** True if a tool added, replaced or removed a draft during this run. */
    boolean draftsChanged() {
        return draftsChanged;
    }

    Collection<PendingDraft> drafts() {
        return List.copyOf(draftsByCustomerId.values());
    }
}
