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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

/** {@link AiTelemetry} into a Micrometer registry. Loaded only when Micrometer is present. */
public final class MicrometerAiTelemetry implements AiTelemetry {

    private final MeterRegistry registry;

    private MicrometerAiTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    public static AiTelemetry of(MeterRegistry registry) {
        return new MicrometerAiTelemetry(registry);
    }

    static AiTelemetry global() {
        return new MicrometerAiTelemetry(Metrics.globalRegistry);
    }

    @Override
    public void recordCall(String queue, ModelInfo model, String outcome, Duration latency, Usage usage) {
        Timer.builder(REQUEST_DURATION)
                .tags("queue", queue, "provider", model.provider(), "model", model.model(), "outcome", outcome)
                .register(registry)
                .record(latency);
        count(queue, model, "input", usage.inputTokens());
        count(queue, model, "output", usage.outputTokens());
        count(queue, model, "cached_input", usage.cachedInputTokens());
    }

    private void count(String queue, ModelInfo model, String kind, long tokens) {
        if (tokens > 0) {
            Counter.builder(TOKENS)
                    .tags("queue", queue, "provider", model.provider(), "model", model.model(), "kind", kind)
                    .register(registry)
                    .increment(tokens);
        }
    }

    @Override
    public void registerBudget(String queue, BooleanSupplier paused) {
        Gauge.builder(BUDGET_PAUSED, paused, p -> p.getAsBoolean() ? 1 : 0)
                .tag("queue", queue)
                .register(registry);
    }
}
