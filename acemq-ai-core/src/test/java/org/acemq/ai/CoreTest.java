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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The parts with no network and no broker: classification, configuration, the guard, the budget. */
class CoreTest {

    @Nested
    class Classification {

        @Test
        void retries_only_what_can_clear_by_itself() {
            assertThat(ModelException.kindOf(429, "")).isEqualTo(ModelException.Kind.RATE_LIMITED);
            assertThat(ModelException.kindOf(529, "{\"type\":\"overloaded_error\"}"))
                    .isEqualTo(ModelException.Kind.OVERLOADED);
            assertThat(ModelException.kindOf(503, "")).isEqualTo(ModelException.Kind.OVERLOADED);
            assertThat(ModelException.kindOf(504, "")).isEqualTo(ModelException.Kind.TIMEOUT);
            assertThat(ModelException.kindOf(500, "")).isEqualTo(ModelException.Kind.UNAVAILABLE);
            assertThat(ModelException.kindOf(401, "")).isEqualTo(ModelException.Kind.AUTH);
            assertThat(ModelException.kindOf(400, "{\"code\":\"context_length_exceeded\"}"))
                    .isEqualTo(ModelException.Kind.CONTEXT_TOO_LONG);
            assertThat(ModelException.kindOf(400, "prompt is too long: 1200000 tokens"))
                    .isEqualTo(ModelException.Kind.CONTEXT_TOO_LONG);
            assertThat(ModelException.kindOf(400, "bad")).isEqualTo(ModelException.Kind.BAD_REQUEST);
            assertThat(ModelException.kindOf(404, "")).isEqualTo(ModelException.Kind.BAD_REQUEST);

            assertThat(ModelException.Kind.RATE_LIMITED.isRetryable()).isTrue();
            assertThat(ModelException.Kind.TIMEOUT.isRetryable()).isTrue();
            assertThat(ModelException.Kind.BAD_REQUEST.isRetryable()).isFalse();
            assertThat(ModelException.Kind.REFUSED.isRetryable()).isFalse();
            assertThat(ModelException.Kind.SCHEMA_MISMATCH.isRetryable()).isFalse();
        }

        @Test
        void reads_retry_after_as_seconds_or_a_date() {
            assertThat(ModelException.parseRetryAfter("7")).isEqualTo(Duration.ofSeconds(7));
            assertThat(ModelException.parseRetryAfter("1.5")).isEqualTo(Duration.ofMillis(1500));
            assertThat(ModelException.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT")).isEqualTo(Duration.ZERO);
            assertThat(ModelException.parseRetryAfter("soon")).isNull();
            assertThat(ModelException.parseRetryAfter(null)).isNull();
            assertThat(ModelException.fromHttpStatus("p", 429, "slow down", "3").retryAfter())
                    .contains(Duration.ofSeconds(3));
        }

        @Test
        void truncates_a_long_error_body() {
            String body = new String(new char[2000]).replace('\0', 'x');
            assertThat(ModelException.fromHttpStatus("p", 400, body, null).getMessage().length()).isLessThan(600);
        }
    }

    @Nested
    class Egress {

        private final List<String> none = Collections.emptyList();

        @Test
        void local_only_accepts_loopback_and_private_addresses() {
            for (String url : new String[] {"http://localhost:11434", "http://127.0.0.1:11434", "http://[::1]:8000",
                    "http://10.1.2.3:8000", "http://192.168.1.20", "http://172.20.0.5", "http://[fd00::5]"}) {
                EgressGuard.check(org.acemq.ai.Egress.LOCAL_ONLY, "ollama", URI.create(url), none);
            }
        }

        @Test
        void local_only_refuses_a_public_host_and_says_how_to_allow_it() {
            assertThatThrownBy(() -> EgressGuard.check(org.acemq.ai.Egress.LOCAL_ONLY, "openai",
                    URI.create("http://8.8.8.8/v1"), none))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("8.8.8.8")
                    .hasMessageContaining("acemq.ai.egress-allow=8.8.8.8");
        }

