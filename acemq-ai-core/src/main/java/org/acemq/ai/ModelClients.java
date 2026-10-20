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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeSet;

/**
 * Where model clients come from.
 *
 * <pre>{@code
 * // Configuration decides: Ollama on localhost unless acemq.ai.provider says otherwise.
 * ModelClient model = ModelClients.fromEnv();
 *
 * // Or in code, with nothing secret in it.
 * ModelClient local = ModelClients.ollama("qwen3-coder:30b").egress(Egress.LOCAL_ONLY).build();
 * ModelClient vllm  = ModelClients.openAiCompatible("my-model").baseUrl("http://gpu-box:8000/v1").build();
 * }</pre>
 *
 * <p>API keys come from configuration or the environment. The builders take a key so that a
 * configuration system can hand one over, and read the provider's usual variable when none is
 * given; a key written into source is a key in version control.
 */
public final class ModelClients {

    private ModelClients() {
    }

    /** A local or remote Ollama, {@code http://localhost:11434} unless {@link Builder#baseUrl} says otherwise. */
    public static Builder ollama(String model) {
        return new Builder(OllamaModelClient.PROVIDER, model, OllamaModelClient.DEFAULT_BASE_URL, null);
    }

    /**
     * Anything speaking {@code /chat/completions}: hosted OpenAI by default (key from
     * {@code OPENAI_API_KEY}), or vLLM, LM Studio, llama.cpp or Ollama's {@code /v1} with
     * {@link Builder#baseUrl}.
     */
    public static Builder openAiCompatible(String model) {
        return new Builder(OpenAiCompatibleModelClient.PROVIDER, model, OpenAiCompatibleModelClient.DEFAULT_BASE_URL,
                OpenAiCompatibleModelClient.DEFAULT_KEY_ENV);
    }

    /** The client {@code ACEMQ_AI_*} environment variables and {@code acemq.ai.*} system properties describe. */
    public static ModelClient fromEnv() {
        return create(ModelConfig.fromEnv());
    }

    /** The client a map of {@code acemq.ai.*} keys describes. See {@link ModelConfig}. */
    public static ModelClient fromConfig(Map<String, String> properties) {
        return create(ModelConfig.from(properties));
    }

    /**
     * @throws IllegalArgumentException if no provider module on the classpath answers to
     *     {@code acemq.ai.provider}
     */
    public static ModelClient create(ModelConfig config) {
        String wanted = config.provider();
        TreeSet<String> known = new TreeSet<>();
        for (ModelProvider provider : ServiceLoader.load(ModelProvider.class, ModelClients.class.getClassLoader())) {
            if (provider.name().equals(wanted)) {
                return provider.create(config);
            }
            known.add(provider.name());
        }
        throw new IllegalArgumentException("acemq.ai.provider=" + wanted + " but the providers on the classpath are "
                + known + ". A hosted provider has its own module; add it as a dependency.");
    }

    /** Builds an HTTP model client. */
    public static final class Builder {

        private final String provider;
        private final String model;
        private final String defaultKeyEnv;
        private URI baseUrl;
        private String apiKey;
        private String apiKeyEnv;
        private Duration timeout = Duration.ofSeconds(120);
        private Egress egress = Egress.ANY;
        private final List<String> allowedHosts = new ArrayList<>();

        private Builder(String provider, String model, String baseUrl, String defaultKeyEnv) {
            if (model == null || model.trim().isEmpty()) {
                throw new IllegalArgumentException("a model name is required; none is built in");
            }
            this.provider = provider;
            this.model = model;
            this.baseUrl = URI.create(baseUrl);
            this.defaultKeyEnv = defaultKeyEnv;
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

        /** Read the key from this environment variable instead of the provider's usual one. */
        public Builder apiKeyFromEnv(String variable) {
            this.apiKeyEnv = variable;
            return this;
        }

        /** How long one call may take. Default 120 seconds; a request can set its own. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder egress(Egress egress) {
            this.egress = egress;
            return this;
        }

        /** Hosts {@link Egress#LOCAL_ONLY} accepts although they are not loopback or private. */
        public Builder allowHost(String... hosts) {
            allowedHosts.addAll(Arrays.asList(hosts));
            return this;
        }

        /**
         * @throws IllegalStateException if the egress guard refuses the endpoint, or hosted
         *     OpenAI has no key
         */
        public ModelClient build() {
            EgressGuard.check(egress, provider, baseUrl, allowedHosts);
            String key = apiKey;
            String var = apiKeyEnv != null ? apiKeyEnv : defaultKeyEnv;
            if (key == null && var != null) {
                String fromEnv = System.getenv(var);
                key = fromEnv == null || fromEnv.trim().isEmpty() ? null : fromEnv.trim();
            }
            if (provider.equals(OpenAiCompatibleModelClient.PROVIDER)) {
                if (key == null && "api.openai.com".equalsIgnoreCase(baseUrl.getHost())) {
                    throw new IllegalStateException("no API key for " + baseUrl + ": set " + var
                            + ", or acemq.ai.api-key-env to the variable that holds it");
                }
                return new OpenAiCompatibleModelClient(model, baseUrl, key, timeout);
            }
            return new OllamaModelClient(model, baseUrl, key, timeout);
        }
    }

    /** Applies a {@link ModelConfig} to a builder; what the built-in providers share. */
    static ModelClient build(Builder builder, ModelConfig config, String defaultKeyEnv) {
        config.baseUrl().ifPresent(builder::baseUrl);
        config.apiKey(defaultKeyEnv).ifPresent(builder::apiKey);
        String var = config.apiKeyEnv(defaultKeyEnv);
        if (var != null) {
            builder.apiKeyFromEnv(var);
        }
        return builder.timeout(config.timeout())
                .egress(config.egress())
                .allowHost(config.egressAllow().toArray(new String[0]))
                .build();
    }

    /** {@code acemq.ai.provider=ollama}, the default. */
    public static final class OllamaProvider implements ModelProvider {
        @Override
        public String name() {
            return OllamaModelClient.PROVIDER;
        }

        @Override
        public ModelClient create(ModelConfig config) {
            return build(ollama(config.model()), config, null);
        }
    }

    /** {@code acemq.ai.provider=openai}, for any {@code /chat/completions} server. */
    public static final class OpenAiProvider implements ModelProvider {
        @Override
        public String name() {
            return OpenAiCompatibleModelClient.PROVIDER;
        }

        @Override
        public ModelClient create(ModelConfig config) {
            return build(openAiCompatible(config.model()), config, OpenAiCompatibleModelClient.DEFAULT_KEY_ENV);
        }
    }
}
