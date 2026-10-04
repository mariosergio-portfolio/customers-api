package com.mycompany.customersapi.service.email;

import java.util.UUID;

/**
 * An email draft as the agent sees and edits it, before it is stored or while it waits in an open batch.
 * The recipient comes from the customer record, never from the model.
 *
 * @param customerPk technical key of the customer
 * @param customerId business id of the customer within the company, the id the model uses
 */
public record PendingDraft(UUID customerPk, Long customerId, String name, String email,
                           String language, String subject, String body) {
}
