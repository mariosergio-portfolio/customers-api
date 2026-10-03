package com.mycompany.customersapi.service;

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
        service = new CompanyAssistantService(repository, bedrock, new CompanyQueryValidator(),
                mock(DataSource.class), mock(PlatformTransactionManager.class), 100, 5);
    }

    @Test
    void systemPromptHasSchemaButNoCustomerData() {
        String prompt = service.systemPrompt();
        assertTrue(prompt.contains("CREATE TABLE customer"));
        assertTrue(prompt.contains(CompanyAssistantService.NO_QUERY));
    }

    @Test
    void extractSqlStripsFenceAndSemicolon() {
        assertEquals("SELECT 1", service.extractSql("```sql\nSELECT 1;\n```"));
        assertEquals("SELECT 1", service.extractSql("  SELECT 1 ;  "));
    }

    @Test
    void noQueryReplyIsRejected() {
        assertThrows(GeneratedQueryException.class, () -> service.extractSql("NO_QUERY"));
    }

    @Test
    void scopedQueryShadowsCustomerWithThisCompanyOnly() {
        String sql = CompanyAssistantService.scopedQuery(7L, "SELECT name FROM customer");
        assertEquals("WITH customer AS (SELECT id, name, email, age, country, phone, created_at "
                + "FROM public.customer WHERE company_id = 7) SELECT name FROM customer", sql);
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
        when(bedrock.ask(anyString(), eq("q"))).thenReturn("DELETE FROM customer");
        assertThrows(GeneratedQueryException.class, () -> service.ask(1L, "q"));
    }

    @Test
    void customerRowsAreNotSentToTheModel() {
        when(repository.existsByCompanyId(1L)).thenReturn(true);
        when(bedrock.ask(anyString(), eq("q"))).thenReturn("DROP TABLE customer");
        assertThrows(GeneratedQueryException.class, () -> service.ask(1L, "q"));
        verify(repository, never()).findByCompanyIdOrderByIdAsc(any());
    }
}
