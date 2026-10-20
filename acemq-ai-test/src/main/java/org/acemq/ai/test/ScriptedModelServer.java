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
package org.acemq.ai.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.acemq.ai.ModelException;
import org.acemq.ai.ModelRequest;
import org.acemq.ai.StopReason;

/**
 * A {@link ScriptedModel} over HTTP on loopback, speaking Ollama's {@code /api/chat} and the
 * OpenAI-compatible {@code /v1/chat/completions}. What the real adapters are tested against,
 * and the model peer the TCK runner plays.
 *
 * <pre>{@code
 * try (ScriptedModelServer server = ScriptedModelServer.start(ScriptedModel.create().reply("hi"))) {
 *     ModelClient ollama = ModelClients.ollama("scripted").baseUrl(server.url()).build();
 *     ModelClient compat = ModelClients.openAiCompatible("scripted").baseUrl(server.url() + "/v1").build();
 * }
 * }</pre>
 *
 * <p>A refusal is expressible only on the OpenAI-compatible path; Ollama's API has no such stop.
 */
public final class ScriptedModelServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ScriptedModel script;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "scripted-model-server");
        t.setDaemon(true);
        return t;
    });
    private final List<JsonNode> bodies = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();

    private ScriptedModelServer(ScriptedModel script) throws IOException {
        this.script = script;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/chat", ex -> handle(ex, false));
        server.createContext("/v1/chat/completions", ex -> handle(ex, true));
        server.setExecutor(executor);
        server.start();
    }

    public static ScriptedModelServer start(ScriptedModel script) {
        try {
            return new ScriptedModelServer(script);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the scripted model server", e);
        }
    }

    /** @return {@code http://127.0.0.1:<port>}; add {@code /v1} for the OpenAI-compatible shape */
    public String url() {
        return "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    /** @return every request body received, as JSON, in order */
    public synchronized List<JsonNode> bodies() {
        return Collections.unmodifiableList(new ArrayList<>(bodies));
    }

    /** @return the {@code Authorization} header of each request, or "" where there was none */
    public synchronized List<String> authorizations() {
        return Collections.unmodifiableList(new ArrayList<>(authorizations));
    }

    private void handle(HttpExchange ex, boolean openAi) throws IOException {
        try (HttpExchange e = ex) {
            JsonNode body;
            try (InputStream in = e.getRequestBody()) {
                body = JSON.readTree(in);
            }
            synchronized (this) {
                bodies.add(body);
                String auth = e.getRequestHeaders().getFirst("Authorization");
                authorizations.add(auth == null ? "" : auth);
            }
            ScriptedModel.Step step = script.next(toRequest(body));
            if (step.kind == ScriptedModel.Kind.HANG) {
                try {
                    Thread.sleep(step.duration.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (step.kind == ScriptedModel.Kind.ERROR) {
                error(e, step, openAi);
            } else {
                send(e, 200, openAi ? openAiReply(step) : ollamaReply(step));
            }
        }
    }

    private ObjectNode ollamaReply(ScriptedModel.Step step) {
        ObjectNode r = JSON.createObjectNode();
        r.put("model", script.model());
        r.putObject("message").put("role", "assistant").put("content", step.text);
        r.put("done", true);
        r.put("done_reason", step.stop == StopReason.MAX_TOKENS ? "length"
                : step.stop == StopReason.REFUSAL ? "refusal" : "stop");
        r.put("prompt_eval_count", step.usage.inputTokens());
        r.put("eval_count", step.usage.outputTokens());
        return r;
    }

    private ObjectNode openAiReply(ScriptedModel.Step step) {
        ObjectNode r = JSON.createObjectNode();
        r.put("object", "chat.completion");
        r.put("model", script.model());
        ObjectNode choice = r.putArray("choices").addObject();
        ObjectNode message = choice.putObject("message").put("role", "assistant");
        if (step.stop == StopReason.REFUSAL) {
            message.putNull("content");
            message.put("refusal", step.text);
        } else {
            message.put("content", step.text);
            message.putNull("refusal");
        }
        choice.put("finish_reason", step.stop == StopReason.MAX_TOKENS ? "length" : "stop");
        ObjectNode usage = r.putObject("usage");
        usage.put("prompt_tokens", step.usage.inputTokens());
        usage.put("completion_tokens", step.usage.outputTokens());
        usage.put("total_tokens", step.usage.total());
        usage.putObject("prompt_tokens_details").put("cached_tokens", step.usage.cachedInputTokens());
        return r;
    }

    private void error(HttpExchange e, ScriptedModel.Step step, boolean openAi) throws IOException {
        int status = statusOf(step);
        if (step.error == ModelException.Kind.RATE_LIMITED && step.duration != null) {
            e.getResponseHeaders().add("retry-after", String.valueOf(step.duration.toMillis() / 1000.0));
        }
        ObjectNode body = JSON.createObjectNode();
        if (openAi) {
            ObjectNode err = body.putObject("error").put("message", step.detail);
            if (step.error == ModelException.Kind.CONTEXT_TOO_LONG) {
                err.put("code", "context_length_exceeded");
            }
        } else {
            body.put("error", step.detail);
        }
        send(e, status, body);
    }

    private static int statusOf(ScriptedModel.Step step) {
        switch (step.error) {
            case RATE_LIMITED:
                return 429;
            case OVERLOADED:
                return 503;
            case AUTH:
                return 401;
            case BAD_REQUEST:
                return step.detail.contains("not found") ? 404 : 400;
            case CONTEXT_TOO_LONG:
                return 400;
            default:
                return 500;
        }
    }

    private static void send(HttpExchange e, int status, JsonNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().add("Content-Type", "application/json");
        e.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Enough of the request to assert on: the turns and the output limit. */
    private static ModelRequest toRequest(JsonNode body) {
        ModelRequest.Builder b = ModelRequest.builder();
        for (JsonNode m : body.path("messages")) {
            String role = m.path("role").asText();
            String content = m.path("content").asText("");
            if (role.equals("system")) {
                b.system(content);
            } else if (role.equals("assistant")) {
                b.assistant(content);
            } else {
                b.user(content);
            }
        }
        int limit = body.path("options").path("num_predict").asInt(
                body.path("max_tokens").asInt(body.path("max_completion_tokens").asInt(1024)));
        b.maxOutputTokens(Math.max(1, limit));
        if (body.path("messages").size() == 0) {
            b.user("");
        }
        return b.build();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
