package com.mycompany.customersapi.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;

import java.util.List;

/**
 * Sends prompts to a foundation model on AWS Bedrock through the Converse API.
 *
 * Credentials come from the AWS default chain (env vars, ~/.aws, `aws login`,
 * IAM role). The identity needs bedrock:InvokeModel on the configured model.
 */
@Service
@Slf4j
public class BedrockService {

    @Value("${aws.bedrock.region:us-east-1}")
    private String region;

    @Value("${aws.bedrock.model-id}")
    private String modelId;

    @Value("${aws.bedrock.max-tokens:1024}")
    private int maxTokens;

    private BedrockRuntimeClient client;

    @PostConstruct
    void init() {
        client = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        log.info("AWS Bedrock client initialised — region={}, model={}", region, modelId);
    }

    /** Sends a single user prompt and returns the model's text reply. */
    public String ask(String prompt) {
        return ask(null, prompt);
    }

    /**
     * @param systemPrompt optional instructions for the model (may be null/blank)
     * @param prompt       the user message
     * @throws BedrockException if Bedrock returns an error
     */
    public String ask(String systemPrompt, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        log.debug("Calling AWS Bedrock: model={}", modelId);
        try {
            ConverseResponse response = client.converse(r -> {
                r.modelId(modelId)
                        .messages(Message.builder()
                                .role(ConversationRole.USER)
                                .content(ContentBlock.fromText(prompt))
                                .build())
                        .inferenceConfig(c -> c.maxTokens(maxTokens));
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    r.system(SystemContentBlock.fromText(systemPrompt));
                }
            });
            return response.output().message().content().stream()
                    .map(ContentBlock::text)
                    .filter(t -> t != null)
                    .reduce("", String::concat);
        } catch (SdkException e) {
            log.error("AWS Bedrock error: {}", e.getMessage());
            throw new BedrockException("Bedrock invocation failed: " + e.getMessage(), e);
        }
    }

    /**
     * One Converse call with an explicit model and conversation, returning the whole response so the
     * caller can read tool requests and the stop reason (used by the agentic assistant).
     *
     * @param toolConfig tools the model may call; may be null
     * @throws BedrockException if Bedrock returns an error
     */
    public ConverseResponse converse(String modelId, String systemPrompt, List<Message> messages,
                                     ToolConfiguration toolConfig, int maxTokens) {
        log.debug("Calling AWS Bedrock Converse: model={}, messages={}", modelId, messages.size());
        try {
            return client.converse(r -> {
                r.modelId(modelId)
                        .messages(messages)
                        .inferenceConfig(c -> c.maxTokens(maxTokens));
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    r.system(SystemContentBlock.fromText(systemPrompt));
                }
                if (toolConfig != null) {
                    r.toolConfig(toolConfig);
                }
            });
        } catch (SdkException e) {
            log.error("AWS Bedrock error: {}", e.getMessage());
            throw new BedrockException("Bedrock invocation failed: " + e.getMessage(), e);
        }
    }

    public static class BedrockException extends RuntimeException {
        public BedrockException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
