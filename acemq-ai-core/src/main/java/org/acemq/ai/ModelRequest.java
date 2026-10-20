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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What to ask a model. Immutable; build one with {@link #builder()}.
 *
 * <p>Provider-neutral on purpose. Anything one provider has and the others do not goes through
 * {@link Builder#option(String, Object)}, which each adapter documents.
 */
public final class ModelRequest {

    static final ObjectMapper JSON = new ObjectMapper();

    private final String system;
    private final List<ChatMessage> messages;
    private final int maxOutputTokens;
    private final String responseSchema;
    private final Duration timeout;
    private final Map<String, Object> options;

    private ModelRequest(Builder b) {
        this.system = b.system;
        this.messages = Collections.unmodifiableList(new ArrayList<>(b.messages));
        this.maxOutputTokens = b.maxOutputTokens;
        this.responseSchema = b.responseSchema;
        this.timeout = b.timeout;
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(b.options));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> system() {
        return Optional.ofNullable(system);
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    /** @return the JSON schema the reply must match, if one was asked for */
    public Optional<String> responseSchema() {
        return Optional.ofNullable(responseSchema);
    }

    /** @return this request's timeout, overriding the client's, if set */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /** @return provider-specific settings, passed through by the adapter that knows them */
    public Map<String, Object> options() {
        return options;
    }

    /**
     * The same request with more turns appended. How a repair attempt is asked: the model's
     * reply goes back as an assistant turn, followed by what was wrong with it.
     */
    public ModelRequest followUp(ChatMessage... more) {
        Builder b = toBuilder();
        b.messages.addAll(Arrays.asList(more));
        return b.build();
    }

    private Builder toBuilder() {
        Builder b = new Builder();
        b.system = system;
        b.messages.addAll(messages);
        b.maxOutputTokens = maxOutputTokens;
        b.responseSchema = responseSchema;
        b.timeout = timeout;
        b.options.putAll(options);
        return b;
    }

    @Override
    public String toString() {
        return "ModelRequest{messages=" + messages + ", maxOutputTokens=" + maxOutputTokens
                + ", schema=" + (responseSchema != null) + ", options=" + options.keySet() + "}";
    }

    /** Builds a {@link ModelRequest}. */
    public static final class Builder {

        private String system;
        private final List<ChatMessage> messages = new ArrayList<>();
        private int maxOutputTokens = 1024;
        private String responseSchema;
        private Duration timeout;
        private final Map<String, Object> options = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        /** Appends a user turn. */
        public Builder user(String text) {
            messages.add(ChatMessage.user(text));
            return this;
        }

        /** Appends a model turn, for few-shot examples or a conversation so far. */
        public Builder assistant(String text) {
            messages.add(ChatMessage.assistant(text));
            return this;
        }

        public Builder message(ChatMessage message) {
            messages.add(message);
            return this;
        }

        public Builder messages(List<ChatMessage> more) {
            messages.addAll(more);
            return this;
        }

        /**
         * The most the model may write. Also what a {@link TokenBudget} reserves for each
         * message in flight, so it is a cost ceiling as well as a length limit. Default 1024.
         */
        public Builder maxOutputTokens(int maxOutputTokens) {
            if (maxOutputTokens <= 0) {
                throw new IllegalArgumentException("maxOutputTokens must be positive: " + maxOutputTokens);
            }
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        /**
         * A JSON schema the reply must match. Sent to providers that enforce it (Ollama's
         * {@code format}, OpenAI-compatible {@code response_format}, hosted structured output),
         * and what {@link ModelResponse#as(Class)} parses against.
         *
         * @throws IllegalArgumentException if it is not JSON
         */
        public Builder responseSchema(String jsonSchema) {
            try {
                if (!JSON.readTree(jsonSchema).isObject()) {
                    throw new IllegalArgumentException("a response schema is a JSON object");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("the response schema is not valid JSON: " + e.getMessage(), e);
            }
            this.responseSchema = jsonSchema;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** A provider-specific setting, such as Ollama's {@code seed} or {@code temperature}. */
        public Builder option(String key, Object value) {
            options.put(key, value);
            return this;
        }

        public ModelRequest build() {
            if (messages.isEmpty()) {
                throw new IllegalStateException("a model request needs at least one message");
            }
            return new ModelRequest(this);
        }
    }
}
