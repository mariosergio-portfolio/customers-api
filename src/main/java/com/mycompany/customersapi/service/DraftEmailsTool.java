package com.mycompany.customersapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.domain.CountryLanguage;
import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lets the agent write emails for customers. It only drafts: nothing is sent, a human approves the batch
 * afterwards. The model supplies customer id, subject and body; the recipient address comes from the
 * customer record, and only customers of this company are accepted.
 */
@Component
@Slf4j
class DraftEmailsTool implements AgentTool {

    static final String NAME = "draft_emails";

    /** Drafts accepted per call, so one call's output stays within the model's token budget. */
    static final int MAX_DRAFTS_PER_CALL = 10;
    static final int MAX_SUBJECT_CHARS   = 200;
    static final int MAX_BODY_CHARS      = 5000;

    private final CustomerRepository customerRepository;
    private final ObjectMapper       objectMapper;
    private final int                maxRecipients;
    private final Tool               specification;

    DraftEmailsTool(CustomerRepository customerRepository,
                    ObjectMapper objectMapper,
                    @Value("${assistant.email.max-recipients:25}") int maxRecipients) {
        this.customerRepository = customerRepository;
        this.objectMapper = objectMapper;
        this.maxRecipients = maxRecipients;
        this.specification = buildSpecification(maxRecipients);
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
        List<Document> items = draftItems(input);
        if (items.isEmpty()) {
            return ToolResult.rejected("The drafts argument is missing or empty", null);
        }
        if (items.size() > MAX_DRAFTS_PER_CALL) {
            return ToolResult.rejected("Send at most " + MAX_DRAFTS_PER_CALL + " drafts per call and call again for the rest", null);
        }

        Set<Long> ids = items.stream().map(DraftEmailsTool::customerIdOf).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Customer> customers = ids.isEmpty() ? Map.of()
                : customerRepository.findByCompanyIdAndIdIn(run.companyId(), ids).stream()
                        .collect(Collectors.toMap(Customer::getId, Function.identity(), (a, b) -> a));

        List<Map<String, Object>> rejected = new ArrayList<>();
        int accepted = 0;
        for (Document item : items) {
            Long customerId = customerIdOf(item);
            String reason = refusal(run, item, customerId, customers);
            if (reason != null) {
                rejected.add(rejection(customerId, reason));
                continue;
            }
            Customer customer = customers.get(customerId);
            run.putDraft(customerId, new PendingDraft(customer, CountryLanguage.languageOf(customer.getCountry()),
                    cleanLine(textOf(item, "subject")), textOf(item, "body").strip()));
            accepted++;
        }

        log.info("Agent drafts: companyId={}, accepted={}, rejected={}, inBatch={}", run.companyId(), accepted, rejected.size(), run.draftCount());
        String result = json(Map.of("accepted", accepted, "rejected", rejected,
                "draftsInBatch", run.draftCount(), "maxRecipients", maxRecipients));
        return accepted == 0
                ? ToolResult.rejected("No draft was accepted: " + result, null)
                : ToolResult.ok(result, null, accepted);
    }

    /** Why this draft cannot be stored, or null if it can. A re-draft for the same customer replaces the old one. */
    private String refusal(AgentRun run, Document item, Long customerId, Map<Long, Customer> customers) {
        if (customerId == null) {
            return "customerId is missing or not a whole number";
        }
        Customer customer = customers.get(customerId);
        if (customer == null) {
            return "no customer with this id in the company";
        }
        if (customer.getEmail() == null || customer.getEmail().isBlank()) {
            return "the customer has no email address";
        }
        String subject = cleanLine(textOf(item, "subject"));
        if (subject.isEmpty() || subject.length() > MAX_SUBJECT_CHARS) {
            return "subject must be 1 to " + MAX_SUBJECT_CHARS + " characters";
        }
        String body = textOf(item, "body").strip();
        if (body.isEmpty() || body.length() > MAX_BODY_CHARS) {
            return "body must be 1 to " + MAX_BODY_CHARS + " characters";
        }
        if (!run.hasDraftFor(customerId) && run.draftCount() >= maxRecipients) {
            return "the batch is full (at most " + maxRecipients + " recipients per batch)";
        }
        return null;
    }

    private static Map<String, Object> rejection(Long customerId, String reason) {
        Map<String, Object> rejection = new LinkedHashMap<>();
        rejection.put("customerId", customerId);
        rejection.put("reason", reason);
        return rejection;
    }

    private static List<Document> draftItems(Document input) {
        if (input == null || !input.isMap()) {
            return List.of();
        }
        Document drafts = input.asMap().get("drafts");
        return drafts != null && drafts.isList()
                ? drafts.asList().stream().filter(Document::isMap).toList()
                : List.of();
    }

    private static Long customerIdOf(Document item) {
        Document id = item.asMap().get("customerId");
        if (id == null) {
            return null;
        }
        if (id.isNumber()) {
            double value = id.asNumber().doubleValue();
            return value == Math.rint(value) ? (long) value : null;
        }
        if (id.isString() && id.asString().strip().matches("\\d{1,18}")) {
            return Long.parseLong(id.asString().strip());
        }
        return null;
    }

    private static String textOf(Document item, String field) {
        Document value = item.asMap().get(field);
        return value != null && value.isString() ? value.asString() : "";
    }

    /** One line, no control characters, so a subject can never carry extra mail headers. */
    private static String cleanLine(String value) {
        return value.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize a tool result", e);
        }
    }

    private static Tool buildSpecification(int maxRecipients) {
        Document draft = Document.mapBuilder()
                .putString("type", "object")
                .putDocument("properties", Document.mapBuilder()
                        .putDocument("customerId", Document.mapBuilder()
                                .putString("type", "integer")
                                .putString("description", "The id column of the customer, as returned by run_query")
                                .build())
                        .putDocument("subject", Document.mapBuilder()
                                .putString("type", "string")
                                .putString("description", "Email subject, one line")
                                .build())
                        .putDocument("body", Document.mapBuilder()
                                .putString("type", "string")
                                .putString("description", "Email body, plain text, in the customer's language")
                                .build())
                        .build())
                .putList("required", List.of(Document.fromString("customerId"), Document.fromString("subject"),
                        Document.fromString("body")))
                .build();
        Document schema = Document.mapBuilder()
                .putString("type", "object")
                .putDocument("properties", Document.mapBuilder()
                        .putDocument("drafts", Document.mapBuilder()
                                .putString("type", "array")
                                .putDocument("items", draft)
                                .build())
                        .build())
                .putList("required", List.of(Document.fromString("drafts")))
                .build();
        return Tool.fromToolSpec(ToolSpecification.builder()
                .name(NAME)
                .description("Drafts emails for customers of this company. Nothing is sent: a person reviews the drafts and "
                        + "approves them afterwards. Give each customer's id, a subject and a body; the recipient address is added "
                        + "from the customer record. At most " + MAX_DRAFTS_PER_CALL + " drafts per call and " + maxRecipients
                        + " recipients per batch. Drafting a customer again replaces the earlier draft.")
                .inputSchema(ToolInputSchema.fromJson(schema))
                .build());
    }
}
