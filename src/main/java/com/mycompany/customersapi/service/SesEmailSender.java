package com.mycompany.customersapi.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Sends plain-text email through Amazon SES. Credentials come from the AWS default chain; the identity
 * needs ses:SendEmail on the verified sender. While the SES account is in the sandbox, recipients must be
 * verified too and other addresses fail per message.
 */
@Component
@Slf4j
public class SesEmailSender implements EmailSender {

    private static final String CHARSET = "UTF-8";

    @Value("${aws.ses.region:us-east-1}")
    private String region;

    @Value("${aws.ses.from-address:}")
    private String fromAddress;

    private SesV2Client client;

    @PostConstruct
    void init() {
        client = SesV2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        log.info("AWS SES client initialised — region={}, sender={}", region,
                isConfigured() ? fromAddress : "(not configured: emails cannot be sent)");
    }

    @Override
    public boolean isConfigured() {
        return fromAddress != null && !fromAddress.isBlank();
    }

    @Override
    public String send(OutgoingEmail email) {
        try {
            return client.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(fromAddress)
                    .destination(Destination.builder().toAddresses(email.toAddress()).build())
                    .content(EmailContent.builder().simple(Message.builder()
                            .subject(Content.builder().data(email.subject()).charset(CHARSET).build())
                            .body(Body.builder().text(Content.builder().data(email.body()).charset(CHARSET).build()).build())
                            .build()).build())
                    .build()).messageId();
        } catch (SdkException e) {
            log.warn("AWS SES refused an email to {}: {}", email.toAddress(), e.getMessage());
            throw new EmailDeliveryException("SES send failed: " + e.getMessage(), e);
        }
    }
}
