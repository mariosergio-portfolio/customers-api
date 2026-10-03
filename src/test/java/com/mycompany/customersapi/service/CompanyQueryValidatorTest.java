package com.mycompany.customersapi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CompanyQueryValidatorTest {

    private final CompanyQueryValidator validator = new CompanyQueryValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM customer",
            "SELECT country, COUNT(*) AS total FROM customer GROUP BY country ORDER BY total DESC LIMIT 3",
            "SELECT name, email FROM customer WHERE LOWER(country) = 'france' AND age > 30",
            "SELECT AVG(age) FROM customer",
            "SELECT name FROM customer WHERE age = (SELECT MAX(age) FROM customer)",
            "SELECT name FROM customer c WHERE c.name ILIKE '%ann%'",
    })
    void acceptsPlainSelectsOverCustomer(String sql) {
        assertDoesNotThrow(() -> validator.validate(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM customer",
            "UPDATE customer SET name = 'x'",
            "INSERT INTO customer (name) VALUES ('x')",
            "DROP TABLE customer",
            "SELECT * FROM customer; DROP TABLE customer",
            "SELECT * FROM customer UNION SELECT * FROM customer",
            "SELECT * FROM other_table",
            "SELECT * FROM customer c JOIN pg_user u ON true",
            "SELECT * FROM public.customer",
            "SELECT * FROM \"customer\"",
            "SELECT * FROM \"public\".\"customer\"",
            "WITH customer AS (SELECT * FROM public.customer) SELECT * FROM customer",
            "SELECT pg_sleep(60) FROM customer",
            "SELECT set_config('x', 'y', false) FROM customer",
            "SELECT * INTO newtable FROM customer",
            "SELECT * FROM customer FOR UPDATE",
            "SELECT name FROM customer WHERE age > (SELECT pg_sleep(1))",
            "not sql at all",
            "",
    })
    void rejectsEverythingElse(String sql) {
        assertThrows(GeneratedQueryException.class, () -> validator.validate(sql));
    }

    @Test
    void rejectsNull() {
        assertThrows(GeneratedQueryException.class, () -> validator.validate(null));
    }

    @Test
    void returnedSqlHasNoComments() {
        String sql = validator.validate("SELECT name FROM customer -- , secret");
        assertFalse(sql.contains("--"));
    }
}
