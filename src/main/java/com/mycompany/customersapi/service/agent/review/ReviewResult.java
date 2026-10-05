package com.mycompany.customersapi.service.agent.review;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/** What the reviewer model returns: one verdict per draft it was given. */
record ReviewResult(@Description("One verdict for every draft, in the same order") List<DraftVerdict> verdicts) {
}
