package com.mycompany.customersapi.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Lets the agent drop customers from the batch of drafts the user is reviewing. Nothing else is touched. */
@Component
@Slf4j
public class RemoveDraftsTool implements AgentTool {

    public static final String NAME = "remove_drafts";

    private static final int MAX_IDS_PER_CALL = 100;

    private final ObjectMapper objectMapper;

    public RemoveDraftsTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool(name = NAME, value = "Removes the drafts of the given customers from the batch under review. Use it when the user wants "
            + "some customers dropped. Customers without a draft are reported and ignored.")
    String removeDrafts(@P("Ids of the customers whose drafts to remove") List<Long> customerIds,
                        InvocationParameters parameters) {
        AgentRun run = AgentRun.from(parameters);
        return run.report(execute(run, customerIds));
    }

    ToolResult execute(AgentRun run, List<Long> customerIds) {
        List<Long> ids = customerIds == null ? List.of() : customerIds.stream().filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return ToolResult.rejected("The customerIds argument is missing or has no valid id", null);
        }
        if (ids.size() > MAX_IDS_PER_CALL) {
            return ToolResult.rejected("At most " + MAX_IDS_PER_CALL + " ids per call", null);
        }

        List<Long> removed = new ArrayList<>();
        List<Long> notInBatch = new ArrayList<>();
        for (Long id : ids) {
            (run.removeDraft(id) ? removed : notInBatch).add(id);
        }

        log.info("Agent removed drafts: companyId={}, removed={}, notInBatch={}", run.companyId(), removed, notInBatch);
        String result = json(Map.of("removed", removed, "notInBatch", notInBatch, "draftsInBatch", run.draftCount()));
        return removed.isEmpty()
                ? ToolResult.rejected("No draft was removed: " + result, null)
                : ToolResult.ok(result, null, removed.size());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize a tool result", e);
        }
    }
}
