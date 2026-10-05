package com.mycompany.customersapi.service.agent.lead;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunTest {

    private AgentRun run;

    @BeforeEach
    void setUp() {
        run = new AgentRun(1L);
    }

    private static ToolExecution executed(String callId, String resultText) {
        return ToolExecution.builder()
                .request(ToolExecutionRequest.builder().id(callId).name("tool").arguments("{}").build())
                .result(resultText)
                .invocationContext(InvocationContext.builder().build())
                .build();
    }

    @Test
    void should_give_a_completed_call_the_outcome_its_tool_reported() {
        ToolResult reported = ToolResult.ok("rows", "SELECT 1", 3);
        run.report(reported);

        run.completeCall(executed("c1", "rows"));

        assertSame(reported, run.outcomeOf("c1"));
    }

    @Test
    void should_report_a_call_that_never_reached_a_tool_as_rejected_with_the_text_the_model_got() {
        run.completeCall(executed("c1", "Unknown tool: nope"));

        assertEquals("rejected", run.outcomeOf("c1").status());
        assertEquals("Unknown tool: nope", run.outcomeOf("c1").error());
    }

    @Test
    void should_not_let_a_call_that_never_reached_a_tool_take_another_calls_outcome() {
        ToolResult reported = ToolResult.ok("rows", "SELECT 1", 3);
        run.report(reported);

        run.completeCall(executed("c1", "Unknown tool: nope"));
        run.completeCall(executed("c2", "rows"));

        assertEquals("rejected", run.outcomeOf("c1").status());
        assertSame(reported, run.outcomeOf("c2"));
    }

    @Test
    void should_skip_an_outcome_left_behind_by_a_call_that_did_not_complete() {
        run.report(ToolResult.ok("left behind", null, 1));
        ToolResult current = ToolResult.ok("current", null, 2);
        run.report(current);

        run.completeCall(executed("c1", "current"));

        assertSame(current, run.outcomeOf("c1"));
    }

    @Test
    void should_pair_calls_with_equal_text_in_the_order_they_were_reported() {
        ToolResult first = ToolResult.rejected("The sql argument is missing", null);
        ToolResult second = ToolResult.rejected("The sql argument is missing", null);
        run.report(first);
        run.report(second);

        run.completeCall(executed("c1", "The sql argument is missing"));
        run.completeCall(executed("c2", "The sql argument is missing"));

        assertSame(first, run.outcomeOf("c1"));
        assertSame(second, run.outcomeOf("c2"));
    }

    @Test
    void should_treat_a_call_that_never_completed_as_rejected() {
        assertEquals("rejected", run.outcomeOf("missing").status());
    }
}
