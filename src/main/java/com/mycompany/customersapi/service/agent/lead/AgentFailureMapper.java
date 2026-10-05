package com.mycompany.customersapi.service.agent.lead;

import com.mycompany.customersapi.service.bedrock.BedrockService;
import com.mycompany.customersapi.service.query.GeneratedQueryException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns what can go wrong inside the LangChain4j loop into the exceptions the rest of the API already maps to HTTP
 * statuses. This is the only place that knows how LangChain4j reports each failure.
 *
 * <ul>
 *   <li>a tool failed unexpectedly: rethrown as is, it is a bug and must not be hidden;</li>
 *   <li>the model call failed: {@link BedrockService.BedrockException};</li>
 *   <li>the model kept calling tools past the limit: {@link GeneratedQueryException};</li>
 *   <li>anything else: rethrown as is.</li>
 * </ul>
 */
@Slf4j
final class AgentFailureMapper {

    /**
     * LangChain4j has no exception type for an exhausted round-trip limit: it throws a plain RuntimeException
     * whose message names this setting. A test runs the real library into its limit, so a library upgrade that
     * rewords the message fails the build instead of turning the limit into a 500.
     */
    static final String STEP_LIMIT_MARKER = "maxToolCallingRoundTrips";

    private AgentFailureMapper() {
    }

    static RuntimeException map(RuntimeException e, AgentRun run, int maxSteps) {
        if (e instanceof ToolExecutionException || e instanceof ToolArgumentsException) {
            return e;
        }
        if (e instanceof LangChain4jException) {
            log.error("AWS Bedrock error: {}", e.getMessage());
            return new BedrockService.BedrockException("Bedrock invocation failed: " + e.getMessage(), e);
        }
        if (isStepLimit(e)) {
            log.warn("Agentic ask did not finish: companyId={}, maxSteps={}", run.companyId(), maxSteps);
            return new GeneratedQueryException("The assistant did not finish within " + maxSteps + " model calls");
        }
        return e;
    }

    static boolean isStepLimit(RuntimeException e) {
        return e.getClass() == RuntimeException.class
                && e.getMessage() != null && e.getMessage().contains(STEP_LIMIT_MARKER);
    }
}
