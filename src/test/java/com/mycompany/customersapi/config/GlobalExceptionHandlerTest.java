package com.mycompany.customersapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void responseStatusExceptionKeepsItsStatusAndReason() {
        var response = handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found: 9"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals(404, response.getBody().getStatus());
        assertEquals("Company not found: 9", response.getBody().getMessage());
    }

    @Test
    void withoutAReasonTheStatusTextIsUsed() {
        var response = handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertEquals("Not Found", response.getBody().getMessage());
    }

    @Test
    void otherExceptionsStillGiveAGeneric500() {
        var response = handler.handleGeneric(new RuntimeException("boom"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals("An unexpected error occurred", response.getBody().getMessage());
    }
}
