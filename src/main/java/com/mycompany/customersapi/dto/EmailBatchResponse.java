package com.mycompany.customersapi.dto;

import com.mycompany.customersapi.domain.EmailBatch;
import com.mycompany.customersapi.domain.EmailDraftStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Emails drafted for a company. Nothing is sent until the batch is approved.")
public record EmailBatchResponse(
        @Schema(description = "Batch identifier; approve it with POST /companies/{companyId}/email-batches/{batchId}/approve") UUID batchId,
        @Schema(description = "Company the batch belongs to") Long companyId,
        @Schema(description = "DRAFTED (waiting for approval), SENT or PARTIALLY_SENT") String status,
        @Schema(description = "When the batch was drafted") LocalDateTime createdAt,
        @Schema(description = "Approval is refused after this moment") LocalDateTime expiresAt,
        @Schema(description = "Number of emails in the batch") int recipientCount,
        @Schema(description = "Number of emails already sent") int sentCount,
        @Schema(description = "The drafts, for review") List<EmailDraftResponse> drafts) {

    public static EmailBatchResponse from(EmailBatch batch) {
        List<EmailDraftResponse> drafts = batch.getDrafts().stream().map(EmailDraftResponse::from).toList();
        int sent = (int) batch.getDrafts().stream().filter(d -> d.getStatus() == EmailDraftStatus.SENT).count();
        return new EmailBatchResponse(batch.getBatchId(), batch.getCompanyId(), batch.getStatus().name(),
                batch.getCreatedAt(), batch.getExpiresAt(), drafts.size(), sent, drafts);
    }
}
