package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Birthday greeting written for one customer")
public record BirthdayGreetingResponse(
        @Schema(description = "The greeting, addressed to the customer by name") String message,
        @Schema(description = "Language the greeting is written in, from the customer's country", example = "Portuguese") String language,
        @Schema(description = "Tone used, from the customer's age", example = "WARM") String tone) {
}
