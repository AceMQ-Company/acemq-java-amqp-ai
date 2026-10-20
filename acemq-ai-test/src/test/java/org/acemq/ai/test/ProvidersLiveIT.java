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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.acemq.ai.Egress;
import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelClients;
import org.acemq.ai.ModelRequest;
import org.acemq.ai.ModelResponse;
import org.acemq.ai.StopReason;
import org.junit.jupiter.api.Test;

/**
 * Real models, run only with {@code mvn verify -Plive}. Each test skips itself when its model
 * is not available: Ollama needs to be running with a model already pulled (nothing is pulled
 * here), and the hosted test needs {@code OPENAI_API_KEY}. Keys are read from the environment
 * and never printed.
 */
class ProvidersLiveIT {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"integer\"}},\"required\":[\"answer\"]}";

    private static ModelRequest question() {
        return ModelRequest.builder()
                .system("Reply with JSON only.")
                .user("What is 2 + 3? Reply as {\"answer\": <number>}.")
                .responseSchema(SCHEMA)
                .maxOutputTokens(2_000)
                .option("temperature", 0)
                .timeout(Duration.ofMinutes(5))
                .build();
    }

    /** The model named by ACEMQ_AI_LIVE_OLLAMA_MODEL, or the smallest one installed. */
    private static String installedOllamaModel() {
        String named = System.getenv("ACEMQ_AI_LIVE_OLLAMA_MODEL");
        try {
            HttpResponse<String> tags = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:11434/api/tags"))
                            .timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String smallest = null;
            long size = Long.MAX_VALUE;
            for (JsonNode m : new ObjectMapper().readTree(tags.body()).path("models")) {
                if (m.path("name").asText().equals(named)) {
                    return named;
                }
                if (m.path("size").asLong(Long.MAX_VALUE) < size && !m.path("name").asText().contains("embed")) {
                    size = m.path("size").asLong();
                    smallest = m.path("name").asText();
                }
            }
            return named == null ? smallest : null;
        } catch (Exception notRunning) {
            return null;
        }
    }

    @Test
    void ollama_answers_in_the_schema() {
        String model = installedOllamaModel();
        assumeTrue(model != null, "no Ollama on localhost:11434 with a model pulled");

        ModelClient ollama = ModelClients.ollama(model).egress(Egress.LOCAL_ONLY).build();
        ModelResponse reply = ollama.complete(question());

        assertThat(reply.stopReason()).isEqualTo(StopReason.END);
        assertThat(((Number) reply.as(Map.class).get("answer")).intValue()).isEqualTo(5);
        assertThat(reply.usage().inputTokens()).isPositive();
        assertThat(reply.usage().outputTokens()).isPositive();
    }

    @Test
    void hosted_openai_answers_in_the_schema() {
        String key = System.getenv("OPENAI_API_KEY");
        assumeTrue(key != null && !key.trim().isEmpty(), "OPENAI_API_KEY is not set");
        String model = System.getenv().getOrDefault("ACEMQ_AI_LIVE_OPENAI_MODEL", "gpt-6.1-sol");

        ModelClient openai = ModelClients.openAiCompatible(model).build();
        ModelResponse reply = openai.complete(ModelRequest.builder()
                .system("Reply with JSON only.")
                .user("What is 2 + 3? Reply as {\"answer\": <number>}.")
                .maxOutputTokens(2_000)
                .build());

        assertThat(reply.stopReason()).isEqualTo(StopReason.END);
        assertThat(((Number) reply.as(Map.class).get("answer")).intValue()).isEqualTo(5);
    }
}
