package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Prompt to send to the Bedrock model")
public record AskRequest(
        @Schema(description = "Optional instructions that steer the model")
        @Size(max = 4000) String systemPrompt,

        @Schema(description = "User prompt", example = "Summarize what a customers API does in one sentence.")
        @NotBlank @Size(max = 8000) String prompt) {
}
