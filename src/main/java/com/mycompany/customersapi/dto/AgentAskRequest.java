package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Request to the company agent, optionally continuing an earlier conversation")
public record AgentAskRequest(
        @Schema(description = "What to ask or do, or how to change the drafts under review",
                example = "Write a thank-you email for the 5 oldest customers, in their language")
        @NotBlank @Size(max = 8000) String prompt,

        @Schema(description = "Session returned by an earlier response, to continue that conversation and refine its "
                + "drafts. Omit to start a new one.", nullable = true)
        UUID sessionId) {
}
