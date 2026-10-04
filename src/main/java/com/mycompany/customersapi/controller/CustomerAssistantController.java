package com.mycompany.customersapi.controller;

import com.mycompany.customersapi.config.GlobalExceptionHandler;
import com.mycompany.customersapi.domain.PronounceLanguage;
import com.mycompany.customersapi.dto.BirthdayGreetingResponse;
import com.mycompany.customersapi.service.BirthdayGreetingService;
import com.mycompany.customersapi.service.PronounceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Customer AI Assistant", description = "Send prompts to a foundation model on AWS Bedrock")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class CustomerAssistantController {

    private final BirthdayGreetingService birthdayGreetingService;
    private final PronounceService pronounceService;

    @Operation(
            summary = "Write a birthday greeting for a customer",
            description = """
                    Writes a birthday greeting with the Bedrock model. The model receives the customer's name and
                    country and chooses the language (the main language of the country) and the grammatical gender.
                    The service sets the tone from the customer's age and sends only that band, not the exact age.
                    Email and phone are not sent.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Greeting written"),
            @ApiResponse(responseCode = "404", description = "Customer not found",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Bedrock call failed",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/customers/{customerPk}/birthday-greetings")
    public ResponseEntity<BirthdayGreetingResponse> birthdayGreeting(
            @Parameter(description = "Customer PK", required = true)
            @PathVariable("customerPk") @NotNull UUID customerPk) {
        return ResponseEntity.ok(birthdayGreetingService.greet(customerPk));
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

            @Parameter(description = "BCP-47 language code for Polly TTS. Defaults to en-US.")
            @RequestParam(value = "language", required = false, defaultValue = "en-US") PronounceLanguage language) {

        byte[] mp3 = pronounceService.pronounce(customerPk, language);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + customerPk + "-pronounce.mp3\"")
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .contentLength(mp3.length)
                .body(mp3);
    }
}
