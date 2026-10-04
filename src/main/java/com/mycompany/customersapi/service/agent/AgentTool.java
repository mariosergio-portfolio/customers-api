package com.mycompany.customersapi.service.agent;

import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;

/**
 * One capability the company agent may use. The model chooses when to call it; the tool validates its
 * input and enforces its own limits, because nothing the model sends is trusted.
 */
interface AgentTool {

    String name();

    /** Name, description and input schema the model sees. */
    Tool specification();

    /** Never throws for bad model input: it returns a rejected or failed result the model can react to. */
    ToolResult execute(AgentRun run, Document input);
}
