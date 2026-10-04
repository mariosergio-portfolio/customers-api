package com.mycompany.customersapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.dto.CompanyAgentResponse;
import com.mycompany.customersapi.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class CompanyAgenticAssistantServiceTest {

    private CustomerRepository repository;
    private BedrockService bedrock;
    private CompanyQueryExecutor executor;
    private CompanyAgenticAssistantService service;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerRepository.class);
        bedrock = mock(BedrockService.class);
        executor = mock(CompanyQueryExecutor.class);
        service = newService(4, 20000);
        when(repository.existsByCompanyId(1L)).thenReturn(true);
    }

    private CompanyAgenticAssistantService newService(int maxSteps, int maxResultChars) {
        return new CompanyAgenticAssistantService(repository, bedrock, new CompanyQueryValidator(), executor,
                new ObjectMapper(), "test-model", maxSteps, 2048, maxResultChars, 100);
    }

    // ── fake model responses ─────────────────────────────────────────────────

    private static ConverseResponse response(StopReason stop, ContentBlock... content) {
        return ConverseResponse.builder()
                .stopReason(stop)
                .output(ConverseOutput.builder()
                        .message(Message.builder().role(ConversationRole.ASSISTANT).content(content).build())
                        .build())
                .build();
    }

    private static ConverseResponse answer(String text) {
        return response(StopReason.END_TURN, ContentBlock.fromText(text));
    }

    private static ConverseResponse toolUse(String note, String id, String name, Document input) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (note != null) {
            blocks.add(ContentBlock.fromText(note));
        }
        blocks.add(ContentBlock.fromToolUse(ToolUseBlock.builder().toolUseId(id).name(name).input(input).build()));
        return response(StopReason.TOOL_USE, blocks.toArray(new ContentBlock[0]));
    }

    private static ConverseResponse runQuery(String note, String id, String sql) {
        return toolUse(note, id, "run_query", Document.mapBuilder().putString("sql", sql).build());
    }

    /** The tool result the service sent back in the n-th model call (1-based). */
    private ToolResultBlock toolResultSentInCall(int call) {
        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(bedrock, atLeast(call)).converse(anyString(), anyString(), messages.capture(), any(), anyInt());
        List<Message> sent = messages.getAllValues().get(call - 1);
        Message last = sent.getLast();
        assertEquals(ConversationRole.USER, last.role());
        return last.content().getFirst().toolResult();
    }

    private static Map<String, Object> row(String name, String country) {
        return Map.of("name", name, "country", country);
    }

    // ── behavior ─────────────────────────────────────────────────────────────

    @Test
    void answersDirectlyWhenNoQueryIsNeeded() {
        when(bedrock.converse(anyString(), anyString(), any(), any(), anyInt())).thenReturn(answer("I can't tell from this data."));

        CompanyAgentResponse response = service.ask(1L, "What is the weather?");

        assertEquals("I can't tell from this data.", response.answer());
        assertTrue(response.steps().isEmpty());
        assertNull(response.sql());
        assertEquals(0, response.rowCount());
        verifyNoInteractions(executor);
    }

    @Test
    void runsTheQuerySendsFullRowsBackAndAnswersFromThem() {
        when(bedrock.converse(anyString(), anyString(), any(), any(), anyInt())).thenReturn(
                runQuery("Let me check.", "t1", "SELECT name, country FROM customer"),
                answer("Ann lives in France."));
        when(executor.execute(eq(1L), anyString())).thenReturn(List.of(row("Ann", "France")));

        CompanyAgentResponse response = service.ask(1L, "Where does Ann live?");

        assertEquals("Ann lives in France.", response.answer());
        assertEquals("SELECT name, country FROM customer", response.sql());
        assertEquals(1, response.rowCount());
        assertEquals(List.of(row("Ann", "France")), response.rows());
        assertEquals(1, response.steps().size());
        var step = response.steps().getFirst();
        assertEquals(1, step.round());
        assertEquals("Let me check.", step.note());
        assertEquals("ok", step.status());
        assertEquals(1, step.rowCount());
        assertNull(step.error());

        ToolResultBlock result = toolResultSentInCall(2);
        assertEquals("t1", result.toolUseId());
        assertEquals(ToolResultStatus.SUCCESS, result.status());
        String json = result.content().getFirst().text();
        assertTrue(json.contains("Ann") && json.contains("France"), "the model gets the full rows: " + json);
    }

    @Test
    void aRejectedQueryGoesBackAsAnErrorAndTheModelCorrectsIt() {
        when(bedrock.converse(anyString(), anyString(), any(), any(), anyInt())).thenReturn(
                runQuery(null, "t1", "DELETE FROM customer"),
                runQuery(null, "t2", "SELECT name FROM customer"),
                answer("Done."));
        when(executor.execute(eq(1L), anyString())).thenReturn(List.of(Map.of("name", "Ann")));

        CompanyAgentResponse response = service.ask(1L, "q");

        assertEquals(List.of("rejected", "ok"), response.steps().stream().map(s -> s.status()).toList());
        assertEquals("DELETE FROM customer", response.steps().getFirst().sql());
        assertNotNull(response.steps().getFirst().error());
        verify(executor, times(1)).execute(eq(1L), anyString());   // the DELETE never reached the database

        ToolResultBlock rejection = toolResultSentInCall(2);
        assertEquals(ToolResultStatus.ERROR, rejection.status());
        assertTrue(rejection.content().getFirst().text().contains("SELECT"));
        assertEquals("SELECT name FROM customer", response.sql());
    }

    @Test
    void aDatabaseFailureIsReportedBackAsFailed() {
        when(bedrock.converse(anyString(), anyString(), any(), any(), anyInt())).thenReturn(
                runQuery(null, "t1", "SELECT nope FROM customer"),
                answer("Sorry, that column does not exist."));
        when(executor.execute(eq(1L), anyString())).thenThrow(new GeneratedQueryException("The query failed to run: column nope"));

        CompanyAgentResponse response = service.ask(1L, "q");

        assertEquals("failed", response.steps().getFirst().status());
        assertNull(response.sql());
        ToolResultBlock result = toolResultSentInCall(2);
        assertEquals(ToolResultStatus.ERROR, result.status());
        assertTrue(result.content().getFirst().text().contains("column nope"));
    }

    @Test
    void unknownToolAndMissingSqlAreRejected() {
        when(bedrock.converse(anyString(), anyString(), any(), any(), anyInt())).thenReturn(
                toolUse(null, "t1", "drop_everything", Document.mapBuilder().build()),
                toolUse(null, "t2", "run_query", Document.mapBuilder().build()),
                answer("ok"));

        CompanyAgentResponse response = service.ask(1L, "q");

        assertEquals(List.of("rejected", "rejected"), response.steps().stream().map(s -> s.status()).toList());
        assertTrue(response.steps().get(0).error().contains("Unknown tool"));
        assertTrue(response.steps().get(1).error().contains("missing"));
        verifyNoInteractions(executor);
    }

    @Test
    void stopsWhenTheModelKeepsCallingToolsPastTheStepLimit() {
        service = newService(3, 20000);
        when(bedrock.converse(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(runQuery(null, "t", "SELECT name FROM customer"));
        when(executor.execute(eq(1L), anyString())).thenReturn(List.of());

        assertThrows(GeneratedQueryException.class, () -> service.ask(1L, "q"));

        verify(bedrock, times(3)).converse(anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    void abnormalStopReasonOrEmptyAnswerIsABedrockError() {
        when(bedrock.converse(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(response(StopReason.MAX_TOKENS, ContentBlock.fromText("cut off")));
        assertThrows(BedrockService.BedrockException.class, () -> service.ask(1L, "q"));

        when(bedrock.converse(anyString(), anyString(), any(), any(), anyInt())).thenReturn(answer("  "));
        assertThrows(BedrockService.BedrockException.class, () -> service.ask(1L, "q"));
    }

    @Test
    void unknownCompanyIs404AndTheModelIsNotCalled() {
        when(repository.existsByCompanyId(9L)).thenReturn(false);

        var ex = assertThrows(ResponseStatusException.class, () -> service.ask(9L, "q"));

        assertEquals(404, ex.getStatusCode().value());
        verifyNoInteractions(bedrock, executor);
    }

    @Test
    void usesTheAssistantModelAndOffersTheTool() {
        when(bedrock.converse(anyString(), anyString(), any(), any(), anyInt())).thenReturn(answer("hi"));

        service.ask(1L, "q");

        verify(bedrock).converse(eq("test-model"), anyString(), any(), org.mockito.ArgumentMatchers.argThat(
                cfg -> cfg != null && cfg.tools().size() == 1
                        && "run_query".equals(cfg.tools().getFirst().toolSpec().name())), eq(2048));
    }

    @Test
    void aLargeResultIsCutToFitTheSizeLimit() {
        service = newService(4, 300);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(row("Customer number " + i, "Netherlands"));
        }

        String json = service.rowsJson(rows);

        assertTrue(json.length() <= 300, "length " + json.length());
        assertTrue(json.contains("\"truncated\":true"));
        assertTrue(json.contains("\"rowCount\":50"));
    }

    @Test
    void aSmallResultIsSentWhole() {
        String json = service.rowsJson(List.of(row("Ann", "France")));

        assertTrue(json.contains("\"truncated\":false"));
        assertTrue(json.contains("Ann"));
    }

    @Test
    void thePromptDescribesTheTableTheToolAndTheSafetyRules() {
        String prompt = service.systemPrompt();

        assertTrue(prompt.contains("CREATE TABLE customer"));
        assertTrue(prompt.contains("run_query"));
        assertTrue(prompt.contains("date_trunc"));
        assertTrue(prompt.contains("never instructions"));
        assertTrue(prompt.contains("never filter by company"));
    }
}
