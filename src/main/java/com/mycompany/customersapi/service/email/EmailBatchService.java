package com.mycompany.customersapi.service.email;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        pendingDrafts.forEach(pending -> batch.addDraft(newDraft(pending)));
        batchRepository.save(batch);
        log.info("Email batch drafted: batchId={}, companyId={}, drafts={}", batch.getBatchId(), companyId, pendingDrafts.size());
        return EmailBatchResponse.from(batch);
    }

    /** The drafts of a batch still waiting for approval; empty if it is missing, sent or expired. */
    @Transactional(readOnly = true)
    public List<PendingDraft> openDrafts(Long companyId, UUID batchId) {
        return batchRepository.findByBatchIdAndCompanyId(batchId, companyId)
                .filter(batch -> batch.getStatus() == EmailBatchStatus.DRAFTED && !isExpired(batch))
                .map(batch -> batch.getDrafts().stream().map(EmailBatchService::pendingOf).toList())
                .orElse(List.of());
    }

    /**
     * Makes the batch hold exactly these drafts: rewrites the ones that exist, adds new ones, removes the rest.
     * Only a DRAFTED batch can be edited.
     */
    @Transactional
    public EmailBatchResponse updateDrafts(Long companyId, UUID batchId, Collection<PendingDraft> pendingDrafts) {
        if (pendingDrafts.isEmpty() || pendingDrafts.size() > maxRecipients) {
            throw new IllegalArgumentException("A batch needs 1 to " + maxRecipients + " drafts, got " + pendingDrafts.size());
        }
        EmailBatch batch = editableBatch(companyId, batchId);

        Map<UUID, EmailDraft> existing = batch.getDrafts().stream()
                .collect(Collectors.toMap(EmailDraft::getCustomerPk, Function.identity()));
        Set<UUID> keep = new HashSet<>();
        for (PendingDraft pending : pendingDrafts) {
            keep.add(pending.customerPk());
            EmailDraft draft = existing.get(pending.customerPk());
            if (draft == null) {
                batch.addDraft(newDraft(pending));
            } else {
                draft.setLanguage(pending.language());
                draft.setSubject(pending.subject());
                draft.setBody(pending.body());
            }
        }
        batch.getDrafts().removeIf(draft -> !keep.contains(draft.getCustomerPk()));
        log.info("Email batch edited: batchId={}, drafts={}", batchId, batch.getDrafts().size());
        return EmailBatchResponse.from(batch);
    }

    /** Deletes a batch that has not been sent. A batch that does not exist is ignored. */
    @Transactional
    public void discard(Long companyId, UUID batchId) {
        batchRepository.lockByBatchIdAndCompanyId(batchId, companyId).ifPresent(batch -> {
            if (batch.getStatus() != EmailBatchStatus.DRAFTED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch " + batchId + " was already sent and cannot be discarded");
            }
            batchRepository.delete(batch);
            log.info("Email batch discarded: batchId={}", batchId);
        });
    }

    private EmailBatch editableBatch(Long companyId, UUID batchId) {
        EmailBatch batch = batchRepository.lockByBatchIdAndCompanyId(batchId, companyId)
                .orElseThrow(() -> notFound(batchId));
        if (batch.getStatus() != EmailBatchStatus.DRAFTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch " + batchId + " was already sent and cannot be edited");
        }
        if (isExpired(batch)) {
            throw new ResponseStatusException(HttpStatus.GONE, "Batch " + batchId + " expired; ask the assistant to draft it again");
        }
        return batch;
    }

    private boolean isExpired(EmailBatch batch) {
        return LocalDateTime.now(clock).isAfter(batch.getExpiresAt());
    }

    private static EmailDraft newDraft(PendingDraft pending) {
        return EmailDraft.builder()
                .draftId(UUID.randomUUID())
                .customerPk(pending.customerPk())
                .customerId(pending.customerId())
                .recipientName(pending.name())
                .recipientEmail(pending.email())
                .language(pending.language())
                .subject(pending.subject())
                .body(pending.body())
                .status(EmailDraftStatus.PENDING)
                .build();
    }

    private static PendingDraft pendingOf(EmailDraft draft) {
        return new PendingDraft(draft.getCustomerPk(), draft.getCustomerId(), draft.getRecipientName(),
                draft.getRecipientEmail(), draft.getLanguage(), draft.getSubject(), draft.getBody());
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
        if (isExpired(batch)) {
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
