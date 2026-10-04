package com.mycompany.customersapi.service.query;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CompanyQueryExecutorTest {

    private static CompanyQueryExecutor executor(String schema) {
        return new CompanyQueryExecutor(mock(DataSource.class), mock(PlatformTransactionManager.class), schema, 100, 5);
    }

    @Test
    void scopedQueryShadowsCustomerWithThisCompanyOnly() {
        String sql = executor("customer_app").scopedQuery(7L, "SELECT name FROM customer");

        assertEquals("WITH customer AS (SELECT id, name, email, age, country, phone, created_at "
                + "FROM customer_app.customer WHERE company_id = 7) SELECT name FROM customer", sql);
    }

    @Test
    void invalidSchemaNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> executor("x; DROP TABLE customer"));
        assertThrows(IllegalArgumentException.class, () -> executor(null));
    }
}
