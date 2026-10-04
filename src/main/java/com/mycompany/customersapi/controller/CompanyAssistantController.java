package com.mycompany.customersapi.controller;

import com.mycompany.customersapi.config.GlobalExceptionHandler;
import com.mycompany.customersapi.dto.CompanyAskRequest;
import com.mycompany.customersapi.dto.CompanyQueryResponse;
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

@Tag(name = "Company AI Assistant", description = "Send prompts to a foundation model on AWS Bedrock")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class CompanyAssistantController {

    private final CompanyAssistantService companyAssistantService;

    @Operation(
            summary = "Ask a question about a company's customers",
            description = """
                    The model receives only the table structure and the prompt, and replies with a SQL query and
                    a short human-readable message. The service validates the query, runs it read-only scoped to
                    the company, and returns the message, the SQL and the rows. No customer data is sent to the model.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Query executed"),
            @ApiResponse(responseCode = "400", description = "Invalid request body",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Company has no customers",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "The model's query was unusable or rejected",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Bedrock call failed",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/companies/{companyId}/ask")
    public ResponseEntity<CompanyQueryResponse> ask(
            @Parameter(description = "Company identifier", required = true)
            @PathVariable("companyId") @NotNull Long companyId,
            @Valid @RequestBody CompanyAskRequest request) {
        return ResponseEntity.ok(companyAssistantService.ask(companyId, request.prompt()));
    }
}
