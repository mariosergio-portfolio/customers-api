package com.mycompany.customersapi.service;

import com.mycompany.customersapi.dto.AgentStep;
import com.mycompany.customersapi.dto.CompanyAgentResponse;
import com.mycompany.customersapi.dto.EmailBatchResponse;
import com.mycompany.customersapi.repository.CustomerRepository;
import com.mycompany.customersapi.service.agent.AgentSessionService;
import com.mycompany.customersapi.service.agent.lead.AgentRun;
import com.mycompany.customersapi.service.agent.lead.LeadAgent;
import com.mycompany.customersapi.service.agent.lead.DraftBatchCoordinator;
import com.mycompany.customersapi.service.agent.lead.ToolResult;
import com.mycompany.customersapi.service.bedrock.BedrockService;
import com.mycompany.customersapi.service.email.PendingDraft;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Answers questions about a company's customers, and drafts emails to them, with a tool-use agent built on
 * LangChain4j AI Services.
 *
 * Unlike {@link com.mycompany.customersapi.service.CompanyAssistantService} (one model call that writes one query), here the model drives:
 * it calls {@code run_query} as often as it needs to read the real rows, fixes rejected or failed queries,
 * and calls {@code draft_emails} to write emails in each customer's language. A second agent, the reviewer
 * ({@link com.mycompany.customersapi.service.agent.review.DraftReviewer}), checks the drafts when the model calls
 * {@code review_drafts}; the model fixes what it flags. The loop itself lives in {@link LeadAgent}; this
 * service opens the session, calls the agent and turns the outcome into a response.
 *
 * The agent can read and draft, never send. Drafts are stored as an email batch that a person reviews and
 * approves through a separate request, so text injected through stored customer data cannot make it deliver
 * mail: at most it can add drafts, within the recipient cap, to customers of this company. Query results,
 * including names, emails and phones, are sent to the model.
 *
 * A request can continue a session: earlier prompts and answers are replayed as chat memory, and the drafts
 * under review are shown to the model so it can rewrite them ({@code draft_emails}) or drop customers
 * ({@code remove_drafts}) in the same batch.
 */
@Service
@Slf4j
public class CompanyAgenticAssistantService {

    private final CustomerRepository    customerRepository;
    private final LeadAgent leadAgent;
    private final AgentSessionService   sessions;
    private final DraftBatchCoordinator draftBatches;

    public CompanyAgenticAssistantService(CustomerRepository customerRepository,
                                          LeadAgent agent,
                                          AgentSessionService sessions,
                                          DraftBatchCoordinator draftBatches) {
        this.customerRepository = customerRepository;
        this.leadAgent = agent;
        this.sessions = sessions;
        this.draftBatches = draftBatches;
    }

    /** Starts a new session. */
    public CompanyAgentResponse ask(Long companyId, String prompt) {
        return ask(companyId, prompt, null);
    }

    /**
     * @param sessionId the session to continue, or null to start a new one
     * @throws ResponseStatusException 404 for an unknown company or session, 410 for an expired session
     */
    public CompanyAgentResponse ask(Long companyId, String prompt, UUID sessionId) {
        if (!customerRepository.existsByCompanyId(companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found: " + companyId);
        }

        AgentSessionService.SessionState session = sessions.open(companyId, sessionId);
        List<PendingDraft> openDrafts = draftBatches.openDrafts(companyId, session.batchId());
        UUID openBatchId = openDrafts.isEmpty() ? null : session.batchId();

        AgentRun run = new AgentRun(companyId, openDrafts, prompt);
        Result<String> result = leadAgent.chat(run, session.history(), prompt + draftBatches.describe(openDrafts));

        String answer = answerOf(result);
        List<AgentStep> steps = stepsOf(run, result);
        log.info("Agentic ask: companyId={}, sessionId={}, rounds={}, steps={}", companyId, session.sessionId(),
                result.intermediateResponses().size() + 1, steps.size());
        EmailBatchResponse batch = draftBatches.save(companyId, openBatchId, run, prompt);
        sessions.record(companyId, session.sessionId(), prompt, answer, batch == null ? null : batch.batchId());
        return new CompanyAgentResponse(session.sessionId(), answer, run.lastSql(), run.lastRows().size(),
                run.lastRows(), steps, batch, run.reviewSummary());
    }

    private static String answerOf(Result<String> result) {
        if (result.finishReason() != FinishReason.STOP) {
            throw new BedrockService.BedrockException("The model stopped early (" + result.finishReason() + ")", null);
        }
        String answer = result.content() == null ? "" : result.content().strip();
        if (answer.isEmpty()) {
            throw new BedrockService.BedrockException("The model returned an empty answer", null);
        }
        return answer;
    }

    /** One step per tool call, grouped by the model call (round) that asked for it. */
    private static List<AgentStep> stepsOf(AgentRun run, Result<String> result) {
        List<AgentStep> steps = new ArrayList<>();
        int round = 0;
        for (ChatResponse response : result.intermediateResponses()) {
            round++;
            AiMessage message = response.aiMessage();
            String note = message.text() == null ? "" : message.text().strip();
            for (ToolExecutionRequest request : message.toolExecutionRequests()) {
                ToolResult outcome = run.outcomeOf(request.id());
                steps.add(new AgentStep(round, note.isEmpty() ? null : note, request.name(), outcome.sql(),
                        outcome.status(), outcome.count(), outcome.error()));
                note = "";   // the note belongs to the first call of this round
            }
        }
        return List.copyOf(steps);
    }
}
