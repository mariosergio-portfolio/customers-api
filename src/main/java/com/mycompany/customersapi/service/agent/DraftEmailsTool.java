package com.mycompany.customersapi.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.customersapi.domain.CountryLanguage;
import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.repository.CustomerRepository;
import com.mycompany.customersapi.service.email.PendingDraft;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.output.structured.Description;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    /** One email the model wants to draft. Fields may be null: the model's output is checked, not trusted. */
    record DraftItem(
            @Description("The id column of the customer, as returned by run_query") Long customerId,
            @Description("Email subject, one line") String subject,
            @Description("Email body, plain text, in the customer's language") String body) {
    }

    private final CustomerRepository customerRepository;
    private final ObjectMapper       objectMapper;
    private final int                maxRecipients;

    DraftEmailsTool(CustomerRepository customerRepository,
                    ObjectMapper objectMapper,
                    @Value("${assistant.email.max-recipients:25}") int maxRecipients) {
        this.customerRepository = customerRepository;
        this.objectMapper = objectMapper;
        this.maxRecipients = maxRecipients;
    }

    @Tool(name = NAME, value = "Drafts emails for customers of this company. Nothing is sent: a person reviews the drafts and "
            + "approves them afterwards. Give each customer's id, a subject and a body; the recipient address is added "
            + "from the customer record. At most " + MAX_DRAFTS_PER_CALL + " drafts per call, and the batch has a cap. "
            + "Drafting a customer again replaces the earlier draft.")
    String draftEmails(@P("The emails to draft") List<DraftItem> drafts, InvocationParameters parameters) {
        AgentRun run = AgentRun.from(parameters);
        return run.report(execute(run, drafts));
    }

    ToolResult execute(AgentRun run, List<DraftItem> items) {
        if (items == null || items.isEmpty()) {
            return ToolResult.rejected("The drafts argument is missing or empty", null);
        }
        if (items.size() > MAX_DRAFTS_PER_CALL) {
            return ToolResult.rejected("Send at most " + MAX_DRAFTS_PER_CALL + " drafts per call and call again for the rest", null);
        }

        Set<Long> ids = items.stream().map(DraftItem::customerId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Customer> customers = ids.isEmpty() ? Map.of()
                : customerRepository.findByCompanyIdAndIdIn(run.companyId(), ids).stream()
                        .collect(Collectors.toMap(Customer::getId, Function.identity(), (a, b) -> a));

        List<Map<String, Object>> rejected = new ArrayList<>();
        int accepted = 0;
        for (DraftItem item : items) {
            String reason = refusal(run, item, customers);
            if (reason != null) {
                rejected.add(rejection(item.customerId(), reason));
                continue;
            }
            Customer customer = customers.get(item.customerId());
            run.putDraft(new PendingDraft(customer.getCustomerPk(), customer.getId(), customer.getName(), customer.getEmail(),
                    CountryLanguage.languageOf(customer.getCountry()), cleanLine(item.subject()), item.body().strip()));
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
    private String refusal(AgentRun run, DraftItem item, Map<Long, Customer> customers) {
        Long customerId = item.customerId();
        if (customerId == null) {
            return "customerId is missing";
        }
        Customer customer = customers.get(customerId);
        if (customer == null) {
            return "no customer with this id in the company";
        }
        if (customer.getEmail() == null || customer.getEmail().isBlank()) {
            return "the customer has no email address";
        }
        String subject = cleanLine(item.subject());
        if (subject.isEmpty() || subject.length() > MAX_SUBJECT_CHARS) {
            return "subject must be 1 to " + MAX_SUBJECT_CHARS + " characters";
        }
        String body = item.body() == null ? "" : item.body().strip();
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

    /** One line, no control characters, so a subject can never carry extra mail headers. */
    private static String cleanLine(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize a tool result", e);
        }
    }
}
