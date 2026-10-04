package com.mycompany.customersapi.service.query;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Accepts only a single, plain SELECT over the unqualified {@code customer} table.
 *
 * The model's SQL is never trusted: this validator is one of three layers, together with the
 * company-scoped {@code customer} CTE and the read-only transaction in
 * {@link com.mycompany.customersapi.service.CompanyAssistantService}. Rejecting schema-qualified and quoted table names keeps
 * every reference bound to that CTE (the finder reports names as written, quotes and schema included), and rejecting WITH keeps the CTE from being redefined.
 *
 * The statement that is returned has been re-rendered by the parser, so comments are gone.
 */
@Component
public class CompanyQueryValidator {

    static final String TABLE = "customer";

    /** Functions the model may call; anything else (pg_sleep, set_config, ...) is rejected. */
    public static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "round", "abs", "ceil", "floor",
            "lower", "upper", "length", "trim", "substring", "concat", "coalesce", "nullif",
            "date_trunc", "now", "row_number", "rank", "dense_rank");

    public String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new GeneratedQueryException("The model returned no SQL");
        }
        Statement statement;
        try {
            // parse() would silently ignore anything after the first ';', so require exactly one statement.
            var statements = CCJSqlParserUtil.parseStatements(sql.trim()).getStatements();
            if (statements.size() != 1) {
                throw new GeneratedQueryException("Only a single plain SELECT statement is allowed");
            }
            statement = statements.getFirst();
        } catch (JSQLParserException e) {
            throw new GeneratedQueryException("The generated SQL could not be parsed");
        }
        if (!(statement instanceof Select select) || !(select instanceof PlainSelect plain)) {
            throw new GeneratedQueryException("Only a single plain SELECT statement is allowed");
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw new GeneratedQueryException("WITH clauses are not allowed");
        }
        if (plain.getIntoTables() != null || plain.getForMode() != null) {
            throw new GeneratedQueryException("SELECT ... INTO and row locking are not allowed");
        }

        Set<String> tables = new TablesNamesFinder<Void>().getTables(statement);
        if (tables.isEmpty() || !tables.stream().allMatch(TABLE::equalsIgnoreCase)) {
            throw new GeneratedQueryException("Only the unqualified table '" + TABLE + "' may be queried");
        }

        new TablesNamesFinder<Void>() {
            @Override
            public <S> Void visit(Function function, S context) {
                String name = function.getName() == null ? "" : function.getName().toLowerCase(Locale.ROOT);
                if (!ALLOWED_FUNCTIONS.contains(name)) {
                    throw new GeneratedQueryException("Function not allowed: " + function.getName());
                }
                return super.visit(function, context);
            }
        }.getTables(statement);

        return statement.toString();
    }
}
