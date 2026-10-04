package com.mycompany.customersapi.controller;

import com.mycompany.customersapi.config.GlobalExceptionHandler;
import com.mycompany.customersapi.dto.BirthdayGreetingResponse;
import com.mycompany.customersapi.service.BirthdayGreetingService;
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

@Tag(name = "Bedrock", description = "Send prompts to a foundation model on AWS Bedrock")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class BirthdayGreetingController {

    private final BirthdayGreetingService birthdayGreetingService;

    @Operation(
            summary = "Write a birthday greeting for a customer",
            description = """
                    Writes a birthday greeting with the Bedrock model, in the main language of the customer's
                    country and with a formality that follows the customer's age. The service picks the language and
                    the tone; the model never receives the customer's name, email, phone, country or exact age.
                    It writes a placeholder that the service replaces with the customer's name.
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
}
