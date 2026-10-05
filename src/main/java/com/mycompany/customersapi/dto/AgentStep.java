package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One tool call the assistant made while working on the request")
public record AgentStep(
        @Schema(description = "Model call this tool call came from, starting at 1") int round,
        @Schema(description = "Text the model wrote next to the tool call (its reasoning), if any") String note,
        @Schema(description = "Tool the model called: run_query, draft_emails, remove_drafts or review_drafts (the reviewer agent)", example = "run_query") String tool,
        @Schema(description = "SQL the model asked to run (run_query only)") String sql,
        @Schema(description = "ok, rejected (the input was refused) or failed (the database refused the query)") String status,
        @Schema(description = "Rows returned (run_query), drafts stored (draft_emails) or drafts reviewed (review_drafts), when status is ok") Integer rowCount,
        @Schema(description = "Why the call was rejected or failed; this text went back to the model") String error) {
}
