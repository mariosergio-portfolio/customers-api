package com.mycompany.customersapi.service.agent.review;

/** The reviewer could not give a usable answer (model error, or an answer that could not be read). */
public class DraftReviewException extends RuntimeException {

    public DraftReviewException(String message, Throwable cause) {
        super(message, cause);
    }
}
