package com.mycompany.customersapi.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** State of one agent run: the company it works for and what its tools have produced so far. */
final class AgentRun {

    private final Long companyId;
    private final Map<Long, PendingDraft> draftsByCustomerId = new LinkedHashMap<>();

    private String lastSql;
    private List<Map<String, Object>> lastRows = List.of();

    AgentRun(Long companyId) {
        this.companyId = companyId;
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
    void putDraft(Long customerId, PendingDraft draft) {
        draftsByCustomerId.put(customerId, draft);
    }

    boolean hasDraftFor(Long customerId) {
        return draftsByCustomerId.containsKey(customerId);
    }

    int draftCount() {
        return draftsByCustomerId.size();
    }

    Collection<PendingDraft> drafts() {
        return List.copyOf(draftsByCustomerId.values());
    }
}
