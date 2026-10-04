package com.mycompany.customersapi.controller;

import com.mycompany.customersapi.config.GlobalExceptionHandler;
import com.mycompany.customersapi.dto.CompanyAgentResponse;
import com.mycompany.customersapi.dto.CompanyAskRequest;
import com.mycompany.customersapi.service.CompanyAgenticAssistantService;
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
public class CompanyAgenticAssistantController {

    private final CompanyAgenticAssistantService agenticAssistantService;

    @Operation(
            summary = "Ask a question about a company's customers (agent)",
            description = """
                    A tool-use agent answers the question: the model runs read-only SQL queries over the company's
                    customers as often as it needs, reads the rows each query returns (full customer data, including
                    names, emails and phones, is sent to the model), corrects rejected queries and writes the answer.
                    Returns the answer, the last query result and every step the agent took.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer written"),
            @ApiResponse(responseCode = "400", description = "Invalid request body",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Company has no customers",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "The agent did not finish within the step limit",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Bedrock call failed",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/companies/{companyId}/agentic-ask")
    public ResponseEntity<CompanyAgentResponse> ask(
            @Parameter(description = "Company identifier", required = true)
            @PathVariable("companyId") @NotNull Long companyId,
            @Valid @RequestBody CompanyAskRequest request) {
        return ResponseEntity.ok(agenticAssistantService.ask(companyId, request.prompt()));
    }
}
