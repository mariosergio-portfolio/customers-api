package com.mycompany.customersapi.controller;

import com.mycompany.customersapi.config.GlobalExceptionHandler;
import com.mycompany.customersapi.dto.CustomerPageResponse;
import com.mycompany.customersapi.service.CustomerService;
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

import java.util.UUID;

@Tag(name = "Customers", description = "Endpoints for querying customers with optional partial-text filters")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
@Slf4j
public class CustomerController {

    private final CustomerService customerService;

    @Operation(
            summary = "Search customers",
            description = """
                    Returns all customers for the given company.
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
            @RequestParam(value = "country", required = false) String country,

            @Parameter(description = "Sort order: 'id' (default) or 'name'")
            @RequestParam(value = "orderBy", required = false, defaultValue = "id") String orderBy) {

        return ResponseEntity.ok(customerService.search(companyId, name, country, orderBy));
    }

    @Operation(
            summary = "Pronounce customer name",
            description = "Calls AWS Polly Neural TTS to synthesize the customer's name and country, returning MP3 audio."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "MP3 audio bytes",
                    content = @Content(mediaType = "audio/mpeg")),
            @ApiResponse(responseCode = "404", description = "Customer not found",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @GetMapping("/customers/{customerPk}/pronounce")
    public ResponseEntity<byte[]> pronounce(
            @Parameter(description = "Customer PK", required = true)
            @PathVariable("customerPk") @NotNull UUID customerPk,

            @Parameter(description = "BCP-47 language code for Polly TTS (e.g. en-US, pt-BR). Defaults to en-US.")
            @RequestParam(value = "language", required = false, defaultValue = "en-US") String language) {

        byte[] mp3 = customerService.pronounce(customerPk, language);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + customerPk + "-pronounce.mp3\"")
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .contentLength(mp3.length)
                .body(mp3);
    }

  /*  @GetMapping("/customers/{customerPk}/blip")
    public ResponseEntity<byte[]> blip(
            @Parameter(description = "Customer PK", required = true)
            @PathVariable("customerPk") @NotNull UUID customerPk) {

        byte[] wav = mockBlipService.generateWav(customerService.getCustomer(customerPk).getName());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + customerPk + "-blip.wav\"")
                .contentType(MediaType.parseMediaType("audio/wav"))
                .contentLength(wav.length)
                .body(wav);
    }*/
}
