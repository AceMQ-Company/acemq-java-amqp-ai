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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelRequest;
import org.acemq.ai.ModelResponse;
import org.acemq.ai.StopReason;
import org.junit.jupiter.api.Test;

/**
 * One real call, run only with {@code mvn verify -Plive} and only when
 * {@code ANTHROPIC_API_KEY} is set. The cheapest current model by default; override with
 * {@code ACEMQ_AI_LIVE_ANTHROPIC_MODEL}. The key is never printed.
 */
class AnthropicLiveIT {

    @Test
    void answers_in_the_schema() {
        String key = System.getenv(AnthropicModelClient.DEFAULT_KEY_ENV);
        assumeTrue(key != null && !key.trim().isEmpty(), "ANTHROPIC_API_KEY is not set");
        String model = System.getenv().getOrDefault("ACEMQ_AI_LIVE_ANTHROPIC_MODEL", "claude-haiku-4-5-20251001");

        try (ModelClient c = AnthropicModelClient.builder(model).build()) {
            ModelResponse reply = c.complete(ModelRequest.builder()
                    .user("What is 2 + 3?")
                    .responseSchema("{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"integer\"}},"
                            + "\"required\":[\"answer\"],\"additionalProperties\":false}")
                    .maxOutputTokens(1_000)
                    .build());

            assertThat(reply.stopReason()).isEqualTo(StopReason.END);
            assertThat(((Number) reply.as(Map.class).get("answer")).intValue()).isEqualTo(5);
            assertThat(reply.usage().inputTokens()).isPositive();
        }
    }
}
