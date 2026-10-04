package com.mycompany.customersapi.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.dto.CompanyAgentResponse;
import com.mycompany.customersapi.dto.EmailBatchResponse;
import com.mycompany.customersapi.repository.CustomerRepository;
import com.mycompany.customersapi.service.bedrock.BedrockService;
import com.mycompany.customersapi.service.email.EmailBatchService;
import com.mycompany.customersapi.service.email.PendingDraft;
import com.mycompany.customersapi.service.query.CompanyQueryExecutor;
import com.mycompany.customersapi.service.query.CompanyQueryValidator;
import com.mycompany.customersapi.service.query.GeneratedQueryException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.mycompany.customersapi.service.agent.ScriptedChatModel.answer;
import static com.mycompany.customersapi.service.agent.ScriptedChatModel.cutOff;
import static com.mycompany.customersapi.service.agent.ScriptedChatModel.json;
import static com.mycompany.customersapi.service.agent.ScriptedChatModel.request;
import static com.mycompany.customersapi.service.agent.ScriptedChatModel.toolCall;
import static com.mycompany.customersapi.service.agent.ScriptedChatModel.toolCalls;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class CompanyAgenticAssistantServiceTest {

    private CustomerRepository repository;
    private CompanyQueryExecutor executor;
    private EmailBatchService emailBatchService;
    private AgentSessionService sessions;
    private RunQueryTool runQueryTool;
    private ScriptedChatModel model;
    private CompanyAgenticAssistantService service;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerRepository.class);
        executor = mock(CompanyQueryExecutor.class);
        emailBatchService = mock(EmailBatchService.class);
        sessions = mock(AgentSessionService.class);
        when(sessions.open(any(), any())).thenReturn(new AgentSessionService.SessionState(UUID.randomUUID(), List.of(), null));
        when(repository.existsByCompanyId(1L)).thenReturn(true);
    }

    /** Builds the service over a model that replays these replies (see {@link ScriptedChatModel}). */
    private ScriptedChatModel script(Object... replies) {
        return scriptWithLimits(4, 20000, replies);
    }

    private ScriptedChatModel scriptWithLimits(int maxSteps, int maxResultChars, Object... replies) {
        model = new ScriptedChatModel(replies);
        ObjectMapper mapper = new ObjectMapper();
        runQueryTool = new RunQueryTool(new CompanyQueryValidator(), executor, mapper, maxResultChars);
        service = new CompanyAgenticAssistantService(repository, model, sessions, new DraftBatchCoordinator(emailBatchService),
                List.of(runQueryTool, new DraftEmailsTool(repository, mapper, 25), new RemoveDraftsTool(mapper)), maxSteps, 25);
        return model;
    }

    private static Map<String, Object> row(String name, String country) {
        return Map.of("name", name, "country", country);
    }

    private static String runQueryArguments(String sql) {
        return json(Map.of("sql", sql));
    }

    // ── behavior ─────────────────────────────────────────────────────────────

    @Test
    void answersDirectlyWhenNoQueryIsNeeded() {
        script(answer("I can't tell from this data."));

        CompanyAgentResponse response = service.ask(1L, "What is the weather?");

        assertEquals("I can't tell from this data.", response.answer());
        assertTrue(response.steps().isEmpty());
        assertNull(response.sql());
        assertEquals(0, response.rowCount());
        assertNull(response.emailBatch());
        verifyNoInteractions(executor, emailBatchService);
    }

    @Test
    void runsTheQuerySendsFullRowsBackAndAnswersFromThem() {
        script(toolCall("Let me check.", "t1", "run_query", runQueryArguments("SELECT name, country FROM customer")),
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
        assertEquals("run_query", step.tool());
        assertEquals("Let me check.", step.note());
        assertEquals("ok", step.status());
        assertEquals(1, step.rowCount());
        assertNull(step.error());

        ToolExecutionResultMessage result = model.toolResultSentInCall(2);
        assertEquals("t1", result.id());
        assertEquals("run_query", result.toolName());
        assertTrue(result.text().contains("Ann") && result.text().contains("France"), "the model gets the full rows: " + result.text());
    }

    @Test
    void aRejectedQueryGoesBackAsAnErrorAndTheModelCorrectsIt() {
        script(toolCall(null, "t1", "run_query", runQueryArguments("DELETE FROM customer")),
                toolCall(null, "t2", "run_query", runQueryArguments("SELECT name FROM customer")),
                answer("Done."));
        when(executor.execute(eq(1L), anyString())).thenReturn(List.of(Map.of("name", "Ann")));

        CompanyAgentResponse response = service.ask(1L, "q");

        assertEquals(List.of("rejected", "ok"), response.steps().stream().map(s -> s.status()).toList());
        assertEquals("DELETE FROM customer", response.steps().getFirst().sql());
        assertNotNull(response.steps().getFirst().error());
        verify(executor, times(1)).execute(eq(1L), anyString());   // the DELETE never reached the database

        assertTrue(model.toolResultSentInCall(2).text().contains("SELECT"));
        assertEquals("SELECT name FROM customer", response.sql());
    }

    @Test
    void aDatabaseFailureIsReportedBackAsFailed() {
        script(toolCall(null, "t1", "run_query", runQueryArguments("SELECT nope FROM customer")),
                answer("Sorry, that column does not exist."));
        when(executor.execute(eq(1L), anyString())).thenThrow(new GeneratedQueryException("The query failed to run: column nope"));

        CompanyAgentResponse response = service.ask(1L, "q");

        assertEquals("failed", response.steps().getFirst().status());
        assertNull(response.sql());
        assertTrue(model.toolResultSentInCall(2).text().contains("column nope"));
    }

    @Test
    void unknownToolAndMissingSqlAreRejected() {
        script(toolCall(null, "t1", "drop_everything", "{}"),
                toolCall(null, "t2", "run_query", "{}"),
                answer("ok"));

        CompanyAgentResponse response = service.ask(1L, "q");

        assertEquals(List.of("rejected", "rejected"), response.steps().stream().map(s -> s.status()).toList());
        assertEquals("drop_everything", response.steps().get(0).tool());
        assertTrue(response.steps().get(0).error().contains("Unknown tool"));
        assertTrue(response.steps().get(1).error().contains("missing"));
        verifyNoInteractions(executor);
    }

    @Test
    void argumentsThatCannotBeReadGoBackToTheModelAsARejection() {
        script(toolCall(null, "t1", "draft_emails", "{\"drafts\": \"not a list\"}"),
                answer("Let me try again."));

        CompanyAgentResponse response = service.ask(1L, "Greet everyone");

        assertEquals("rejected", response.steps().getFirst().status());
        assertFalse(model.toolResultSentInCall(2).text().isBlank());
        assertEquals("Let me try again.", response.answer());
        verifyNoInteractions(emailBatchService);
    }

    @Test
    void aRoundWithTwoToolCallsGivesTwoStepsAndTheNoteBelongsToTheFirst() {
        script(toolCalls("Two lookups.",
                        request("t1", "run_query", runQueryArguments("SELECT name FROM customer")),
                        request("t2", "run_query", runQueryArguments("SELECT country FROM customer"))),
                answer("Both done."));
        when(executor.execute(eq(1L), anyString())).thenReturn(List.of(Map.of("name", "Ann")));

        CompanyAgentResponse response = service.ask(1L, "q");

        assertEquals(List.of(1, 1), response.steps().stream().map(s -> s.round()).toList());
        assertEquals("Two lookups.", response.steps().get(0).note());
        assertNull(response.steps().get(1).note());
    }

    @Test
    void stopsWhenTheModelKeepsCallingToolsPastTheStepLimit() {
        scriptWithLimits(3, 20000, toolCall(null, "t", "run_query", runQueryArguments("SELECT name FROM customer")));
        when(executor.execute(eq(1L), anyString())).thenReturn(List.of());

        var ex = assertThrows(GeneratedQueryException.class, () -> service.ask(1L, "q"));

        assertTrue(ex.getMessage().contains("3"));
        assertTrue(model.requests().size() <= 4, "the loop must stop at the limit, saw " + model.requests().size() + " calls");
        verify(sessions, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anAnswerWithinTheStepLimitIsAccepted() {
        scriptWithLimits(3, 20000,
                toolCall(null, "t1", "run_query", runQueryArguments("SELECT name FROM customer")),
                toolCall(null, "t2", "run_query", runQueryArguments("SELECT name FROM customer")),
                answer("Third call answers."));
        when(executor.execute(eq(1L), anyString())).thenReturn(List.of());

        assertEquals("Third call answers.", service.ask(1L, "q").answer());
    }

    @Test
    void abnormalFinishReasonOrEmptyAnswerIsABedrockError() {
        script(cutOff("cut off"));
        assertThrows(BedrockService.BedrockException.class, () -> service.ask(1L, "q"));

        script(answer("  "));
        assertThrows(BedrockService.BedrockException.class, () -> service.ask(1L, "q"));
    }

    @Test
    void aModelFailureIsABedrockErrorAndStoresNothingInTheSession() {
        script(new RateLimitException("slow down"));

        assertThrows(BedrockService.BedrockException.class, () -> service.ask(1L, "Hi"));

        verify(sessions, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anUnexpectedToolFailureStopsTheRequestAndIsNotShownToTheModel() {
        script(toolCall(null, "t1", "run_query", runQueryArguments("SELECT name FROM customer")),
                answer("never reached"));
        when(executor.execute(eq(1L), anyString())).thenThrow(new IllegalStateException("db password is hunter2"));

        var ex = assertThrows(RuntimeException.class, () -> service.ask(1L, "q"));

        assertFalse(ex instanceof BedrockService.BedrockException);
        assertEquals(1, model.requests().size(), "the model must not see the failure");
        verify(sessions, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void unknownCompanyIs404AndTheModelIsNotCalled() {
        script(answer("never reached"));
        when(repository.existsByCompanyId(9L)).thenReturn(false);

        var ex = assertThrows(ResponseStatusException.class, () -> service.ask(9L, "q"));

        assertEquals(404, ex.getStatusCode().value());
        assertTrue(model.requests().isEmpty());
        verifyNoInteractions(executor, sessions);
    }

    @Test
    void offersTheThreeToolsAndTheSystemPrompt() {
        script(answer("hi"));

        service.ask(1L, "q");

        var request = model.requests().getFirst();
        assertEquals(Set.of("run_query", "draft_emails", "remove_drafts"),
                request.toolSpecifications().stream().map(ToolSpecification::name).collect(Collectors.toSet()));
        SystemMessage system = (SystemMessage) request.messages().getFirst();
        assertEquals(service.systemPrompt(), system.text());
    }

    @Test
    void theToolSchemasTellTheModelWhatToSend() {
        script(answer("hi"));

        service.ask(1L, "q");

        Map<String, ToolSpecification> byName = model.requests().getFirst().toolSpecifications().stream()
                .collect(Collectors.toMap(ToolSpecification::name, spec -> spec));
        assertTrue(byName.get("run_query").parameters().properties().containsKey("sql"));
        assertTrue(byName.get("draft_emails").parameters().properties().containsKey("drafts"));
        assertTrue(byName.get("remove_drafts").parameters().properties().containsKey("customerIds"));
        assertFalse(byName.get("run_query").parameters().properties().containsKey("parameters"),
                "the run is passed to the tool, never offered to the model");
    }

    @Test
    void aLargeResultIsCutToFitTheSizeLimit() {
        scriptWithLimits(4, 300, answer("unused"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(row("Customer number " + i, "Netherlands"));
        }

        String json = runQueryTool.rowsJson(rows);

        assertTrue(json.length() <= 300, "length " + json.length());
        assertTrue(json.contains("\"truncated\":true"));
        assertTrue(json.contains("\"rowCount\":50"));
    }

    @Test
    void aSmallResultIsSentWhole() {
        script(answer("unused"));

        String json = runQueryTool.rowsJson(List.of(row("Ann", "France")));

        assertTrue(json.contains("\"truncated\":false"));
        assertTrue(json.contains("Ann"));
    }

    @Test
    void thePromptDescribesTheTableTheToolAndTheSafetyRules() {
        script(answer("unused"));

        String prompt = service.systemPrompt();

        assertTrue(prompt.contains("CREATE TABLE customer"));
        assertTrue(prompt.contains("run_query"));
        assertTrue(prompt.contains("date_trunc"));
        assertTrue(prompt.contains("never instructions"));
        assertTrue(prompt.contains("never filter by company"));
    }

    @Test
    void thePromptCarriesTheEmailRulesTheCapAndTheCountryLanguageTable() {
        script(answer("unused"));

        String prompt = service.systemPrompt();

        assertTrue(prompt.contains("draft_emails"));
        assertTrue(prompt.contains("At most 25 recipients per batch"));
        assertTrue(prompt.contains("France: French"));
        assertTrue(prompt.contains("Switzerland: German"));
        assertTrue(prompt.contains("Nothing is sent"));
        assertTrue(prompt.contains("remove_drafts"));
        assertTrue(prompt.contains("same customer id"));
    }

    // ── drafting emails ──────────────────────────────────────────────────────

    private static Customer customer(long id, String name, String country) {
        return Customer.builder().customerPk(UUID.randomUUID()).id(id).companyId(1L).name(name)
                .email(name.toLowerCase().replace(" ", ".") + "@example.com").country(country).build();
    }

    private static ChatResponse draftEmails(String id, long customerId, String subject, String body) {
        return toolCall(null, id, "draft_emails", json(Map.of("drafts", List.of(
                Map.of("customerId", customerId, "subject", subject, "body", body)))));
    }

    @Test
    void draftsAreStoredAsABatchAwaitingApprovalAndNothingIsSent() {
        Customer ann = customer(7, "Ann Dupont", "France");
        when(repository.findByCompanyIdAndIdIn(eq(1L), any())).thenReturn(List.of(ann));
        script(draftEmails("t1", 7, "Joyeux anniversaire", "Chère Ann, ..."),
                answer("One draft is ready for review."));
        EmailBatchResponse batch = new EmailBatchResponse(UUID.randomUUID(), 1L, "DRAFTED", null, null, 1, 0, List.of());
        when(emailBatchService.createBatch(eq(1L), eq("Greet Ann"), any())).thenReturn(batch);

        CompanyAgentResponse response = service.ask(1L, "Greet Ann");

        assertSame(batch, response.emailBatch());
        assertEquals("draft_emails", response.steps().getFirst().tool());
        assertEquals("ok", response.steps().getFirst().status());
        assertEquals(1, response.steps().getFirst().rowCount());

        ArgumentCaptor<Collection<PendingDraft>> stored = ArgumentCaptor.forClass(Collection.class);
        verify(emailBatchService).createBatch(eq(1L), eq("Greet Ann"), stored.capture());
        PendingDraft draft = stored.getValue().iterator().next();
        assertEquals(ann.getCustomerPk(), draft.customerPk());
        assertEquals("French", draft.language());
        assertEquals("Joyeux anniversaire", draft.subject());
        verify(emailBatchService, never()).approve(any(), any());
    }

    @Test
    void aCustomerIdSentAsTextIsStillUnderstood() {
        when(repository.findByCompanyIdAndIdIn(eq(1L), any())).thenReturn(List.of(customer(7, "Ann Dupont", "France")));
        script(toolCall(null, "t1", "draft_emails",
                        "{\"drafts\":[{\"customerId\":\"7\",\"subject\":\"Hi\",\"body\":\"Hello\"}]}"),
                answer("Drafted."));
        when(emailBatchService.createBatch(eq(1L), anyString(), any())).thenReturn(
                new EmailBatchResponse(UUID.randomUUID(), 1L, "DRAFTED", null, null, 1, 0, List.of()));

        CompanyAgentResponse response = service.ask(1L, "Greet Ann");

        assertEquals("ok", response.steps().getFirst().status());
    }

    @Test
    void aRejectedDraftGoesBackToTheModelAndNoBatchIsStoredWhenNothingWasAccepted() {
        when(repository.findByCompanyIdAndIdIn(eq(1L), any())).thenReturn(List.of());
        script(draftEmails("t1", 99, "Hi", "Hello"),
                answer("I could not find that customer."));

        CompanyAgentResponse response = service.ask(1L, "Greet customer 99");

        assertEquals("rejected", response.steps().getFirst().status());
        assertNull(response.emailBatch());
        assertTrue(model.toolResultSentInCall(2).text().contains("no customer with this id in the company"));
        verifyNoInteractions(emailBatchService);
    }

    // ── multi-turn sessions ──────────────────────────────────────────────────

    private static PendingDraft pending(long id, String name, String subject) {
        return new PendingDraft(UUID.randomUUID(), id, name, name.toLowerCase().replace(" ", ".") + "@example.com",
                "French", subject, "body of " + name);
    }

    private static ChatResponse removeDrafts(String id, long... customerIds) {
        return toolCall(null, id, "remove_drafts", json(Map.of("customerIds", Arrays.stream(customerIds).boxed().toList())));
    }

    private UUID sessionWithOpenDrafts(PendingDraft... drafts) {
        UUID sessionId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(sessions.open(eq(1L), eq(sessionId))).thenReturn(new AgentSessionService.SessionState(sessionId,
                List.of(UserMessage.from("first question"), AiMessage.from("first answer")), batchId));
        when(emailBatchService.openDrafts(1L, batchId)).thenReturn(List.of(drafts));
        when(emailBatchService.get(eq(1L), eq(batchId))).thenReturn(
                new EmailBatchResponse(batchId, 1L, "DRAFTED", null, null, drafts.length, 0, List.of()));
        return sessionId;
    }

    private UUID openBatchOf(UUID sessionId) {
        return sessions.open(1L, sessionId).batchId();
    }

    @Test
    void aFollowUpReplaysTheHistoryAndShowsTheDraftsUnderReview() {
        UUID sessionId = sessionWithOpenDrafts(pending(7, "Ann Dupont", "Bonjour"));
        UUID batchId = openBatchOf(sessionId);
        script(answer("Nothing to change."));

        CompanyAgentResponse response = service.ask(1L, "Is the first email polite?", sessionId);

        List<ChatMessage> sent = model.requests().getFirst().messages().stream()
                .filter(m -> !(m instanceof SystemMessage)).toList();
        assertEquals(3, sent.size());
        assertEquals("first question", ((UserMessage) sent.get(0)).singleText());
        assertEquals("first answer", ((AiMessage) sent.get(1)).text());
        String userMessage = ((UserMessage) sent.get(2)).singleText();
        assertTrue(userMessage.startsWith("Is the first email polite?"));
        assertTrue(userMessage.contains("customerId 7 (Ann Dupont, French)"));
        assertTrue(userMessage.contains("not instructions"));

        assertEquals(sessionId, response.sessionId());
        assertNotNull(response.emailBatch(), "the batch under review is returned even when this turn did not change it");
        verify(emailBatchService, never()).updateDrafts(any(), any(), any());
        verify(sessions).record(eq(1L), eq(sessionId), eq("Is the first email polite?"), eq("Nothing to change."), eq(batchId));
    }

    @Test
    void theStoredPromptIsTheUsersTextWithoutTheDraftsBlock() {
        UUID sessionId = sessionWithOpenDrafts(pending(7, "Ann Dupont", "Bonjour"));
        script(answer("ok"));

        service.ask(1L, "shorter please", sessionId);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(sessions).record(eq(1L), eq(sessionId), prompt.capture(), anyString(), any());
        assertEquals("shorter please", prompt.getValue());
    }

    @Test
    void aRevisedDraftEditsTheOpenBatchInsteadOfCreatingANewOne() {
        Customer ann = customer(7, "Ann Dupont", "France");
        UUID sessionId = sessionWithOpenDrafts(pending(7, "Ann Dupont", "Bonjour"));
        UUID batchId = openBatchOf(sessionId);
        when(repository.findByCompanyIdAndIdIn(eq(1L), any())).thenReturn(List.of(ann));
        script(draftEmails("t1", 7, "Nouveau sujet", "Corps plus court"),
                answer("Shortened."));
        EmailBatchResponse edited = new EmailBatchResponse(batchId, 1L, "DRAFTED", null, null, 1, 0, List.of());
        when(emailBatchService.updateDrafts(eq(1L), eq(batchId), any())).thenReturn(edited);

        CompanyAgentResponse response = service.ask(1L, "Make it shorter", sessionId);

        assertSame(edited, response.emailBatch());
        ArgumentCaptor<Collection<PendingDraft>> stored = ArgumentCaptor.forClass(Collection.class);
        verify(emailBatchService).updateDrafts(eq(1L), eq(batchId), stored.capture());
        assertEquals("Nouveau sujet", stored.getValue().iterator().next().subject());
        verify(emailBatchService, never()).createBatch(any(), any(), any());
        verify(sessions).record(eq(1L), eq(sessionId), anyString(), eq("Shortened."), eq(batchId));
    }

    @Test
    void removingSomeDraftsKeepsTheOthersInTheSameBatch() {
        UUID sessionId = sessionWithOpenDrafts(pending(7, "Ann Dupont", "s"), pending(8, "Bob Martin", "s"));
        UUID batchId = openBatchOf(sessionId);
        script(removeDrafts("t1", 8),
                answer("Bob is out."));
        when(emailBatchService.updateDrafts(eq(1L), eq(batchId), any())).thenReturn(
                new EmailBatchResponse(batchId, 1L, "DRAFTED", null, null, 1, 0, List.of()));

        service.ask(1L, "Drop Bob", sessionId);

        ArgumentCaptor<Collection<PendingDraft>> stored = ArgumentCaptor.forClass(Collection.class);
        verify(emailBatchService).updateDrafts(eq(1L), eq(batchId), stored.capture());
        assertEquals(List.of(7L), stored.getValue().stream().map(PendingDraft::customerId).toList());
    }

    @Test
    void removingEveryDraftDiscardsTheBatchAndClearsTheSessionsPointer() {
        UUID sessionId = sessionWithOpenDrafts(pending(7, "Ann Dupont", "s"));
        UUID batchId = openBatchOf(sessionId);
        script(removeDrafts("t1", 7),
                answer("No drafts left."));

        CompanyAgentResponse response = service.ask(1L, "Drop everyone", sessionId);

        assertNull(response.emailBatch());
        verify(emailBatchService).discard(1L, batchId);
        verify(sessions).record(eq(1L), eq(sessionId), anyString(), eq("No drafts left."), isNull());
    }

    @Test
    void aNewSessionGetsAnIdAndIsStoredWithItsFirstTurn() {
        UUID fresh = UUID.randomUUID();
        when(sessions.open(1L, null)).thenReturn(new AgentSessionService.SessionState(fresh, List.of(), null));
        script(answer("Hello."));

        CompanyAgentResponse response = service.ask(1L, "Hi");

        assertEquals(fresh, response.sessionId());
        verify(sessions).record(1L, fresh, "Hi", "Hello.", null);
    }

    @Test
    void anUnknownSessionStopsBeforeTheModelIsCalled() {
        script(answer("never reached"));
        UUID unknown = UUID.randomUUID();
        when(sessions.open(1L, unknown)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "no session"));

        var ex = assertThrows(ResponseStatusException.class, () -> service.ask(1L, "Hi", unknown));

        assertEquals(404, ex.getStatusCode().value());
        assertTrue(model.requests().isEmpty());
    }
}
