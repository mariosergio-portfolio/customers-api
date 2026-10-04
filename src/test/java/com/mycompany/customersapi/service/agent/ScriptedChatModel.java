package com.mycompany.customersapi.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * A model that replays a script, so the agent loop can be tested without calling Bedrock. Each reply is a
 * {@link ChatResponse} to return or a {@link RuntimeException} to throw; the last one repeats when the script
 * runs out. Every request the service sends is recorded.
 */
final class ScriptedChatModel implements ChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Queue<Object> script = new LinkedList<>();
    private final List<ChatRequest> requests = new ArrayList<>();

    ScriptedChatModel(Object... replies) {
        script.addAll(List.of(replies));
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        Object reply = script.size() > 1 ? script.poll() : script.peek();
        if (reply instanceof RuntimeException e) {
            throw e;
        }
        if (reply instanceof ChatResponse response) {
            return response;
        }
        throw new IllegalStateException("The scripted model has no reply left");
    }

    List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    /** The tool result the service sent back in the n-th model call (1-based). */
    ToolExecutionResultMessage toolResultSentInCall(int call) {
        ChatMessage last = requests.get(call - 1).messages().getLast();
        if (last instanceof ToolExecutionResultMessage result) {
            return result;
        }
        throw new AssertionError("Call " + call + " did not end with a tool result: " + last);
    }

    // ── replies ──────────────────────────────────────────────────────────────

    static ChatResponse answer(String text) {
        return reply(AiMessage.from(text), FinishReason.STOP);
    }

    static ChatResponse cutOff(String text) {
        return reply(AiMessage.from(text), FinishReason.LENGTH);
    }

    /** The model asks for one tool call; {@code arguments} is what it sends, as a JSON object. */
    static ChatResponse toolCall(String note, String id, String tool, String arguments) {
        return toolCalls(note, request(id, tool, arguments));
    }

    static ChatResponse toolCalls(String note, ToolExecutionRequest... requests) {
        AiMessage message = note == null ? AiMessage.from(List.of(requests)) : AiMessage.from(note, List.of(requests));
        return reply(message, FinishReason.TOOL_EXECUTION);
    }

    static ToolExecutionRequest request(String id, String tool, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(tool).arguments(arguments).build();
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ChatResponse reply(AiMessage message, FinishReason reason) {
        return ChatResponse.builder().aiMessage(message).finishReason(reason).build();
    }
}
