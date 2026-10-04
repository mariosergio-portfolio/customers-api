package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One query the assistant tried while working on the question")
public record AgentStep(
        @Schema(description = "Model call this query came from, starting at 1") int round,
        @Schema(description = "Text the model wrote next to the query (its reasoning), if any") String note,
        @Schema(description = "SQL the model asked to run") String sql,
        @Schema(description = "ok, rejected (the validator refused it) or failed (the database refused it)") String status,
        @Schema(description = "Rows returned, when status is ok") Integer rowCount,
        @Schema(description = "Why the query was rejected or failed; this text went back to the model") String error) {
}
