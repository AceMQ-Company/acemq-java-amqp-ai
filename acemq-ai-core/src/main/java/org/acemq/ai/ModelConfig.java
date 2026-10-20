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

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A model client described by configuration rather than code: which provider, which model,
 * where, and which key.
 *
 * <p>Keys, all optional except {@code acemq.ai.model}:
 *
 * <pre>
 * acemq.ai.provider       ollama (default) | openai | any name a provider module registers
 * acemq.ai.model          required; no model name is built in
 * acemq.ai.base-url       the provider's endpoint; each provider has a default
 * acemq.ai.api-key        the key itself, for a configuration system that holds secrets
 * acemq.ai.api-key-env    the environment variable holding the key; each provider has a default
 * acemq.ai.egress         any (default) | local-only
 * acemq.ai.egress-allow   comma-separated hosts local-only also accepts
 * acemq.ai.timeout        seconds, or an ISO-8601 duration such as PT2M; default 120 seconds
 * acemq.ai.*              anything else is passed to the provider (for example acemq.ai.effort)
 * </pre>
 *
 * <p>{@link #fromEnv()} reads the same keys from the environment ({@code ACEMQ_AI_BASE_URL} is
 * {@code acemq.ai.base-url}), with system properties of the dotted name taking precedence.
 *
 * <p>An API key never appears in {@link #toString()}, in an exception message, or in a log line.
 */
public final class ModelConfig {

    static final String PREFIX = "acemq.ai.";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final Map<String, String> values;
    private final Function<String, String> env;

    private ModelConfig(Map<String, String> values, Function<String, String> env) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.env = env;
    }

    /** From a map of {@code acemq.ai.*} keys, such as a Spring {@code Environment} or a properties file. */
    public static ModelConfig from(Map<String, String> properties) {
        return from(properties, System::getenv);
    }

    static ModelConfig from(Map<String, String> properties, Function<String, String> env) {
        Map<String, String> values = new LinkedHashMap<>();
        properties.forEach((k, v) -> {
            if (k.startsWith(PREFIX) && v != null) {
                values.put(k, v.trim());
            }
        });
        return new ModelConfig(values, env);
    }

    /** From {@code ACEMQ_AI_*} environment variables and {@code acemq.ai.*} system properties. */
    public static ModelConfig fromEnv() {
        Map<String, String> values = new LinkedHashMap<>();
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("ACEMQ_AI_")) {
                values.put(PREFIX + k.substring("ACEMQ_AI_".length()).toLowerCase(Locale.ROOT).replace('_', '-'), v);
            }
        });
        System.getProperties().stringPropertyNames().stream()
                .filter(k -> k.startsWith(PREFIX))
                .forEach(k -> values.put(k, System.getProperty(k)));
        return from(values, System::getenv);
    }

    public String provider() {
        return get("provider").orElse("ollama").toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalStateException if no model is configured */
    public String model() {
        return get("model").orElseThrow(() -> new IllegalStateException(
                "acemq.ai.model is not set. No model name is built in, because model names change faster than "
                        + "releases do. Name one, for example acemq.ai.model=qwen3-coder:30b for a local Ollama."));
    }

    public Optional<String> baseUrl() {
        return get("base-url");
    }

    /**
     * The API key: {@code acemq.ai.api-key} if set, otherwise the environment variable named by
     * {@code acemq.ai.api-key-env}, otherwise {@code defaultEnvVar}.
     */
    public Optional<String> apiKey(String defaultEnvVar) {
        Optional<String> direct = get("api-key");
        if (direct.isPresent()) {
            return direct;
        }
        String var = get("api-key-env").orElse(defaultEnvVar);
        if (var == null) {
            return Optional.empty();
        }
        String key = env.apply(var);
        return key == null || key.trim().isEmpty() ? Optional.empty() : Optional.of(key.trim());
    }

    /** @return which variable {@link #apiKey(String)} would read, for an error message */
    public String apiKeyEnv(String defaultEnvVar) {
        return get("api-key-env").orElse(defaultEnvVar);
    }

    public Egress egress() {
        return get("egress").map(Egress::parse).orElse(Egress.ANY);
    }

    public List<String> egressAllow() {
        List<String> hosts = new ArrayList<>();
        get("egress-allow").ifPresent(v -> {
            for (String h : v.split(",")) {
                if (!h.trim().isEmpty()) {
                    hosts.add(h.trim());
                }
            }
        });
        return hosts;
    }

    public Duration timeout() {
        return get("timeout").map(ModelConfig::parseDuration).orElse(DEFAULT_TIMEOUT);
    }

    /** @return {@code acemq.ai.<name>}, for provider-specific settings */
    public Optional<String> get(String name) {
        String v = values.get(PREFIX + name);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v);
    }

    static Duration parseDuration(String v) {
        try {
            return Duration.ofMillis((long) (Double.parseDouble(v) * 1000));
        } catch (NumberFormatException notSeconds) {
            try {
                return Duration.parse(v);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("acemq.ai.timeout is seconds or an ISO-8601 duration, not '" + v + "'");
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("ModelConfig{");
        values.forEach((k, v) -> s.append(k).append('=').append(k.endsWith("api-key") ? "<set>" : v).append(", "));
        if (!values.isEmpty()) {
            s.setLength(s.length() - 2);
        }
        return s.append('}').toString();
    }
}
