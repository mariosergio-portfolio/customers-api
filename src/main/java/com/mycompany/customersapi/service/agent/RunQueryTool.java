package com.mycompany.customersapi.service.agent;

import com.mycompany.customersapi.service.query.CompanyQueryExecutor;
import com.mycompany.customersapi.service.query.CompanyQueryValidator;
import com.mycompany.customersapi.service.query.GeneratedQueryException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

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
    private final Tool                  specification;

    public RunQueryTool(CompanyQueryValidator validator,
                 CompanyQueryExecutor executor,
                 ObjectMapper objectMapper,
                 @Value("${aws.bedrock.agent-max-result-chars:20000}") int maxResultChars,
                 @Value("${aws.bedrock.company-query-max-rows:100}") int maxRows) {
        this.validator = validator;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.maxResultChars = maxResultChars;
        this.specification = buildSpecification(maxRows);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Tool specification() {
        return specification;
    }

    @Override
    public ToolResult execute(AgentRun run, Document input) {
        String sql = sqlOf(input);
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

    private static String sqlOf(Document input) {
        if (input == null || !input.isMap()) {
            return null;
        }
        Document sql = input.asMap().get("sql");
        return sql != null && sql.isString() ? sql.asString() : null;
    }

    private static Tool buildSpecification(int maxRows) {
        Document schema = Document.mapBuilder()
                .putString("type", "object")
                .putDocument("properties", Document.mapBuilder()
                        .putDocument("sql", Document.mapBuilder()
                                .putString("type", "string")
                                .putString("description", "One PostgreSQL SELECT statement over the customer table")
                                .build())
                        .build())
                .putList("required", List.of(Document.fromString("sql")))
                .build();
        return Tool.fromToolSpec(ToolSpecification.builder()
                .name(NAME)
                .description("Runs one read-only SELECT over the customer table (already limited to this company) and "
                        + "returns {rowCount, rows}. At most " + maxRows + " rows come back.")
                .inputSchema(ToolInputSchema.fromJson(schema))
                .build());
    }
}
