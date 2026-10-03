package com.mycompany.customersapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.dto.CompanyQueryResponse;
import com.mycompany.customersapi.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Answers free-text questions about a company's customers without sending customer data to the model.
 *
 * The model only sees the table structure (DDL) and the question, and replies with a SQL query plus
 * a short human-readable message about it. The service runs the query itself and returns the message,
 * the SQL and the rows. The message is written before the model could see any data, so it describes
 * what is being returned and never states results. Three layers keep this safe,
 * none of which depends on the model behaving:
 *   1. {@link CompanyQueryValidator}: a single plain SELECT over the unqualified customer table.
 *   2. A CTE named {@code customer}, prepended to the query, that exposes only this company's rows
 *      and the columns listed in {@link #SCHEMA}, so the query cannot see other companies.
 *   3. A read-only transaction with a statement timeout and a row limit.
 * Use a database user that can only SELECT from the customer table for extra protection.
 */
@Service
@Slf4j
public class CompanyAssistantService {

    /** Columns visible to the model. Keep in sync with {@link #SCHEMA} and the CTE in {@link #scopedQuery}. */
    private static final String VISIBLE_COLUMNS = "id, name, email, age, country, phone, created_at";

    /** What the model is told about the data. Describes the scoped customer view, not the raw table. */
    static final String SCHEMA = """
            CREATE TABLE customer (
                id          BIGINT,          -- business id of the customer, unique within the company
                name        VARCHAR(255),
                email       VARCHAR(255),
                age         INTEGER,
                country     VARCHAR(100),
                phone       VARCHAR(50),
                created_at  TIMESTAMP
            );
            """;

    private final CustomerRepository customerRepository;
    private final BedrockService     bedrockService;
    private final CompanyQueryValidator validator;
    private final ObjectMapper       objectMapper;
    private final String             schema;
    private final JdbcTemplate       jdbc;
    private final TransactionTemplate readOnlyTx;

    public CompanyAssistantService(CustomerRepository customerRepository,
                                   BedrockService bedrockService,
                                   CompanyQueryValidator validator,
                                   ObjectMapper objectMapper,
                                   DataSource dataSource,
                                   PlatformTransactionManager txManager,
                                   @Value("${spring.jpa.properties.hibernate.default_schema}") String schema,
                                   @Value("${aws.bedrock.company-query-max-rows:100}") int maxRows,
                                   @Value("${aws.bedrock.company-query-timeout-seconds:5}") int timeoutSeconds) {
        this.customerRepository = customerRepository;
        this.bedrockService = bedrockService;
        this.validator = validator;
        this.objectMapper = objectMapper;
        if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("hibernate.default_schema is not a valid schema name: " + schema);
        }
        this.schema = schema;
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setMaxRows(maxRows);
        this.jdbc.setQueryTimeout(timeoutSeconds);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    public CompanyQueryResponse ask(Long companyId, String prompt) {
        if (!customerRepository.existsByCompanyId(companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found: " + companyId);
        }

        ModelReply reply = parseReply(bedrockService.ask(systemPrompt(), prompt));
        String sql = validator.validate(reply.sql());
        log.info("Company ask: companyId={}, generated SQL: {}", companyId, sql);

        try {
            // The model call above runs outside the transaction so no DB connection is held while waiting on Bedrock.
            List<Map<String, Object>> rows = readOnlyTx.execute(
                    status -> jdbc.queryForList(scopedQuery(companyId, sql)));
            return new CompanyQueryResponse(reply.message(), sql, rows.size(), rows);
        } catch (DataAccessException e) {
            log.warn("Generated SQL failed: {}", e.getMostSpecificCause().getMessage());
            throw new GeneratedQueryException("The generated query failed to run: "
                    + e.getMostSpecificCause().getMessage(), e);
        }
    }

    String systemPrompt() {
        return """
                You turn a user's question about a company's customers into one PostgreSQL query and a short message.
                The data is in this table:

                %s
                Reply with one JSON object and nothing else (no markdown), with these fields:
                - "message": one or two friendly sentences, in the language of the question, saying what the
                  query returns for the user. You have not seen any data, so do not state results, counts or names.
                - "sql": exactly one SELECT statement.

                SQL rules:
                - Use only the table customer and only the columns above.
                - Do not filter by company: the table already contains only the company's customers.
                - Use only these functions: count, sum, avg, min, max, round, abs, ceil, floor, lower, upper,
                  length, trim, substring, concat, coalesce, nullif, date_trunc, now, row_number, rank, dense_rank.
                - Match text case-insensitively, for example lower(country) = 'france'.
                - If the question cannot be answered from this table, set "sql" to null and use "message" to say why.
                """.formatted(SCHEMA);
    }

    /** What the model replied: a message for the user and the SQL to run. */
    record ModelReply(String message, String sql) {
    }

    /** Parses the model's JSON reply, tolerating a markdown fence or text around the object. */
    ModelReply parseReply(String reply) {
        String text = reply == null ? "" : reply.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new GeneratedQueryException("The model did not return the expected JSON reply");
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            throw new GeneratedQueryException("The model did not return the expected JSON reply", e);
        }
        String message = json.path("message").asText("").trim();
        String sql = json.path("sql").isTextual() ? json.path("sql").asText().trim() : "";
        if (sql.isEmpty()) {
            throw new GeneratedQueryException(message.isEmpty()
                    ? "The question cannot be answered from the customer data" : message);
        }
        return new ModelReply(message, sql.replaceFirst("\\s*;\\s*$", ""));
    }

    /** Shadows the customer table with this company's rows. companyId is a Long, so it cannot inject SQL. */
    String scopedQuery(Long companyId, String validatedSql) {
        return "WITH customer AS (SELECT " + VISIBLE_COLUMNS + " FROM " + schema + ".customer WHERE company_id = "
                + companyId.longValue() + ") " + validatedSql;
    }
}
