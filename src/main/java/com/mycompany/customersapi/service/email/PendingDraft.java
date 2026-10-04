package com.mycompany.customersapi.service.email;

import com.mycompany.customersapi.domain.Customer;

/** An email the agent drafted, not stored yet. The recipient comes from the customer record. */
public record PendingDraft(Customer customer, String language, String subject, String body) {
}
