package com.mycompany.customersapi.service;

/** Port for delivering one email; the production adapter is {@link SesEmailSender}. */
public interface EmailSender {

    /** False when no sender address is configured; nothing can be delivered then. */
    boolean isConfigured();

    /**
     * @return the mail service's id for the accepted message
     * @throws EmailDeliveryException if the mail service refuses or fails to take the message
     */
    String send(OutgoingEmail email);

    record OutgoingEmail(String toAddress, String subject, String body) {
    }

    class EmailDeliveryException extends RuntimeException {
        public EmailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
