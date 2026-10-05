package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "What the reviewer agent found in the drafts this request produced. Advice only: a person still approves the batch.")
public record DraftReviewSummary(
        @Schema(description = "Times the reviewer was called in this request; 0 means the drafts were not reviewed") int reviews,
        @Schema(description = "True when every draft in the batch was reviewed in its current form") boolean allDraftsReviewed,
        @Schema(description = "Problems the reviewer found that are still open; empty when none") List<Issue> openIssues) {

    @Schema(description = "One problem the reviewer found in a draft")
    public record Issue(
            @Schema(description = "Id of the customer whose draft has the problem") Long customerId,
            @Schema(description = "What the reviewer says to fix") String issue) {
    }
}
