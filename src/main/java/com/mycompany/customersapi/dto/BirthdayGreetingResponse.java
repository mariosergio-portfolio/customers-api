package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Birthday greeting written for one customer")
public record BirthdayGreetingResponse(
        @Schema(description = "The greeting, addressed to the customer by name, in the language the model chose from the customer's country") String message,
        @Schema(description = "Tone requested from the customer's age", example = "WARM") String tone) {
}
