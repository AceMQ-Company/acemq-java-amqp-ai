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

import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.ai.ChatMessage;
import org.acemq.ai.Egress;
import org.acemq.ai.EgressGuard;
import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelException;
import org.acemq.ai.ModelInfo;
import org.acemq.ai.ModelRequest;
import org.acemq.ai.ModelResponse;
import org.acemq.ai.StopReason;
import org.acemq.ai.Usage;

/**
 * The hosted Messages API through the provider's official Java SDK.
 *
 * <pre>{@code
 * // Key from ANTHROPIC_API_KEY; model names are configuration, never a default.
 * ModelClient hosted = AnthropicModelClient.builder("claude-opus-5-5").effort("medium").build();
 * }</pre>
 *
 * <p>The SDK's own retries are set to zero: the broker's retry ladder owns retrying, and two
 * retry loops stacked multiply the calls a bad hour costs.
 *
 * <p>Only one request option is read: {@code effort} ({@code low} to {@code max}). Sampling
 * options such as {@code temperature} are not sent, because current models reject them.
 * A response schema goes out as structured output ({@code output_config.format}).
 */
public final class AnthropicModelClient implements ModelClient {

    public static final String PROVIDER = "anthropic";
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_KEY_ENV = "ANTHROPIC_API_KEY";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnthropicClient client;
    private final String model;
    private final String effort;
    private final ModelInfo info;

    private AnthropicModelClient(Builder b, String apiKey) {
        this.model = b.model;
        this.effort = b.effort;
        this.info = new ModelInfo(PROVIDER, b.model, b.baseUrl);
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(b.baseUrl.toString())
                .maxRetries(0)
                .timeout(b.timeout)
                .build();
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxOutputTokens());
        request.system().ifPresent(params::system);
        for (ChatMessage m : request.messages()) {
            if (m.role() == ChatMessage.Role.USER) {
                params.addUserMessage(m.text());
            } else {
                params.addAssistantMessage(m.text());
            }
        }
        Object requestedEffort = request.options().get("effort");
        String chosenEffort = requestedEffort != null ? requestedEffort.toString() : effort;
        if (chosenEffort != null || request.responseSchema().isPresent()) {
            OutputConfig.Builder output = OutputConfig.builder();
            if (chosenEffort != null) {
                output.effort(OutputConfig.Effort.of(chosenEffort));
            }
            request.responseSchema().ifPresent(s -> output.format(JsonOutputFormat.builder().schema(schema(s)).build()));
            params.outputConfig(output.build());
        }
        RequestOptions options = request.timeout()
                .map(t -> RequestOptions.builder().timeout(t).build())
                .orElse(RequestOptions.none());

        long start = System.nanoTime();
        Message reply;
        try {
            reply = client.messages().create(params.build(), options);
        } catch (AnthropicServiceException e) {
            List<String> retryAfter = e.headers().values("retry-after");
            throw ModelException.fromHttpStatus(PROVIDER, e.statusCode(), e.getMessage(),
                    retryAfter.isEmpty() ? null : retryAfter.get(0));
        } catch (AnthropicException e) {
            boolean timedOut = false;
            for (Throwable t = e; t != null; t = t.getCause()) {
                timedOut |= t instanceof InterruptedIOException;
            }
            throw new ModelException(timedOut ? ModelException.Kind.TIMEOUT : ModelException.Kind.UNAVAILABLE,
                    (timedOut ? "no answer from " : "cannot reach ") + PROVIDER + " at " + info.endpoint() + ": " + e, e);
        }
        Duration latency = Duration.ofNanos(System.nanoTime() - start);

        String text = reply.content().stream()
                .flatMap(block -> block.text().map(java.util.stream.Stream::of).orElseGet(java.util.stream.Stream::empty))
                .map(TextBlock::text)
                .collect(Collectors.joining());
        com.anthropic.models.messages.Usage u = reply.usage();
        long cacheRead = u.cacheReadInputTokens().orElse(0L);
        long cacheWrite = u.cacheCreationInputTokens().orElse(0L);
        return new ModelResponse(text,
                // The API counts cached input separately; here it is part of the input.
                Usage.of(u.inputTokens() + cacheRead + cacheWrite, u.outputTokens(), cacheRead),
                stopReason(reply),
                reply.model().asString(),
                latency);
    }

    private static StopReason stopReason(Message reply) {
        if (!reply.stopReason().isPresent()) {
            return StopReason.OTHER;
        }
        com.anthropic.models.messages.StopReason s = reply.stopReason().get();
        if (s.equals(com.anthropic.models.messages.StopReason.END_TURN)
                || s.equals(com.anthropic.models.messages.StopReason.STOP_SEQUENCE)) {
            return StopReason.END;
        }
        if (s.equals(com.anthropic.models.messages.StopReason.MAX_TOKENS)) {
            return StopReason.MAX_TOKENS;
        }
        if (s.equals(com.anthropic.models.messages.StopReason.REFUSAL)) {
            return StopReason.REFUSAL;
        }
        if (s.equals(com.anthropic.models.messages.StopReason.TOOL_USE)) {
            return StopReason.TOOL_USE;
        }
        return StopReason.OTHER;
    }

    @SuppressWarnings("unchecked")
    private static JsonOutputFormat.Schema schema(String json) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        try {
            Map<String, Object> fields = JSON.readValue(json, Map.class);
            fields.forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);  // validated when the request was built
        }
        return schema.build();
    }

    @Override
    public ModelInfo info() {
        return info;
    }

    @Override
    public void close() {
        client.close();
    }

    /** Builds an {@link AnthropicModelClient}. */
    public static final class Builder {

        private final String model;
        private URI baseUrl = URI.create(DEFAULT_BASE_URL);
        private String apiKey;
        private String apiKeyEnv = DEFAULT_KEY_ENV;
        private Duration timeout = Duration.ofSeconds(120);
        private Egress egress = Egress.ANY;
        private final List<String> allowedHosts = new ArrayList<>();
        private String effort;

        private Builder(String model) {
            if (model == null || model.trim().isEmpty()) {
                throw new IllegalArgumentException("a model name is required; none is built in");
            }
            this.model = model;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = URI.create(baseUrl);
            return this;
        }

        /** The key, as your configuration holds it. Never a literal in source. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Read the key from this variable instead of {@code ANTHROPIC_API_KEY}. */
        public Builder apiKeyFromEnv(String variable) {
            this.apiKeyEnv = variable;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder egress(Egress egress) {
            this.egress = egress;
            return this;
        }

        public Builder allowHost(String... hosts) {
            allowedHosts.addAll(Arrays.asList(hosts));
            return this;
        }

        /** {@code low}, {@code medium}, {@code high}, {@code xhigh} or {@code max}; the model's default if unset. */
        public Builder effort(String effort) {
            this.effort = effort;
            return this;
        }

        /** @throws IllegalStateException if the egress guard refuses the endpoint, or there is no key */
        public AnthropicModelClient build() {
            EgressGuard.check(egress, PROVIDER, baseUrl, allowedHosts);
            String key = apiKey;
            if (key == null) {
                String fromEnv = System.getenv(apiKeyEnv);
                key = fromEnv == null || fromEnv.trim().isEmpty() ? null : fromEnv.trim();
            }
            if (key == null) {
                throw new IllegalStateException("no API key for " + baseUrl + ": set " + apiKeyEnv
                        + ", or acemq.ai.api-key-env to the variable that holds it");
            }
            return new AnthropicModelClient(this, key);
        }
    }
}
