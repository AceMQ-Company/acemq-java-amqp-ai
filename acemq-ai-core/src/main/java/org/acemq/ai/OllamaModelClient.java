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
 * Ollama's native {@code POST /api/chat}, not stream. Native rather than its OpenAI-compatible
 * endpoint because only the native one reports token counts and takes a schema in {@code format}.
 *
 * <p>Request options go into Ollama's {@code options} object ({@code seed}, {@code temperature},
 * {@code num_ctx}, ...), except {@code keep_alive} and {@code think}, which are top-level.
 */
final class OllamaModelClient extends HttpModelClient {

    static final String PROVIDER = "ollama";
    static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private final String model;

    OllamaModelClient(String model, URI baseUrl, String apiKey, Duration timeout) {
        super(PROVIDER, model, under(baseUrl, "/api/chat"), apiKey, timeout);
        this.model = model;
    }

    @Override
    ObjectNode body(ModelRequest request) {
        ObjectNode body = ModelRequest.JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        request.system().ifPresent(s -> messages.addObject().put("role", "system").put("content", s));
        for (ChatMessage m : request.messages()) {
            messages.addObject().put("role", m.role() == ChatMessage.Role.USER ? "user" : "assistant")
                    .put("content", m.text());
        }
        request.responseSchema().ifPresent(s -> body.set("format", schemaNode(s)));
        ObjectNode options = body.putObject("options");
        options.put("num_predict", request.maxOutputTokens());
        for (Map.Entry<String, Object> o : request.options().entrySet()) {
            JsonNode value = ModelRequest.JSON.valueToTree(o.getValue());
            if (o.getKey().equals("keep_alive") || o.getKey().equals("think")) {
                body.set(o.getKey(), value);
            } else {
                options.set(o.getKey(), value);
            }
        }
        return body;
    }

    @Override
    ModelResponse parse(JsonNode reply, Duration latency) {
        String reason = reply.path("done_reason").asText("stop");
        StopReason stop = reason.equals("stop") ? StopReason.END
                : reason.equals("length") ? StopReason.MAX_TOKENS : StopReason.OTHER;
        return new ModelResponse(
                reply.path("message").path("content").asText(""),
                Usage.of(reply.path("prompt_eval_count").asLong(0), reply.path("eval_count").asLong(0)),
                stop,
                reply.path("model").asText(model),
                latency);
    }

    @Override
    ModelException error(int status, String body, String retryAfter) {
        // The one startup mistake worth a sentence of its own: a model named but never pulled.
        if (status == 404 && body != null && body.contains("not found")) {
            return new ModelException(ModelException.Kind.BAD_REQUEST, "Ollama at " + info().endpoint()
                    + " has no model '" + model + "'. Pull it first: ollama pull " + model);
        }
        return super.error(status, body, retryAfter);
    }
}
