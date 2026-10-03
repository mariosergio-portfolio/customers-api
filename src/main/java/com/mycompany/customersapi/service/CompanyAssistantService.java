package com.mycompany.customersapi.service;

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
 * The model only sees the table structure (DDL) and the question, and replies with a SQL query.
 * The service then runs that query itself and returns the rows. Three layers keep this safe,
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

    static final String NO_QUERY = "NO_QUERY";

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
    private final JdbcTemplate       jdbc;
    private final TransactionTemplate readOnlyTx;

    public CompanyAssistantService(CustomerRepository customerRepository,
                                   BedrockService bedrockService,
                                   CompanyQueryValidator validator,
                                   DataSource dataSource,
                                   PlatformTransactionManager txManager,
                                   @Value("${aws.bedrock.company-query-max-rows:100}") int maxRows,
                                   @Value("${aws.bedrock.company-query-timeout-seconds:5}") int timeoutSeconds) {
        this.customerRepository = customerRepository;
        this.bedrockService = bedrockService;
        this.validator = validator;
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

        String generated = bedrockService.ask(systemPrompt(), prompt);
        String sql = validator.validate(extractSql(generated));
        log.info("Company ask: companyId={}, generated SQL: {}", companyId, sql);

        try {
            // The model call above runs outside the transaction so no DB connection is held while waiting on Bedrock.
            List<Map<String, Object>> rows = readOnlyTx.execute(
                    status -> jdbc.queryForList(scopedQuery(companyId, sql)));
            return new CompanyQueryResponse(sql, rows.size(), rows);
        } catch (DataAccessException e) {
            log.warn("Generated SQL failed: {}", e.getMostSpecificCause().getMessage());
            throw new GeneratedQueryException("The generated query failed to run: "
                    + e.getMostSpecificCause().getMessage(), e);
        }
    }

    String systemPrompt() {
        return """
                You translate a user's question about a company's customers into one PostgreSQL query.
                The data is in this table:

                %s
                Rules:
                - Reply with exactly one SELECT statement and nothing else: no explanation, no markdown.
                - Use only the table customer and only the columns above.
                - Do not filter by company: the table already contains only the company's customers.
                - Use only these functions: count, sum, avg, min, max, round, abs, ceil, floor, lower, upper,
                  length, trim, substring, concat, coalesce, nullif, date_trunc, now, row_number, rank, dense_rank.
                - Match text case-insensitively, for example lower(country) = 'france'.
                - If the question cannot be answered from this table, reply exactly: %s
                """.formatted(SCHEMA, NO_QUERY);
    }

    /** Strips a markdown fence and a trailing semicolon; rejects the model's "cannot answer" reply. */
    String extractSql(String reply) {
        String sql = reply == null ? "" : reply.trim();
        if (sql.startsWith("```")) {
            sql = sql.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```\\s*$", "").trim();
        }
        if (sql.equals(NO_QUERY)) {
            throw new GeneratedQueryException("The question cannot be answered from the customer data");
        }
        return sql.replaceFirst("\\s*;\\s*$", "");
    }

    /** Shadows the customer table with this company's rows. companyId is a Long, so it cannot inject SQL. */
    static String scopedQuery(Long companyId, String validatedSql) {
        return "WITH customer AS (SELECT " + VISIBLE_COLUMNS + " FROM public.customer WHERE company_id = "
                + companyId.longValue() + ") " + validatedSql;
    }
}
