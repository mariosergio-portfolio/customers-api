package com.mycompany.customersapi.service.query;

/** The model produced SQL that is unusable or was rejected; mapped to HTTP 422. */
public class GeneratedQueryException extends RuntimeException {
    public GeneratedQueryException(String message) {
        super(message);
    }

    public GeneratedQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
