package com.mycompany.customersapi.service;

import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.domain.EmailBatch;
import com.mycompany.customersapi.domain.EmailBatchStatus;
import com.mycompany.customersapi.domain.EmailDraft;
import com.mycompany.customersapi.domain.EmailDraftStatus;
import com.mycompany.customersapi.dto.EmailBatchResponse;
import com.mycompany.customersapi.repository.EmailBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

/**
 * Stores the emails the agent drafted and sends them when a person approves the batch.
 *
 * The agent can only create drafts. Delivery happens here, in {@link #approve}, behind a separate request,
 * so no model output can send mail by itself. Approval is capped per batch and refused once the batch has
 * expired.
 */
@Service
@Slf4j
public class EmailBatchService {

    private static final int MAX_ERROR_CHARS = 500;

    private final EmailBatchRepository batchRepository;
    private final EmailSender          emailSender;
    private final Clock                clock;
    private final int                  maxRecipients;
    private final Duration             batchTtl;

    public EmailBatchService(EmailBatchRepository batchRepository,
                             EmailSender emailSender,
                             Clock clock,
                             @Value("${assistant.email.max-recipients:25}") int maxRecipients,
                             @Value("${assistant.email.batch-ttl-hours:24}") int batchTtlHours) {
        this.batchRepository = batchRepository;
        this.emailSender = emailSender;
        this.clock = clock;
        this.maxRecipients = maxRecipients;
        this.batchTtl = Duration.ofHours(batchTtlHours);
    }

    @Transactional
    public EmailBatchResponse createBatch(Long companyId, String prompt, Collection<PendingDraft> pendingDrafts) {
        if (pendingDrafts.isEmpty() || pendingDrafts.size() > maxRecipients) {
            throw new IllegalArgumentException("A batch needs 1 to " + maxRecipients + " drafts, got " + pendingDrafts.size());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        EmailBatch batch = EmailBatch.builder()
                .batchId(UUID.randomUUID())
                .companyId(companyId)
                .prompt(prompt)
                .status(EmailBatchStatus.DRAFTED)
                .createdAt(now)
                .expiresAt(now.plus(batchTtl))
                .build();
        for (PendingDraft pending : pendingDrafts) {
            Customer customer = pending.customer();
            batch.addDraft(EmailDraft.builder()
                    .draftId(UUID.randomUUID())
                    .customerPk(customer.getCustomerPk())
                    .customerId(customer.getId())
                    .recipientName(customer.getName())
                    .recipientEmail(customer.getEmail())
                    .language(pending.language())
                    .subject(pending.subject())
                    .body(pending.body())
                    .status(EmailDraftStatus.PENDING)
                    .build());
        }
        batchRepository.save(batch);
        log.info("Email batch drafted: batchId={}, companyId={}, drafts={}", batch.getBatchId(), companyId, pendingDrafts.size());
        return EmailBatchResponse.from(batch);
    }

    @Transactional(readOnly = true)
    public EmailBatchResponse get(Long companyId, UUID batchId) {
        return EmailBatchResponse.from(batchRepository.findByBatchIdAndCompanyId(batchId, companyId)
                .orElseThrow(() -> notFound(batchId)));
    }

    /**
     * Sends every draft that is not sent yet. A refused email is recorded on its draft and does not stop the
     * others; approving again retries only the failed ones.
     */
    @Transactional
    public EmailBatchResponse approve(Long companyId, UUID batchId) {
        if (!emailSender.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email sending is not configured (set aws.ses.from-address)");
        }
        EmailBatch batch = batchRepository.lockByBatchIdAndCompanyId(batchId, companyId)
                .orElseThrow(() -> notFound(batchId));
        if (batch.getStatus() == EmailBatchStatus.SENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch " + batchId + " was already sent");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isAfter(batch.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Batch " + batchId + " expired; ask the assistant to draft it again");
        }
        if (batch.getDrafts().size() > maxRecipients) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Batch has more than " + maxRecipients + " recipients");
        }

        for (EmailDraft draft : batch.getDrafts()) {
            if (draft.getStatus() != EmailDraftStatus.SENT) {
                send(draft, now);
            }
        }
        boolean allSent = batch.getDrafts().stream().allMatch(d -> d.getStatus() == EmailDraftStatus.SENT);
        batch.setStatus(allSent ? EmailBatchStatus.SENT : EmailBatchStatus.PARTIALLY_SENT);
        batch.setApprovedAt(now);
        log.info("Email batch approved: batchId={}, status={}", batchId, batch.getStatus());
        return EmailBatchResponse.from(batch);
    }

    private void send(EmailDraft draft, LocalDateTime now) {
        try {
            String messageId = emailSender.send(new EmailSender.OutgoingEmail(
                    draft.getRecipientEmail(), draft.getSubject(), draft.getBody()));
            draft.setStatus(EmailDraftStatus.SENT);
            draft.setMessageId(messageId);
            draft.setSentAt(now);
            draft.setError(null);
        } catch (EmailSender.EmailDeliveryException e) {
            draft.setStatus(EmailDraftStatus.FAILED);
            draft.setError(truncate(e.getMessage()));
        }
    }

    private static ResponseStatusException notFound(UUID batchId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Email batch not found: " + batchId);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "Send failed";
        }
        return message.length() <= MAX_ERROR_CHARS ? message : message.substring(0, MAX_ERROR_CHARS);
    }
}
