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
package org.acemq.ai.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.acemq.ai.Egress;
import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelClients;
import org.acemq.ai.ModelException;
import org.acemq.ai.ModelRequest;
import org.acemq.ai.ModelResponse;
import org.acemq.ai.StopReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The SDK adapter against a local stand-in for the Messages API. No real call, no real key. */
class AnthropicModelClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FAKE_KEY = "test-key-not-real";

    private HttpServer server;
    private final Deque<String[]> replies = new ArrayDeque<>();  // {status, retry-after, body}
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", ex -> {
            try (InputStream in = ex.getRequestBody()) {
                bodies.add(JSON.readTree(in));
            }
            String key = ex.getRequestHeaders().getFirst("x-api-key");
            keys.add(key == null ? "" : key);
            String[] r = replies.poll();
            if (r[1] != null) {
                ex.getResponseHeaders().add("retry-after", r[1]);
            }
            byte[] bytes = r[2].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(Integer.parseInt(r[0]), bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String message(String text, String stopReason) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-4-5-20251001\","
                + "\"content\":[{\"type\":\"text\",\"text\":" + JSON.valueToTree(text) + "}],"
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":30,\"output_tokens\":4,\"cache_read_input_tokens\":70,"
                + "\"cache_creation_input_tokens\":0}}";
    }

    private static String error(String type) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + type + "\"}}";
    }

    private ModelClient client() {
        return AnthropicModelClient.builder("claude-haiku-4-5-20251001").baseUrl(url()).apiKey(FAKE_KEY)
                .effort("low").build();
    }

    private static ModelRequest request() {
        return ModelRequest.builder().system("be brief").user("route this").maxOutputTokens(64)
                .responseSchema("{\"type\":\"object\",\"properties\":{\"queue\":{\"type\":\"string\"}}}")
                .option("temperature", 0).build();
    }

    @Test
    void sends_the_request_and_counts_cached_input_as_input() {
        replies.add(new String[] {"200", null, message("{\"queue\":\"billing\"}", "end_turn")});

        ModelResponse reply = client().complete(request());

        assertThat(reply.text()).isEqualTo("{\"queue\":\"billing\"}");
        assertThat(reply.stopReason()).isEqualTo(StopReason.END);
        assertThat(reply.usage().inputTokens()).isEqualTo(100);
        assertThat(reply.usage().cachedInputTokens()).isEqualTo(70);
        assertThat(reply.usage().outputTokens()).isEqualTo(4);

        JsonNode sent = bodies.get(0);
        assertThat(sent.path("model").asText()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(64);
        assertThat(sent.path("system").asText()).isEqualTo("be brief");
        assertThat(sent.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(sent.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(sent.path("output_config").path("format").path("schema").path("type").asText()).isEqualTo("object");
        assertThat(sent.has("temperature")).isFalse();
        assertThat(keys.get(0)).isEqualTo(FAKE_KEY);
    }

    @Test
    void reports_a_refusal_and_a_cut_off_reply() {
        replies.add(new String[] {"200", null, message("", "refusal")});
        replies.add(new String[] {"200", null, message("bil", "max_tokens")});
        ModelClient c = client();
        assertThat(c.complete(request()).stopReason()).isEqualTo(StopReason.REFUSAL);
        assertThat(c.complete(request()).stopReason()).isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    void classifies_failures_and_never_retries_them_itself() {
        replies.add(new String[] {"429", "3", error("rate_limit_error")});
        replies.add(new String[] {"529", null, error("overloaded_error")});
        replies.add(new String[] {"400", null, error("invalid_request_error")});
        replies.add(new String[] {"401", null, error("authentication_error")});
        ModelClient c = client();

        assertThatThrownBy(() -> c.complete(request())).isInstanceOfSatisfying(ModelException.class, e -> {
            assertThat(e.kind()).isEqualTo(ModelException.Kind.RATE_LIMITED);
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(3));
            assertThat(e.getMessage()).doesNotContain(FAKE_KEY);
        });
        assertThatThrownBy(() -> c.complete(request())).isInstanceOfSatisfying(ModelException.class,
                e -> assertThat(e.kind()).isEqualTo(ModelException.Kind.OVERLOADED));
        assertThatThrownBy(() -> c.complete(request())).isInstanceOfSatisfying(ModelException.class,
                e -> assertThat(e.kind()).isEqualTo(ModelException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> c.complete(request())).isInstanceOfSatisfying(ModelException.class,
                e -> assertThat(e.kind()).isEqualTo(ModelException.Kind.AUTH));
        assertThat(bodies).hasSize(4);  // one request per call: the SDK retried nothing
    }

    @Test
    void local_only_refuses_the_hosted_endpoint_at_startup() {
        assertThatThrownBy(() -> AnthropicModelClient.builder("claude-opus-5-5").apiKey(FAKE_KEY)
                .egress(Egress.LOCAL_ONLY).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api.anthropic.com");
    }

    @Test
    void no_key_fails_at_startup_naming_the_variable() {
        assertThatThrownBy(() -> AnthropicModelClient.builder("claude-opus-5-5")
                .apiKeyFromEnv("ACEMQ_TEST_UNSET_KEY").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACEMQ_TEST_UNSET_KEY");
    }

    @Test
    void configuration_finds_it_by_name() {
        Map<String, String> config = new HashMap<>();
        config.put("acemq.ai.provider", "anthropic");
        config.put("acemq.ai.model", "claude-sonnet-5-5");
        config.put("acemq.ai.base-url", url());
        config.put("acemq.ai.api-key", FAKE_KEY);
        ModelClient c = ModelClients.fromConfig(config);
        assertThat(c.info().provider()).isEqualTo("anthropic");
        assertThat(c.info().model()).isEqualTo("claude-sonnet-5-5");
    }
}
