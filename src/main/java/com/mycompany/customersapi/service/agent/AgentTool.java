package com.mycompany.customersapi.service.agent;

/**
 * Marks a bean whose {@code @Tool} methods the company agent may call. The model chooses when to call them;
 * each tool validates its input and enforces its own limits, because nothing the model sends is trusted.
 *
 * A tool method never throws for bad model input: it reports a rejected or failed {@link ToolResult} through
 * {@link AgentRun#report} and returns the text the model can react to. The run comes in as an
 * {@link dev.langchain4j.invocation.InvocationParameters} argument, which the model does not see.
 */
public interface AgentTool {
}
