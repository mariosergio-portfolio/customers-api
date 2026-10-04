package com.mycompany.customersapi.config;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;

/**
 * The model the company agent talks to, through LangChain4j. Credentials come from the AWS default chain,
 * like {@link com.mycompany.customersapi.service.bedrock.BedrockService}; the identity needs
 * bedrock:InvokeModel on the model.
 */
@Configuration
public class AgentChatModelConfig {

    @Bean
    public ChatModel companyAgentChatModel(@Value("${aws.bedrock.region:us-east-1}") String region,
                                           @Value("${aws.bedrock.assistant-model-id}") String modelId,
                                           @Value("${aws.bedrock.agent-max-tokens:4096}") int maxTokens) {
        return BedrockChatModel.builder()
                .region(Region.of(region))
                .modelId(modelId)
                .defaultRequestParameters(ChatRequestParameters.builder().maxOutputTokens(maxTokens).build())
                .build();
    }
}
