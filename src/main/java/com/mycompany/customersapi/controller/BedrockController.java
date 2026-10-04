package com.mycompany.customersapi.controller;

import com.mycompany.customersapi.config.GlobalExceptionHandler;
import com.mycompany.customersapi.dto.AskRequest;
import com.mycompany.customersapi.dto.AskResponse;
import com.mycompany.customersapi.service.BedrockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "General AI Assistant", description = "Send prompts to a foundation model on AWS Bedrock")
@RestController
@RequestMapping("/api/bedrock")
@RequiredArgsConstructor
@Validated
@Slf4j
public class BedrockController {

    private final BedrockService bedrockService;

    @Operation(
            summary = "Ask the model",
            description = "Sends the prompt (and optional system prompt) to the configured Bedrock model "
                    + "through the Converse API and returns the text reply."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Model replied"),
            @ApiResponse(responseCode = "400", description = "Invalid request body",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Bedrock call failed",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        log.debug("Bedrock ask: promptLength={}", request.prompt().length());
        return ResponseEntity.ok(new AskResponse(
                bedrockService.ask(request.systemPrompt(), request.prompt())));
    }
}
