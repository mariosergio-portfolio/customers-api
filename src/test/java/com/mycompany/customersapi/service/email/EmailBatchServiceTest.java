package com.mycompany.customersapi.service.email;

import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.domain.EmailBatch;
import com.mycompany.customersapi.domain.EmailBatchStatus;
import com.mycompany.customersapi.domain.EmailDraft;
import com.mycompany.customersapi.domain.EmailDraftStatus;
import com.mycompany.customersapi.dto.EmailBatchResponse;
import com.mycompany.customersapi.repository.EmailBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailBatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private EmailBatchRepository repository;
    private EmailSender sender;
    private Clock clock;
    private EmailBatchService service;

    @BeforeEach
    void setUp() {
        repository = mock(EmailBatchRepository.class);
        sender = mock(EmailSender.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new EmailBatchService(repository, sender, clock, 3, 24);
        when(sender.isConfigured()).thenReturn(true);
    }

    private static PendingDraft pending(long id, String name) {
        Customer customer = Customer.builder().customerPk(UUID.randomUUID()).id(id).companyId(1L).name(name)
                .email(name.toLowerCase() + "@example.com").country("France").build();
        return new PendingDraft(customer.getCustomerPk(), customer.getId(), customer.getName(), customer.getEmail(),
                "French", "Sujet " + name, "Corps " + name);
    }

    private EmailBatch storedBatch(EmailBatchStatus status, LocalDateTime expiresAt, EmailDraftStatus... draftStatuses) {
        EmailBatch batch = EmailBatch.builder().batchId(UUID.randomUUID()).companyId(1L).prompt("p").status(status)
                .createdAt(LocalDateTime.now(clock)).expiresAt(expiresAt).build();
        for (int i = 0; i < draftStatuses.length; i++) {
            batch.addDraft(EmailDraft.builder().draftId(UUID.randomUUID()).customerPk(UUID.randomUUID()).customerId((long) i)
                    .recipientName("N" + i).recipientEmail("n" + i + "@example.com").language("French")
                    .subject("s" + i).body("b" + i).status(draftStatuses[i]).build());
        }
        when(repository.lockByBatchIdAndCompanyId(batch.getBatchId(), 1L)).thenReturn(Optional.of(batch));
        when(repository.findByBatchIdAndCompanyId(batch.getBatchId(), 1L)).thenReturn(Optional.of(batch));
        return batch;
    }

    private LocalDateTime inOneHour() {
        return LocalDateTime.now(clock).plusHours(1);
    }

    // ── drafting ─────────────────────────────────────────────────────────────

    @Test
    void should_store_pending_drafts_with_the_recipient_taken_from_the_customer() {
        EmailBatchResponse response = service.createBatch(1L, "Greet everyone", List.of(pending(1, "Ann"), pending(2, "Bob")));

        assertEquals("DRAFTED", response.status());
        assertEquals(2, response.recipientCount());
        assertEquals(0, response.sentCount());
        assertEquals(LocalDateTime.now(clock).plusHours(24), response.expiresAt());
        assertEquals(List.of("ann@example.com", "bob@example.com"),
                response.drafts().stream().map(d -> d.email()).sorted().toList());
        assertTrue(response.drafts().stream().allMatch(d -> "PENDING".equals(d.status())));
        verify(repository).save(any(EmailBatch.class));
        verifyNoInteractions(sender);
    }

    @Test
    void should_refuse_an_empty_batch_or_one_over_the_cap() {
        assertThrows(IllegalArgumentException.class, () -> service.createBatch(1L, "p", List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.createBatch(1L, "p",
                List.of(pending(1, "A"), pending(2, "B"), pending(3, "C"), pending(4, "D"))));
    }

    @Test
    void should_return_404_when_the_batch_belongs_to_another_company() {
        when(repository.findByBatchIdAndCompanyId(any(), any())).thenReturn(Optional.empty());

        var ex = assertThrows(ResponseStatusException.class, () -> service.get(2L, UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ── approving ────────────────────────────────────────────────────────────

    @Test
    void should_send_every_pending_draft_and_mark_the_batch_sent() {
        EmailBatch batch = storedBatch(EmailBatchStatus.DRAFTED, inOneHour(), EmailDraftStatus.PENDING, EmailDraftStatus.PENDING);
        when(sender.send(any())).thenReturn("msg-1", "msg-2");

        EmailBatchResponse response = service.approve(1L, batch.getBatchId());

        assertEquals("SENT", response.status());
        assertEquals(2, response.sentCount());
        verify(sender, times(2)).send(any());
        assertTrue(batch.getDrafts().stream().allMatch(d -> d.getStatus() == EmailDraftStatus.SENT && d.getSentAt() != null));
        assertNotNull(batch.getApprovedAt());
    }

    @Test
    void should_record_a_refused_email_and_still_send_the_others() {
        EmailBatch batch = storedBatch(EmailBatchStatus.DRAFTED, inOneHour(), EmailDraftStatus.PENDING, EmailDraftStatus.PENDING);
        when(sender.send(any()))
                .thenThrow(new EmailSender.EmailDeliveryException("SES send failed: address not verified", null))
                .thenReturn("msg-2");

        EmailBatchResponse response = service.approve(1L, batch.getBatchId());

        assertEquals("PARTIALLY_SENT", response.status());
        assertEquals(1, response.sentCount());
        assertEquals(List.of("FAILED", "SENT"), batch.getDrafts().stream().map(d -> d.getStatus().name()).toList());
        assertTrue(batch.getDrafts().getFirst().getError().contains("not verified"));
    }

    @Test
    void should_retry_only_the_failed_drafts_when_approved_again() {
        EmailBatch batch = storedBatch(EmailBatchStatus.PARTIALLY_SENT, inOneHour(), EmailDraftStatus.SENT, EmailDraftStatus.FAILED);
        when(sender.send(any())).thenReturn("msg-2");

        EmailBatchResponse response = service.approve(1L, batch.getBatchId());

        assertEquals("SENT", response.status());
        verify(sender, times(1)).send(any());
    }

    @Test
    void should_refuse_a_batch_that_was_already_sent() {
        EmailBatch batch = storedBatch(EmailBatchStatus.SENT, inOneHour(), EmailDraftStatus.SENT);

        var ex = assertThrows(ResponseStatusException.class, () -> service.approve(1L, batch.getBatchId()));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(sender, never()).send(any());
    }

    @Test
    void should_refuse_an_expired_batch() {
        EmailBatch batch = storedBatch(EmailBatchStatus.DRAFTED, LocalDateTime.now(clock).minusMinutes(1), EmailDraftStatus.PENDING);

        var ex = assertThrows(ResponseStatusException.class, () -> service.approve(1L, batch.getBatchId()));

        assertEquals(HttpStatus.GONE, ex.getStatusCode());
        verify(sender, never()).send(any());
    }

    @Test
    void should_answer_503_when_no_sender_address_is_configured() {
        when(sender.isConfigured()).thenReturn(false);
        EmailBatch batch = storedBatch(EmailBatchStatus.DRAFTED, inOneHour(), EmailDraftStatus.PENDING);

        var ex = assertThrows(ResponseStatusException.class, () -> service.approve(1L, batch.getBatchId()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        verify(sender, never()).send(any());
    }

    @Test
    void should_refuse_a_batch_over_the_recipient_cap_even_if_it_was_stored() {
        EmailBatch batch = storedBatch(EmailBatchStatus.DRAFTED, inOneHour(), EmailDraftStatus.PENDING, EmailDraftStatus.PENDING,
                EmailDraftStatus.PENDING, EmailDraftStatus.PENDING);

        var ex = assertThrows(ResponseStatusException.class, () -> service.approve(1L, batch.getBatchId()));

        assertEquals(422, ex.getStatusCode().value());
        verify(sender, never()).send(any());
    }

    @Test
    void should_return_404_when_approving_a_batch_of_another_company() {
        when(repository.lockByBatchIdAndCompanyId(any(), any())).thenReturn(Optional.empty());

        var ex = assertThrows(ResponseStatusException.class, () -> service.approve(2L, UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ── reading and editing a batch under review ─────────────────────────────

    private static PendingDraft rewrite(EmailDraft existing, String subject, String body) {
        return new PendingDraft(existing.getCustomerPk(), existing.getCustomerId(), existing.getRecipientName(),
                existing.getRecipientEmail(), "French", subject, body);
    }

    @Test
    void should_list_the_open_drafts_of_a_batch_waiting_for_approval() {
        EmailBatch batch = storedBatch(EmailBatchStatus.DRAFTED, inOneHour(), EmailDraftStatus.PENDING, EmailDraftStatus.PENDING);

        List<PendingDraft> open = service.openDrafts(1L, batch.getBatchId());

        assertEquals(2, open.size());
        assertEquals(batch.getDrafts().getFirst().getSubject(), open.getFirst().subject());
        assertEquals(batch.getDrafts().getFirst().getCustomerPk(), open.getFirst().customerPk());
    }

    @Test
    void should_list_nothing_for_a_sent_expired_or_unknown_batch() {
        EmailBatch sent = storedBatch(EmailBatchStatus.SENT, inOneHour(), EmailDraftStatus.SENT);
        EmailBatch expired = storedBatch(EmailBatchStatus.DRAFTED, LocalDateTime.now(clock).minusMinutes(1), EmailDraftStatus.PENDING);
        when(repository.findByBatchIdAndCompanyId(sent.getBatchId(), 1L)).thenReturn(Optional.of(sent));
        when(repository.findByBatchIdAndCompanyId(expired.getBatchId(), 1L)).thenReturn(Optional.of(expired));

        assertTrue(service.openDrafts(1L, sent.getBatchId()).isEmpty());
        assertTrue(service.openDrafts(1L, expired.getBatchId()).isEmpty());
        assertTrue(service.openDrafts(1L, UUID.randomUUID()).isEmpty());
    }

    @Test
    void should_rewrite_add_and_remove_drafts_so_the_batch_holds_exactly_the_given_ones() {
        EmailBatch batch = storedBatch(EmailBatchStatus.DRAFTED, inOneHour(), EmailDraftStatus.PENDING, EmailDraftStatus.PENDING);
        EmailDraft kept = batch.getDrafts().get(0);
        PendingDraft added = pending(99, "Zoe");

        EmailBatchResponse response = service.updateDrafts(1L, batch.getBatchId(),
                List.of(rewrite(kept, "Nouveau sujet", "Nouveau corps"), added));

        assertEquals(2, response.recipientCount());
        assertEquals(2, batch.getDrafts().size());
        assertSame(kept, batch.getDrafts().stream().filter(d -> d.getCustomerPk().equals(kept.getCustomerPk())).findFirst().orElseThrow());
        assertEquals("Nouveau sujet", kept.getSubject());
        assertEquals("Nouveau corps", kept.getBody());
        assertTrue(batch.getDrafts().stream().anyMatch(d -> d.getCustomerId() == 99L && d.getStatus() == EmailDraftStatus.PENDING));
    }

    @Test
    void should_refuse_to_edit_a_batch_that_was_sent_or_expired() {
        EmailBatch sent = storedBatch(EmailBatchStatus.SENT, inOneHour(), EmailDraftStatus.SENT);
        EmailBatch expired = storedBatch(EmailBatchStatus.DRAFTED, LocalDateTime.now(clock).minusMinutes(1), EmailDraftStatus.PENDING);

        var conflict = assertThrows(ResponseStatusException.class,
                () -> service.updateDrafts(1L, sent.getBatchId(), List.of(pending(1, "A"))));
        var gone = assertThrows(ResponseStatusException.class,
                () -> service.updateDrafts(1L, expired.getBatchId(), List.of(pending(1, "A"))));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(HttpStatus.GONE, gone.getStatusCode());
    }

    @Test
    void should_refuse_an_edit_with_no_drafts_or_more_than_the_cap() {
        EmailBatch batch = storedBatch(EmailBatchStatus.DRAFTED, inOneHour(), EmailDraftStatus.PENDING);

        assertThrows(IllegalArgumentException.class, () -> service.updateDrafts(1L, batch.getBatchId(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.updateDrafts(1L, batch.getBatchId(),
                List.of(pending(1, "A"), pending(2, "B"), pending(3, "C"), pending(4, "D"))));
    }

    @Test
    void should_discard_a_batch_that_was_not_sent() {
        EmailBatch batch = storedBatch(EmailBatchStatus.DRAFTED, inOneHour(), EmailDraftStatus.PENDING);

        service.discard(1L, batch.getBatchId());

        verify(repository).delete(batch);
    }

    @Test
    void should_ignore_discarding_a_batch_that_does_not_exist() {
        when(repository.lockByBatchIdAndCompanyId(any(), any())).thenReturn(Optional.empty());

        service.discard(1L, UUID.randomUUID());

        verify(repository, never()).delete(any(EmailBatch.class));
    }

    @Test
    void should_refuse_to_discard_a_batch_that_was_already_sent() {
        EmailBatch batch = storedBatch(EmailBatchStatus.SENT, inOneHour(), EmailDraftStatus.SENT);

        var ex = assertThrows(ResponseStatusException.class, () -> service.discard(1L, batch.getBatchId()));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(repository, never()).delete(any(EmailBatch.class));
    }
}
