package com.planet.customersapi.controller;

import com.planet.customersapi.config.GlobalExceptionHandler;
import com.planet.customersapi.dto.CustomerPageResponse;
import com.planet.customersapi.service.CustomerService;
import com.planet.customersapi.service.PronounceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Customers", description = "Endpoints for querying customers with optional partial-text filters")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
@Slf4j
public class CustomerController {

    private final CustomerService customerService;
    private final PronounceService pronounceService;

    @Operation(
            summary = "Search customers",
            description = """
                    Returns all customers for the given company and domain.
                    Optional query parameters `name` and `country` perform case-insensitive
                    partial-text (LIKE) filtering. When both are provided they are combined with AND.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search executed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @GetMapping("/companies/{companyId}/customers")
    public ResponseEntity<CustomerPageResponse> searchCustomers(
            @Parameter(description = "Company identifier", required = true)
            @PathVariable("companyId") @NotNull Long companyId,

            @Parameter(description = "Partial text filter on customer name (case-insensitive)")
            @RequestParam(value = "name", required = false) String name,

            @Parameter(description = "Partial text filter on country (case-insensitive)")
            @RequestParam(value = "country", required = false) String country) {

        CustomerPageResponse response = customerService.search(companyId, name, country);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Pronounce customer name",
            description = """
                    Returns a WAV audio clip that pronounces the customer's name.
                    Currently uses a mock sine-wave generator; a real TTS engine will be
                    plugged in at a later stage.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "WAV audio bytes",
                    content = @Content(mediaType = "audio/wav")),
            @ApiResponse(responseCode = "404", description = "Customer not found",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @GetMapping("/customers/{customerId}/pronounce")
    public ResponseEntity<byte[]> pronounceCustomerName(
            @Parameter(description = "Customer identifier", required = true)
            @PathVariable("customerId") @NotNull Long customerId) {

        String name = customerService.getCustomerName(customerId);
        byte[] wav  = pronounceService.generateWav(name);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + customerId + "-pronounce.wav\"")
                .contentType(MediaType.parseMediaType("audio/wav"))
                .contentLength(wav.length)
                .body(wav);
    }
}
