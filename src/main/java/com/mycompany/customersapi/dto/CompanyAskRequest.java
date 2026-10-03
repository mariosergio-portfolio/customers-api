package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Question about a company and its customers")
public record CompanyAskRequest(
        @Schema(description = "Prompt about the company", example = "Which countries have the most customers?")
        @NotBlank @Size(max = 8000) String prompt) {
}
