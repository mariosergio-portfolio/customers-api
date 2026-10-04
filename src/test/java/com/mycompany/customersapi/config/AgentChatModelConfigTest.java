package com.mycompany.customersapi.config;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentChatModelConfigTest {

    @Test
    void should_build_a_bedrock_model_with_the_configured_id_and_token_limit() {
        ChatModel model = new AgentChatModelConfig().companyAgentChatModel("us-east-1", "amazon.nova-pro-v1:0", 2048);

        assertEquals("amazon.nova-pro-v1:0", model.defaultRequestParameters().modelName());
        assertEquals(2048, model.defaultRequestParameters().maxOutputTokens());
    }
}
