package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Answer of the agentic assistant, with the steps it took and the last query result")
public record CompanyAgentResponse(
        @Schema(description = "The assistant's answer, written after it saw the query results") String answer,
        @Schema(description = "Last query that ran successfully; null if none did") String sql,
        @Schema(description = "Rows of that query (capped by the configured maximum)") int rowCount,
        @Schema(description = "Rows of that query, keyed by column name") List<Map<String, Object>> rows,
        @Schema(description = "Every query the assistant tried, in order") List<AgentStep> steps) {
}