        @Test
        void local_only_refuses_a_name_that_does_not_resolve() {
            assertThatThrownBy(() -> EgressGuard.check(org.acemq.ai.Egress.LOCAL_ONLY, "openai",
                    URI.create("https://nowhere.invalid/v1"), none))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void an_allowed_host_passes_and_any_checks_nothing() {
            EgressGuard.check(org.acemq.ai.Egress.LOCAL_ONLY, "openai", URI.create("http://8.8.8.8/v1"),
                    Collections.singletonList("8.8.8.8"));
            EgressGuard.check(org.acemq.ai.Egress.ANY, "openai", URI.create("http://8.8.8.8/v1"), none);
        }

        @Test
        void the_builders_apply_it() {
            assertThatThrownBy(() -> ModelClients.openAiCompatible("m").baseUrl("http://8.8.8.8/v1")
                    .egress(org.acemq.ai.Egress.LOCAL_ONLY).build())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(ModelClients.ollama("m").egress(org.acemq.ai.Egress.LOCAL_ONLY).build().info().isLocal()).isTrue();
        }

        @Test
        void parses_the_setting() {
            assertThat(org.acemq.ai.Egress.parse("local-only")).isEqualTo(org.acemq.ai.Egress.LOCAL_ONLY);
            assertThat(org.acemq.ai.Egress.parse("LOCAL_ONLY")).isEqualTo(org.acemq.ai.Egress.LOCAL_ONLY);
            assertThat(org.acemq.ai.Egress.parse("any")).isEqualTo(org.acemq.ai.Egress.ANY);
            assertThatThrownBy(() -> org.acemq.ai.Egress.parse("offline")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Configuration {

        private final Map<String, String> env = new HashMap<>();

        private ModelConfig config(String... kv) {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put(kv[i], kv[i + 1]);
            }
            return ModelConfig.from(m, env::get);
        }

        @Test
        void defaults_to_ollama_and_requires_a_model() {
            ModelConfig c = config();
            assertThat(c.provider()).isEqualTo("ollama");
            assertThat(c.egress()).isEqualTo(org.acemq.ai.Egress.ANY);
            assertThat(c.timeout()).isEqualTo(Duration.ofSeconds(120));
            assertThatThrownBy(c::model).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("acemq.ai.model");
        }

        @Test
        void reads_the_key_from_the_named_variable_and_never_prints_it() {
            env.put("MY_KEY", "sk-secret-value");
            ModelConfig c = config("acemq.ai.provider", "openai", "acemq.ai.model", "m",
                    "acemq.ai.api-key-env", "MY_KEY", "acemq.ai.timeout", "PT30S");
            assertThat(c.apiKey("OPENAI_API_KEY")).contains("sk-secret-value");
            assertThat(c.timeout()).isEqualTo(Duration.ofSeconds(30));

            ModelConfig direct = config("acemq.ai.api-key", "sk-secret-value");
            assertThat(direct.toString()).doesNotContain("sk-secret-value").contains("<set>");
        }

        @Test
        void falls_back_to_the_provider_variable() {
            env.put("OPENAI_API_KEY", "k");
            assertThat(config().apiKey("OPENAI_API_KEY")).contains("k");
            assertThat(config().apiKey(null)).isEmpty();
        }

        @Test
        void builds_the_built_in_providers_by_name() {
            ModelClient ollama = ModelClients.create(config("acemq.ai.model", "qwen3-coder:30b",
                    "acemq.ai.egress", "local-only"));
            assertThat(ollama.info().provider()).isEqualTo("ollama");
            assertThat(ollama.info().endpoint()).isEqualTo(URI.create("http://localhost:11434/api/chat"));

            ModelClient vllm = ModelClients.create(config("acemq.ai.provider", "openai", "acemq.ai.model", "m",
                    "acemq.ai.base-url", "http://10.0.0.7:8000/v1/"));
            assertThat(vllm.info().endpoint()).isEqualTo(URI.create("http://10.0.0.7:8000/v1/chat/completions"));

            assertThatThrownBy(() -> ModelClients.create(config("acemq.ai.provider", "nope", "acemq.ai.model", "m")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ollama");
        }

        @Test
        void hosted_openai_without_a_key_fails_at_startup() {
            assertThatThrownBy(() -> ModelClients.openAiCompatible("m").apiKeyFromEnv("ACEMQ_TEST_UNSET_KEY").build())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("ACEMQ_TEST_UNSET_KEY");
        }
    }

    @Nested
    class Responses {

        @Test
        void parses_json_and_strips_a_code_fence() {
            ModelResponse r = new ModelResponse("```json\n{\"queue\":\"billing\",\"priority\":2}\n```",
                    Usage.of(10, 5), StopReason.END, "m", Duration.ZERO);
            assertThat(r.as(Map.class)).containsEntry("queue", "billing");
        }

        @Test
        void a_reply_that_does_not_parse_is_a_schema_mismatch() {
            ModelResponse r = new ModelResponse("I think billing", Usage.NONE, StopReason.END, "m", Duration.ZERO);
            assertThatThrownBy(() -> r.as(Map.class)).isInstanceOf(ModelException.class)
                    .satisfies(e -> assertThat(((ModelException) e).kind())
                            .isEqualTo(ModelException.Kind.SCHEMA_MISMATCH));
        }

        @Test
        void a_request_rejects_a_schema_that_is_not_json() {
            assertThatThrownBy(() -> ModelRequest.builder().responseSchema("{not json")).isInstanceOf(
                    IllegalArgumentException.class);
            assertThatThrownBy(() -> ModelRequest.builder().build()).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void a_follow_up_keeps_everything_and_appends() {
            ModelRequest r = ModelRequest.builder().system("s").user("u").maxOutputTokens(9).option("seed", 1).build();
            ModelRequest f = r.followUp(ChatMessage.assistant("a"), ChatMessage.user("again"));
            assertThat(f.messages()).hasSize(3);
            assertThat(f.system()).contains("s");
            assertThat(f.maxOutputTokens()).isEqualTo(9);
            assertThat(f.options()).containsEntry("seed", 1);
            assertThat(r.toString()).doesNotContain("again");
        }
    }

    @Nested
    class Budget {

        @Test
        void pauses_below_the_reservation_and_resumes_when_the_window_refills() throws Exception {
            TokenBudget budget = TokenBudget.per(Duration.ofMillis(300), 1_000);
            Pauser pauser = new Pauser("q");
            budget.attach(pauser);

            budget.record(Usage.of(300, 100), 200);
            assertThat(budget.isPaused()).isFalse();
            assertThat(budget.remaining()).isEqualTo(600);

            budget.record(Usage.of(300, 200), 200);  // 100 left, cannot cover 200
            assertThat(budget.isPaused()).isTrue();
            assertThat(pauser.isPaused(TokenBudget.REASON)).isTrue();

            long deadline = System.currentTimeMillis() + 5_000;
            while (budget.isPaused() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(budget.isPaused()).isFalse();
            assertThat(pauser.isPaused(TokenBudget.REASON)).isFalse();
            assertThat(budget.remaining()).isEqualTo(1_000);
        }

        @Test
        void a_rate_limit_ending_does_not_resume_what_the_budget_holds() {
            Pauser pauser = new Pauser("q");
            pauser.pause(TokenBudget.REASON);
            pauser.pause(AiHandler.RATE_LIMITED);
            pauser.resume(AiHandler.RATE_LIMITED);
            assertThat(pauser.isPaused(TokenBudget.REASON)).isTrue();
        }
    }
}
