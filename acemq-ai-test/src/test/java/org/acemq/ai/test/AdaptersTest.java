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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelClients;
import org.acemq.ai.ModelException;
import org.acemq.ai.ModelRequest;
import org.acemq.ai.ModelResponse;
import org.acemq.ai.StopReason;
import org.junit.jupiter.api.Test;

/** The two HTTP adapters against the scripted server: the wire shape out, the classification back. */
class AdaptersTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"queue\":{\"type\":\"string\"}}}";

    private static ModelRequest request() {
        return ModelRequest.builder().system("be brief").user("route this").maxOutputTokens(64)
                .responseSchema(SCHEMA).option("seed", 7).build();
    }

    private static ModelException.Kind kindOf(ModelClient client) {
        try {
            client.complete(request());
            throw new AssertionError("expected a ModelException");
        } catch (ModelException e) {
            return e.kind();
        }
    }

    @Test
    void ollama_sends_the_native_shape_and_reads_token_counts() {
        try (ScriptedModelServer server = ScriptedModelServer.start(
                ScriptedModel.named("qwen3-coder:30b").reply("{\"queue\":\"billing\"}", 120, 8))) {
            ModelClient ollama = ModelClients.ollama("qwen3-coder:30b").baseUrl(server.url()).build();

            ModelResponse reply = ollama.complete(request());

            assertThat(reply.text()).isEqualTo("{\"queue\":\"billing\"}");
            assertThat(reply.usage().inputTokens()).isEqualTo(120);
            assertThat(reply.usage().outputTokens()).isEqualTo(8);
            assertThat(reply.stopReason()).isEqualTo(StopReason.END);
            assertThat(reply.model()).isEqualTo("qwen3-coder:30b");

            JsonNode sent = server.bodies().get(0);
            assertThat(sent.path("stream").asBoolean(true)).isFalse();
            assertThat(sent.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(sent.path("format").path("type").asText()).isEqualTo("object");
            assertThat(sent.path("options").path("num_predict").asInt()).isEqualTo(64);
            assertThat(sent.path("options").path("seed").asInt()).isEqualTo(7);
            assertThat(server.authorizations().get(0)).isEmpty();
        }
    }

    @Test
    void ollama_names_the_pull_command_for_a_missing_model() {
        try (ScriptedModelServer server = ScriptedModelServer.start(ScriptedModel.named("gpt-oss:20b").modelNotFound())) {
            ModelClient ollama = ModelClients.ollama("gpt-oss:20b").baseUrl(server.url()).build();
            assertThatThrownBy(() -> ollama.complete(request()))
                    .isInstanceOf(ModelException.class)
                    .hasMessageContaining("ollama pull gpt-oss:20b");
        }
    }

    @Test
    void ollama_classifies_failures() {
        try (ScriptedModelServer server = ScriptedModelServer.start(ScriptedModel.create()
                .rateLimited(Duration.ofSeconds(2)).overloaded().badRequest("bad").unavailable()
                .timeout(Duration.ofSeconds(3)).maxTokens("cut"))) {
            ModelClient ollama = ModelClients.ollama("scripted").baseUrl(server.url())
                    .timeout(Duration.ofMillis(500)).build();

            assertThatThrownBy(() -> ollama.complete(request())).isInstanceOfSatisfying(ModelException.class, e -> {
                assertThat(e.kind()).isEqualTo(ModelException.Kind.RATE_LIMITED);
                assertThat(e.retryAfter()).contains(Duration.ofSeconds(2));
            });
            assertThat(kindOf(ollama)).isEqualTo(ModelException.Kind.OVERLOADED);
            assertThat(kindOf(ollama)).isEqualTo(ModelException.Kind.BAD_REQUEST);
            assertThat(kindOf(ollama)).isEqualTo(ModelException.Kind.UNAVAILABLE);
            assertThat(kindOf(ollama)).isEqualTo(ModelException.Kind.TIMEOUT);
            assertThat(ollama.complete(request()).stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        }
    }

    @Test
    void an_unreachable_server_is_retryable() {
        ModelClient ollama = ModelClients.ollama("m").baseUrl("http://127.0.0.1:9").build();
        ModelException.Kind kind = kindOf(ollama);
        assertThat(kind).isEqualTo(ModelException.Kind.UNAVAILABLE);
        assertThat(kind.isRetryable()).isTrue();
    }

    @Test
    void openai_compatible_sends_its_shape_and_reads_cached_tokens() {
        try (ScriptedModelServer server = ScriptedModelServer.start(ScriptedModel.create().reply("ok", 50, 2))) {
            ModelClient compat = ModelClients.openAiCompatible("scripted").baseUrl(server.url() + "/v1")
                    .apiKey("test-key").build();

            ModelResponse reply = compat.complete(request());

            assertThat(reply.text()).isEqualTo("ok");
            assertThat(reply.usage().inputTokens()).isEqualTo(50);
            JsonNode sent = server.bodies().get(0);
            assertThat(sent.path("max_tokens").asInt()).isEqualTo(64);
            assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_schema");
            assertThat(sent.path("response_format").path("json_schema").path("schema").path("type").asText())
                    .isEqualTo("object");
            assertThat(sent.path("seed").asInt()).isEqualTo(7);
            assertThat(server.authorizations().get(0)).isEqualTo("Bearer test-key");
        }
    }

    @Test
    void openai_compatible_reports_a_refusal_and_classifies_failures() {
        try (ScriptedModelServer server = ScriptedModelServer.start(ScriptedModel.create()
                .refusal().contextTooLong().unauthorized().rateLimited(null))) {
            ModelClient compat = ModelClients.openAiCompatible("scripted").baseUrl(server.url() + "/v1").build();

            assertThat(compat.complete(request()).stopReason()).isEqualTo(StopReason.REFUSAL);
            assertThat(kindOf(compat)).isEqualTo(ModelException.Kind.CONTEXT_TOO_LONG);
            assertThat(kindOf(compat)).isEqualTo(ModelException.Kind.AUTH);
            assertThat(kindOf(compat)).isEqualTo(ModelException.Kind.RATE_LIMITED);
        }
    }

    @Test
    void the_in_process_script_runs_out_loudly() {
        ScriptedModel model = ScriptedModel.create().reply("once");
        model.complete(request());
        assertThatThrownBy(() -> model.complete(request())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repeat()");
        assertThat(model.callCount()).isEqualTo(2);
    }
}
