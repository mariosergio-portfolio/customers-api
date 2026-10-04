package com.mycompany.customersapi.controller;

import com.mycompany.customersapi.config.GlobalExceptionHandler;
import com.mycompany.customersapi.dto.EmailBatchResponse;
import com.mycompany.customersapi.service.EmailBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Emails Management", description = "Manage email batches")
@RestController
@RequestMapping("/api/companies/{companyId}/email-batches")
@RequiredArgsConstructor
@Validated
public class EmailBatchController {

    private final EmailBatchService emailBatchService;

    @Operation(
            summary = "Review a batch of drafted emails",
            description = "Returns the emails the assistant drafted and their status. Nothing is sent by reading a batch."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch found"),
            @ApiResponse(responseCode = "404", description = "No such batch for this company",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @GetMapping("/{batchId}")
    public ResponseEntity<EmailBatchResponse> get(
            @Parameter(description = "Company identifier", required = true) @PathVariable("companyId") @NotNull Long companyId,
            @Parameter(description = "Batch identifier", required = true) @PathVariable("batchId") @NotNull UUID batchId) {
        return ResponseEntity.ok(emailBatchService.get(companyId, batchId));
    }

    @Operation(
            summary = "Approve a batch and send its emails",
            description = """
                    Sends every email of the batch through Amazon SES. This is the only call that sends mail; the
                    assistant itself can only draft. Emails SES refuses are marked FAILED and the batch becomes
                    PARTIALLY_SENT; approving again retries only those.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approval processed; check each draft's status"),
            @ApiResponse(responseCode = "404", description = "No such batch for this company",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The batch was already sent",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "410", description = "The batch expired; draft it again",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "No sender address is configured",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/{batchId}/approve")
    public ResponseEntity<EmailBatchResponse> approve(
            @Parameter(description = "Company identifier", required = true) @PathVariable("companyId") @NotNull Long companyId,
            @Parameter(description = "Batch identifier", required = true) @PathVariable("batchId") @NotNull UUID batchId) {
        return ResponseEntity.ok(emailBatchService.approve(companyId, batchId));
    }
}
