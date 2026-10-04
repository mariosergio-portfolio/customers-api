package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Answer of the agentic assistant, with the steps it took, the last query result and any emails it drafted")
public record CompanyAgentResponse(
        @Schema(description = "Conversation id; send it back as sessionId to continue the conversation and refine the drafts")
        UUID sessionId,
        @Schema(description = "The assistant's answer, written after it saw the query results") String answer,
        @Schema(description = "Last query that ran successfully in this request; null if none did") String sql,
        @Schema(description = "Rows of that query (capped by the configured maximum)") int rowCount,
        @Schema(description = "Rows of that query, keyed by column name") List<Map<String, Object>> rows,
        @Schema(description = "Every tool call the assistant made in this request, in order") List<AgentStep> steps,
        @Schema(description = "The batch of emails under review in this conversation, waiting for approval; null if there is none. Nothing is sent until the batch is approved.")
        EmailBatchResponse emailBatch) {
}
