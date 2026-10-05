package com.mycompany.customersapi.service.agent.lead;

import com.mycompany.customersapi.service.agent.ScriptedChatModel;
import com.mycompany.customersapi.service.bedrock.BedrockService;
import com.mycompany.customersapi.service.query.GeneratedQueryException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import static com.mycompany.customersapi.service.agent.ScriptedChatModel.toolCall;
import static org.junit.jupiter.api.Assertions.*;

class AgentFailureMapperTest {

    private final AgentRun run = new AgentRun(1L);

    @Test
    void should_map_a_model_failure_to_a_bedrock_exception() {
        RuntimeException mapped = AgentFailureMapper.map(new RateLimitException("slow down"), run, 5);

        assertInstanceOf(BedrockService.BedrockException.class, mapped);
        assertTrue(mapped.getMessage().contains("slow down"));
    }

    @Test
    void should_map_the_step_limit_to_a_generated_query_exception_that_names_the_limit() {
        RuntimeException mapped = AgentFailureMapper.map(
                new RuntimeException("Something is wrong, exceeded 5 " + AgentFailureMapper.STEP_LIMIT_MARKER), run, 5);

        assertInstanceOf(GeneratedQueryException.class, mapped);
        assertTrue(mapped.getMessage().contains("5"));
    }

    @Test
    void should_leave_tool_failures_and_other_bugs_unchanged() {
        RuntimeException toolFailure = new ToolExecutionException(new IllegalStateException("bug"));
        RuntimeException badArguments = new ToolArgumentsException(new IllegalArgumentException("bad"));
        RuntimeException other = new IllegalStateException("boom");

        assertSame(toolFailure, AgentFailureMapper.map(toolFailure, run, 5));
        assertSame(badArguments, AgentFailureMapper.map(badArguments, run, 5));
        assertSame(other, AgentFailureMapper.map(other, run, 5));
    }

    @Test
    void should_not_take_a_subclass_that_mentions_the_setting_for_the_step_limit() {
        IllegalStateException other = new IllegalStateException(AgentFailureMapper.STEP_LIMIT_MARKER);

        assertSame(other, AgentFailureMapper.map(other, run, 5));
    }

    // ── guard against a LangChain4j upgrade ──────────────────────────────────

    interface Chat {
        String chat(String message);
    }

    static class PingTool {
        @Tool("Does nothing")
        String ping() {
            return "pong";
        }
    }

    /** Runs the real library into its round-trip limit: if its message changes, this fails instead of production. */
    @Test
    void should_recognise_the_real_exception_langchain4j_throws_at_the_round_trip_limit() {
        Chat chat = AiServices.builder(Chat.class)
                .chatModel(new ScriptedChatModel(toolCall(null, "t1", "ping", "{}")))
                .tools(new PingTool())
                .maxToolCallingRoundTrips(2)
                .build();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> chat.chat("go"));

        assertTrue(AgentFailureMapper.isStepLimit(thrown), "LangChain4j reported the limit differently: " + thrown);
        assertInstanceOf(GeneratedQueryException.class, AgentFailureMapper.map(thrown, run, 2));
    }
}
