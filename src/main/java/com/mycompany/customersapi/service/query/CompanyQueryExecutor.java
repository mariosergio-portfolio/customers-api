package com.mycompany.customersapi.service.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Runs an already validated SELECT for one company: read-only transaction, statement timeout and row cap,
 * against a {@code customer} CTE that exposes only that company's rows and the columns the model knows.
 *
 * Pair it with {@link CompanyQueryValidator}; this class does not check the SQL itself.
 */
@Component
public class CompanyQueryExecutor {

    /** Columns the model may read. Keep in sync with {@link com.mycompany.customersapi.service.agent.lead.LeadAgentFactory#SCHEMA}. */
    static final String VISIBLE_COLUMNS = "id, name, email, age, country, phone, created_at";

    private final String             schema;
    private final JdbcTemplate       jdbc;
    private final TransactionTemplate readOnlyTx;

    public CompanyQueryExecutor(DataSource dataSource,
                                PlatformTransactionManager txManager,
                                @Value("${spring.jpa.properties.hibernate.default_schema}") String schema,
                                @Value("${aws.bedrock.company-query-max-rows:100}") int maxRows,
                                @Value("${aws.bedrock.company-query-timeout-seconds:5}") int timeoutSeconds) {
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

    /** @throws GeneratedQueryException if the database refuses the query (bad column, timeout, ...) */
    public List<Map<String, Object>> execute(Long companyId, String validatedSql) {
        try {
            List<Map<String, Object>> rows = readOnlyTx.execute(
                    status -> jdbc.queryForList(scopedQuery(companyId, validatedSql)));
            return rows == null ? List.of() : rows;
        } catch (DataAccessException e) {
            throw new GeneratedQueryException("The query failed to run: " + e.getMostSpecificCause().getMessage(), e);
        }
    }

    /** Shadows the customer table with this company's rows. companyId is a Long, so it cannot inject SQL. */
    String scopedQuery(Long companyId, String validatedSql) {
        return "WITH " + CompanyQueryValidator.TABLE + " AS (SELECT " + VISIBLE_COLUMNS + " FROM " + schema
                + ".customer WHERE company_id = " + companyId.longValue() + ") " + validatedSql;
    }
}
