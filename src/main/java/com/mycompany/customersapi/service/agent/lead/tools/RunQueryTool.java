package com.mycompany.customersapi.service.agent.lead.tools;

import com.mycompany.customersapi.service.agent.lead.ToolResult;
import com.mycompany.customersapi.service.agent.lead.AgentRun;
import com.mycompany.customersapi.service.query.CompanyQueryExecutor;
import com.mycompany.customersapi.service.query.CompanyQueryValidator;
import com.mycompany.customersapi.service.query.GeneratedQueryException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lets the agent read the company's customers with one read-only SELECT at a time.
 *
 * The SQL is never trusted: it goes through {@link CompanyQueryValidator} and {@link CompanyQueryExecutor}
 * (single SELECT, allowed functions, this company's rows only, read-only, timeout, row cap). The rows
 * come back to the model, so names, emails and phones are visible to it.
 */
@Component
@Slf4j
public class RunQueryTool implements AgentTool {

    public static final String NAME = "run_query";

    private final CompanyQueryValidator validator;
    private final CompanyQueryExecutor  executor;
    private final ObjectMapper          objectMapper;
    private final int                   maxResultChars;

    public RunQueryTool(CompanyQueryValidator validator,
                 CompanyQueryExecutor executor,
                 ObjectMapper objectMapper,
                 @Value("${aws.bedrock.agent-max-result-chars:20000}") int maxResultChars) {
        this.validator = validator;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.maxResultChars = maxResultChars;
    }

    @Tool(name = NAME, value = "Runs one read-only SELECT over the customer table (already limited to this company) and "
            + "returns {rowCount, rows}. The number of rows is capped: when truncated is true, narrow the query.")
    String runQuery(@P("One PostgreSQL SELECT statement over the customer table") String sql,
                    InvocationParameters parameters) {
        AgentRun run = AgentRun.from(parameters);
        return run.report(execute(run, sql));
    }

    ToolResult execute(AgentRun run, String sql) {
        if (sql == null || sql.isBlank()) {
            return ToolResult.rejected("The sql argument is missing", null);
        }

        String validated;
        try {
            validated = validator.validate(sql);
        } catch (GeneratedQueryException e) {
            log.info("Agent query rejected: {} ({})", sql, e.getMessage());
            return ToolResult.rejected(e.getMessage(), sql);
        }
        try {
            List<Map<String, Object>> rows = executor.execute(run.companyId(), validated);
            log.info("Agent query ok: companyId={}, rows={}, sql={}", run.companyId(), rows.size(), validated);
            run.recordQuery(validated, rows);
            return ToolResult.ok(rowsJson(rows), validated, rows.size());
        } catch (GeneratedQueryException e) {
            log.info("Agent query failed: {} ({})", validated, e.getMessage());
            return ToolResult.failed(e.getMessage(), validated);
        }
    }

    /** {"rowCount": n, "rows": [...]}, with rows dropped from the end if it would not fit the size limit. */
    public String rowsJson(List<Map<String, Object>> rows) {
        int keep = rows.size();
        while (true) {
            String json = json(Map.of("rowCount", rows.size(), "returnedRows", keep,
                    "truncated", keep < rows.size(), "rows", rows.subList(0, keep)));
            if (json.length() <= maxResultChars || keep == 0) {
                return json;
            }
            keep = keep / 2;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize a tool result", e);
        }
    }
}
