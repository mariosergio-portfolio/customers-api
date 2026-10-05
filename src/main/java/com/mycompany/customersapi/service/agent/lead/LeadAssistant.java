package com.mycompany.customersapi.service.agent.lead;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** The AI Service LangChain4j implements for the lead agent: one user message in; the answer and the tool calls out. */
public interface LeadAssistant {

    /**
     * The message goes in as a template variable: its value is never read as a template, so braces in a prompt
     * or in a customer name are harmless. The parameters carry the {@link AgentRun} to the tools.
     */
    @UserMessage("{{message}}")
    Result<String> chat(@V("message") String message, InvocationParameters parameters);
}
