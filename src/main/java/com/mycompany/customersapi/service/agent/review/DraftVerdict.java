package com.mycompany.customersapi.service.agent.review;

import dev.langchain4j.model.output.structured.Description;

/** The reviewer's judgment of one draft. */
public record DraftVerdict(
        @Description("The customerId of the draft, exactly as given") Long customerId,
        @Description("true when the draft has no problem; false when it must be fixed") boolean approved,
        @Description("When approved is false: one short sentence in English saying what to fix; otherwise null") String issue) {
}
