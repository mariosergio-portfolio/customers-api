package com.mycompany.customersapi.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lets the agent drop customers from the batch of drafts the user is reviewing. Nothing else is touched. */
@Component
@Slf4j
class RemoveDraftsTool implements AgentTool {

    static final String NAME = "remove_drafts";

    private static final int MAX_IDS_PER_CALL = 100;

    private final ObjectMapper objectMapper;
    private final Tool specification;

    RemoveDraftsTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.specification = buildSpecification();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Tool specification() {
        return specification;
    }

    @Override
    public ToolResult execute(AgentRun run, Document input) {
        List<Long> ids = customerIds(input);
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

    private static List<Long> customerIds(Document input) {
        if (input == null || !input.isMap()) {
            return List.of();
        }
        Document ids = input.asMap().get("customerIds");
        if (ids == null || !ids.isList()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Document id : ids.asList()) {
            if (id.isNumber() && id.asNumber().doubleValue() == Math.rint(id.asNumber().doubleValue())) {
                result.add(id.asNumber().longValue());
            } else if (id.isString() && id.asString().strip().matches("\\d{1,18}")) {
                result.add(Long.parseLong(id.asString().strip()));
            }
        }
        return result;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize a tool result", e);
        }
    }

    private static Tool buildSpecification() {
        Document schema = Document.mapBuilder()
                .putString("type", "object")
                .putDocument("properties", Document.mapBuilder()
                        .putDocument("customerIds", Document.mapBuilder()
                                .putString("type", "array")
                                .putDocument("items", Document.mapBuilder().putString("type", "integer").build())
                                .putString("description", "Ids of the customers whose drafts to remove")
                                .build())
                        .build())
                .putList("required", List.of(Document.fromString("customerIds")))
                .build();
        return Tool.fromToolSpec(ToolSpecification.builder()
                .name(NAME)
                .description("Removes the drafts of the given customers from the batch under review. Use it when the user wants "
                        + "some customers dropped. Customers without a draft are reported and ignored.")
                .inputSchema(ToolInputSchema.fromJson(schema))
                .build());
    }
}
