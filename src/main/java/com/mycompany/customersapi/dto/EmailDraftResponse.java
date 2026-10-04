package com.mycompany.customersapi.dto;

import com.mycompany.customersapi.domain.EmailDraft;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "One email drafted by the assistant, to review before the batch is approved")
public record EmailDraftResponse(
        @Schema(description = "Draft identifier") UUID draftId,
        @Schema(description = "Business id of the customer within the company") Long customerId,
        @Schema(description = "Customer name") String name,
        @Schema(description = "Recipient address, taken from the customer record") String email,
        @Schema(description = "Language the email was requested in", example = "French") String language,
        @Schema(description = "Email subject") String subject,
        @Schema(description = "Email body (plain text)") String body,
        @Schema(description = "PENDING (not sent), SENT or FAILED") String status,
        @Schema(description = "Why sending failed, when status is FAILED") String error) {

    public static EmailDraftResponse from(EmailDraft draft) {
        return new EmailDraftResponse(draft.getDraftId(), draft.getCustomerId(), draft.getRecipientName(),
                draft.getRecipientEmail(), draft.getLanguage(), draft.getSubject(), draft.getBody(),
                draft.getStatus().name(), draft.getError());
    }
}
