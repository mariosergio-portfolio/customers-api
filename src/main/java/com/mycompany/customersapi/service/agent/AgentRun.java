package com.mycompany.customersapi.service.agent;

import com.mycompany.customersapi.service.email.PendingDraft;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State of one agent run: the company it works for and what its tools have produced so far. A run can start
 * with the drafts of the session's open batch, which the model may then replace or remove.
 */
final class AgentRun {

    private final Long companyId;
    private final Map<Long, PendingDraft> draftsByCustomerId = new LinkedHashMap<>();

    private boolean draftsChanged;
    private String lastSql;
    private List<Map<String, Object>> lastRows = List.of();

    AgentRun(Long companyId) {
        this(companyId, List.of());
    }

    AgentRun(Long companyId, Collection<PendingDraft> openDrafts) {
        this.companyId = companyId;
        openDrafts.forEach(d -> draftsByCustomerId.put(d.customerId(), d));
    }

    Long companyId() {
        return companyId;
    }

    void recordQuery(String sql, List<Map<String, Object>> rows) {
        this.lastSql = sql;
        this.lastRows = rows;
    }

    String lastSql() {
        return lastSql;
    }

    List<Map<String, Object>> lastRows() {
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
