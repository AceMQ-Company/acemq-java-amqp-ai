/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.ai;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code POST {base}/chat/completions}, the shape vLLM, LM Studio, the llama.cpp server,
 * Ollama's {@code /v1} and hosted OpenAI all speak. Request options become top-level fields
 * ({@code temperature}, {@code seed}, {@code reasoning_effort}, ...).
 *
 * <p>The output limit is sent as {@code max_completion_tokens} to api.openai.com, which has
 * deprecated {@code max_tokens}, and as {@code max_tokens} everywhere else, which is what the
 * compatible servers read.
 */
final class OpenAiCompatibleModelClient extends HttpModelClient {

    static final String PROVIDER = "openai";
    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    static final String DEFAULT_KEY_ENV = "OPENAI_API_KEY";

    private final String model;
    private final String limitField;

    OpenAiCompatibleModelClient(String model, URI baseUrl, String apiKey, Duration timeout) {
        super(PROVIDER, model, under(baseUrl, "/chat/completions"), apiKey, timeout);
        this.model = model;
        this.limitField = "api.openai.com".equalsIgnoreCase(baseUrl.getHost()) ? "max_completion_tokens" : "max_tokens";
    }

    @Override
    ObjectNode body(ModelRequest request) {
        ObjectNode body = ModelRequest.JSON.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        request.system().ifPresent(s -> messages.addObject().put("role", "system").put("content", s));
        for (ChatMessage m : request.messages()) {
            messages.addObject().put("role", m.role() == ChatMessage.Role.USER ? "user" : "assistant")
                    .put("content", m.text());
        }
        body.put(limitField, request.maxOutputTokens());
        request.responseSchema().ifPresent(s -> {
            ObjectNode format = body.putObject("response_format");
            format.put("type", "json_schema");
            format.putObject("json_schema").put("name", "response").set("schema", schemaNode(s));
        });
        for (Map.Entry<String, Object> o : request.options().entrySet()) {
            body.set(o.getKey(), ModelRequest.JSON.valueToTree(o.getValue()));
        }
        return body;
    }

    @Override
    ModelResponse parse(JsonNode reply, Duration latency) {
        JsonNode choice = reply.path("choices").path(0);
        JsonNode message = choice.path("message");
        String refusal = message.path("refusal").isTextual() ? message.path("refusal").asText() : null;
        String finish = choice.path("finish_reason").asText("stop");
        StopReason stop;
        if (refusal != null || finish.equals("content_filter")) {
            stop = StopReason.REFUSAL;
        } else if (finish.equals("stop")) {
            stop = StopReason.END;
        } else if (finish.equals("length")) {
            stop = StopReason.MAX_TOKENS;
        } else if (finish.equals("tool_calls")) {
            stop = StopReason.TOOL_USE;
        } else {
            stop = StopReason.OTHER;
        }
        JsonNode usage = reply.path("usage");
        return new ModelResponse(
                refusal != null ? refusal : message.path("content").asText(""),
                Usage.of(usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0),
                        usage.path("prompt_tokens_details").path("cached_tokens").asLong(0)),
                stop,
                reply.path("model").asText(model),
                latency);
    }
}
