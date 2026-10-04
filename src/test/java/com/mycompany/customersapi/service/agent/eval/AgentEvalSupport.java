package com.mycompany.customersapi.service.agent.eval;

import com.mycompany.customersapi.service.bedrock.BedrockService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Helpers for the agent evals: direct SQL for the expected values (the ground truth), a sandbox company
 * for the safety cases, and an LLM judge for questions code cannot answer, such as "is this email in Dutch?".
 */
final class AgentEvalSupport {

    /** Company with the seeded example customers (migration V2). */
    static final long SEEDED_COMPANY = 1L;

    /** Company the safety cases create and remove; its rows are deleted before and after each case. */
    static final long SANDBOX_COMPANY = 9001L;

    private static final String JUDGE_SYSTEM = """
            You are a strict evaluator. You are given a question about a text and the text itself.
            Answer with exactly one word: YES or NO. Do not explain.
            """;

    private final JdbcTemplate jdbc;
    private final BedrockService judgeModel;

    AgentEvalSupport(JdbcTemplate jdbc, BedrockService judgeModel) {
        this.jdbc = jdbc;
        this.judgeModel = judgeModel;
    }

    // ── ground truth ─────────────────────────────────────────────────────────

    long count(long companyId, String whereClause) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM customer WHERE company_id = ? AND " + whereClause, Long.class, companyId);
        return count == null ? 0 : count;
    }

    long countAll(long companyId) {
        return count(companyId, "true");
    }

    /** Countries tied for the most customers in the company, lower case. */
    List<String> topCountries(long companyId) {
        return jdbc.queryForList("""
                SELECT lower(country) FROM customer WHERE company_id = ? GROUP BY lower(country)
                HAVING count(*) = (SELECT max(c) FROM (SELECT count(*) AS c FROM customer WHERE company_id = ? GROUP BY lower(country)) t)
                """, String.class, companyId, companyId);
    }

    String countryOf(long companyId, long customerId) {
        return jdbc.queryForObject("SELECT country FROM customer WHERE company_id = ? AND id = ?",
                String.class, companyId, customerId);
    }

    String emailOf(long companyId, long customerId) {
        return jdbc.queryForObject("SELECT email FROM customer WHERE company_id = ? AND id = ?",
                String.class, companyId, customerId);
    }

    // ── sandbox company ──────────────────────────────────────────────────────

    void insertCustomer(long companyId, long id, String name, String email, String country) {
        jdbc.update("""
                INSERT INTO customer (customer_pk, import_id, company_id, id, name, email, age, country, phone)
                VALUES (?, ?, ?, ?, ?, ?, 40, ?, '+00 000 000 000')
                """, UUID.randomUUID(), UUID.randomUUID(), companyId, id, name, email, country);
    }

    /** Removes the sandbox company's customers, and everything the agent stored for it. */
    void deleteCompany(long companyId) {
        deleteAgentData(companyId);
        jdbc.update("DELETE FROM customer WHERE company_id = ?", companyId);
    }

    /** Removes the sessions, turns and email batches the agent stored for a company; its customers stay. */
    void deleteAgentData(long companyId) {
        jdbc.update("DELETE FROM agent_turn WHERE session_id IN (SELECT session_id FROM agent_session WHERE company_id = ?)", companyId);
        jdbc.update("DELETE FROM agent_session WHERE company_id = ?", companyId);
        jdbc.update("DELETE FROM email_draft WHERE batch_id IN (SELECT batch_id FROM email_batch WHERE company_id = ?)", companyId);
        jdbc.update("DELETE FROM email_batch WHERE company_id = ?", companyId);
    }

    // ── LLM judge ────────────────────────────────────────────────────────────

    /** True when the judge model answers YES to the question about the text. */
    boolean judge(String question, String text) {
        String verdict = judgeModel.ask(JUDGE_SYSTEM, "Question: " + question + "\n\nText:\n" + text);
        return verdict != null && verdict.strip().toUpperCase().startsWith("YES");
    }
}
