package com.mycompany.customersapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Model reply")
public record AskResponse(String answer) {
}
