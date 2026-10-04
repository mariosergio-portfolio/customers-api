package com.mycompany.customersapi.domain;

/** Where a batch of drafted emails is. Nothing is sent while it is DRAFTED. */
public enum EmailBatchStatus {
    /** Waiting for a human to review and approve. */
    DRAFTED,
    /** Approved and every email was accepted by the mail service. */
    SENT,
    /** Approved, but some emails failed; approving again retries only those. */
    PARTIALLY_SENT
}
