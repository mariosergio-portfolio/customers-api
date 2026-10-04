package com.mycompany.customersapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One drafted email. The recipient address is copied from the customer record when the draft is stored,
 * never taken from the model.
 */
@Entity
@Table(name = "EMAIL_DRAFT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailDraft {

    @Id
    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private EmailBatch batch;

    @Column(name = "customer_pk", nullable = false, updatable = false)
    private UUID customerPk;

    /** Business id of the customer within the company. */
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "recipient_name", nullable = false, updatable = false, length = 255)
    private String recipientName;

    @Column(name = "recipient_email", nullable = false, updatable = false, length = 255)
    private String recipientEmail;

    /** Language the email was requested in, from the fixed country lookup. Subject and body can be rewritten while the batch is DRAFTED. */
    @Column(name = "language", nullable = false, length = 50)
    private String language;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "body", nullable = false, length = 10000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private EmailDraftStatus status;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "message_id", length = 255)
    private String messageId;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
