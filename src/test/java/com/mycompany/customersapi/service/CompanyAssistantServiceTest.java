package com.mycompany.customersapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanyAssistantServiceTest {

    private CustomerRepository repository;
    private BedrockService bedrock;
    private CompanyAssistantService service;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerRepository.class);
        bedrock = mock(BedrockService.class);
        service = new CompanyAssistantService(repository, bedrock, new CompanyQueryValidator(), new ObjectMapper(),
                mock(DataSource.class), mock(PlatformTransactionManager.class), "customer_app", 100, 5);
    }

    @Test
    void systemPromptHasSchemaButNoCustomerData() {
        String prompt = service.systemPrompt();
        assertTrue(prompt.contains("CREATE TABLE customer"));
        assertTrue(prompt.contains("\"message\""));
        assertTrue(prompt.contains("\"sql\""));
    }

    @Test
    void parseReplyReadsMessageAndSql() {
        var reply = service.parseReply("{\"message\": \"Here are your customers.\", \"sql\": \"SELECT 1;\"}");
        assertEquals("Here are your customers.", reply.message());
        assertEquals("SELECT 1", reply.sql());
    }

    @Test
    void parseReplyToleratesMarkdownFence() {
        var reply = service.parseReply("```json\n{\"message\": \"Hi\", \"sql\": \"SELECT 1\"}\n```");
        assertEquals("SELECT 1", reply.sql());
    }

    @Test
    void nullSqlIsRejectedWithTheModelMessage() {
        var ex = assertThrows(GeneratedQueryException.class,
                () -> service.parseReply("{\"message\": \"No such column.\", \"sql\": null}"));
        assertEquals("No such column.", ex.getMessage());
    }

    @Test
    void nonJsonReplyIsRejected() {
        assertThrows(GeneratedQueryException.class, () -> service.parseReply("SELECT * FROM customer"));
    }

    @Test
    void scopedQueryShadowsCustomerWithThisCompanyOnly() {
        String sql = service.scopedQuery(7L, "SELECT name FROM customer");
        assertEquals("WITH customer AS (SELECT id, name, email, age, country, phone, created_at "
                + "FROM customer_app.customer WHERE company_id = 7) SELECT name FROM customer", sql);
    }

    @Test
    void unknownCompanyIs404AndModelIsNotCalled() {
        when(repository.existsByCompanyId(9L)).thenReturn(false);
        var ex = assertThrows(ResponseStatusException.class, () -> service.ask(9L, "q"));
        assertEquals(404, ex.getStatusCode().value());
        verifyNoInteractions(bedrock);
    }

    @Test
    void rejectedSqlIsNeverExecuted() {
        when(repository.existsByCompanyId(1L)).thenReturn(true);
        when(bedrock.ask(anyString(), eq("q"))).thenReturn("{\"message\": \"x\", \"sql\": \"DELETE FROM customer\"}");
        assertThrows(GeneratedQueryException.class, () -> service.ask(1L, "q"));
    }

    @Test
    void customerRowsAreNotSentToTheModel() {
        when(repository.existsByCompanyId(1L)).thenReturn(true);
        when(bedrock.ask(anyString(), eq("q"))).thenReturn("{\"message\": \"x\", \"sql\": \"DROP TABLE customer\"}");
        assertThrows(GeneratedQueryException.class, () -> service.ask(1L, "q"));
        verify(repository, never()).findByCompanyIdOrderByIdAsc(any());
    }

    @Test
    void invalidSchemaNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CompanyAssistantService(repository, bedrock,
                new CompanyQueryValidator(), new ObjectMapper(), mock(DataSource.class),
                mock(PlatformTransactionManager.class), "x; DROP TABLE customer", 100, 5));
    }
}
