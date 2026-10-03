package com.mycompany.customersapi.controller;

import com.mycompany.customersapi.config.GlobalExceptionHandler;
import com.mycompany.customersapi.dto.AskResponse;
import com.mycompany.customersapi.dto.CompanyAskRequest;
import com.mycompany.customersapi.service.CompanyAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Bedrock", description = "Send prompts to a foundation model on AWS Bedrock")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class CompanyAssistantController {

    private final CompanyAssistantService companyAssistantService;

    @Operation(
            summary = "Ask the model about a company",
            description = "Sends the prompt to the Bedrock model together with the company's customers "
                    + "(id, name, email, age, country, phone) as context, and returns the model's reply."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Model replied"),
            @ApiResponse(responseCode = "400", description = "Invalid request body",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Company has no customers",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Bedrock call failed",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/companies/{companyId}/ask")
    public ResponseEntity<AskResponse> ask(
            @Parameter(description = "Company identifier", required = true)
            @PathVariable("companyId") @NotNull Long companyId,
            @Valid @RequestBody CompanyAskRequest request) {
        return ResponseEntity.ok(new AskResponse(companyAssistantService.ask(companyId, request.prompt())));
    }
}
