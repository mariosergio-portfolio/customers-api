package com.mycompany.customersapi.service.agent.lead;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.service.Result;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The lead agent (agent 1) as the service uses it: give it a run, the session history and the user's message,
 * get the model's final answer back. LangChain4j runs the loop (model call, tool calls, model call, ...) inside
 * {@link #chat}; {@link LeadAgentFactory} sets it up and {@link AgentFailureMapper} translates its failures.
 */
@Component
public class LeadAgent {

    private final LeadAgentFactory factory;

    public LeadAgent(LeadAgentFactory factory) {
        this.factory = factory;
    }

    /** Runs the agent loop for one request. The tools read and write {@code run}; the result carries the answer. */
    public Result<String> chat(AgentRun run, List<ChatMessage> history, String message) {
        LeadAssistant assistant = factory.create(run, history);
        try {
            return assistant.chat(message, run.asParameters());
        } catch (RuntimeException e) {
            throw AgentFailureMapper.map(e, run, factory.maxSteps());
        }
    }

    public String systemPrompt() {
        return factory.systemPrompt();
    }
}
