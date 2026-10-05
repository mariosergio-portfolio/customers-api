package com.mycompany.customersapi.service.agent.lead;

/**
 * Outcome of one tool call.
 *
 * @param status  ok, rejected (the input was refused) or failed (the work was attempted and failed)
 * @param content text sent back to the model: JSON when ok, the error message otherwise
 * @param sql     the query involved, for query tools; null otherwise
 * @param count   rows returned or drafts stored; null when nothing was produced
 * @param error   the error message, null when ok
 */
public record ToolResult(String status, String content, String sql, Integer count, String error) {

    public static ToolResult ok(String content, String sql, int count) {
        return new ToolResult("ok", content, sql, count, null);
    }

    public static ToolResult rejected(String error, String sql) {
        return new ToolResult("rejected", error, sql, null, error);
    }

    public static ToolResult failed(String error, String sql) {
        return new ToolResult("failed", error, sql, null, error);
    }

    public boolean isOk() {
        return "ok".equals(status);
    }
}
