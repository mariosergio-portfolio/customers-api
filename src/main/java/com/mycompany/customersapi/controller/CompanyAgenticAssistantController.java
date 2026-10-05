package com.mycompany.customersapi.controller;

import com.mycompany.customersapi.config.GlobalExceptionHandler;
import com.mycompany.customersapi.dto.AgentAskRequest;
import com.mycompany.customersapi.dto.CompanyAgentResponse;
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
                    When the prompt asks for emails (for example "write a greeting for the 20 oldest customers, in the
                    language of their country"), the agent also drafts one email per customer in the main language of
                    the customer's country. Drafts are only stored: they come back in `emailBatch` for review, and
                    nothing is sent until the batch is approved with the email-batches endpoint.
                    The response carries a `sessionId`. Send it back with the next request to continue the conversation:
                    the agent remembers what was said and can revise the drafts under review ("drop the customers from
                    Norway", "make the first email shorter") in the same batch.
                    Returns the answer, the last query result, every step the agent took and the batch under review.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer written"),
            @ApiResponse(responseCode = "400", description = "Invalid request body",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Company has no customers, or the session does not exist for this company",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "410", description = "The session expired; start a new one",
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
            @Valid @RequestBody AgentAskRequest request) {
        return ResponseEntity.ok(agenticAssistantService.ask(companyId, request.prompt(), request.sessionId()));
    }
}
