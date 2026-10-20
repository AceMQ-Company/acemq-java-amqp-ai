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
import java.util.function.BooleanSupplier;

/**
 * Where model-call metrics go. Names and tags follow {@code MetricNames}:
 *
 * <ul>
 *   <li>{@value #REQUEST_DURATION} timer, tags {@code queue}, {@code provider}, {@code model},
 *       {@code outcome} = {@code answered} | {@code timed_out} | {@code refused} | {@code failed}
 *   <li>{@value #TOKENS} counter, same tags but {@code kind} = {@code input} | {@code output} |
 *       {@code cached_input} in place of {@code outcome}
 *   <li>{@value #BUDGET_PAUSED} gauge, tag {@code queue}: 1 while a {@link TokenBudget} holds it
 * </ul>
 *
 * <p>Prompts and replies are never recorded: they are customer data.
 */
public interface AiTelemetry {

    String REQUEST_DURATION = "acemq.ai.request.duration";
    String TOKENS = "acemq.ai.tokens";
    String BUDGET_PAUSED = "acemq.ai.budget.paused";

    /** Records nothing. */
    AiTelemetry NONE = (queue, model, outcome, latency, usage) -> { };

    void recordCall(String queue, ModelInfo model, String outcome, Duration latency, Usage usage);

    default void registerBudget(String queue, BooleanSupplier paused) {
    }

    /**
     * Micrometer's global registry when Micrometer is on the classpath, as acemq-amqp-core
     * discovers it; otherwise {@link #NONE}.
     */
    static AiTelemetry defaults() {
        try {
            Class.forName("io.micrometer.core.instrument.Metrics", false, AiTelemetry.class.getClassLoader());
            return MicrometerAiTelemetry.global();
        } catch (ClassNotFoundException | LinkageError absent) {
            return NONE;
        }
    }
}
