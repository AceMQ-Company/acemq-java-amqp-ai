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
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;

/** What a model said, and what it cost. */
public final class ModelResponse {

    private final String text;
    private final Usage usage;
    private final StopReason stopReason;
    private final String model;
    private final Duration latency;

    public ModelResponse(String text, Usage usage, StopReason stopReason, String model, Duration latency) {
        this.text = text == null ? "" : text;
        this.usage = Objects.requireNonNull(usage, "usage");
        this.stopReason = Objects.requireNonNull(stopReason, "stopReason");
        this.model = Objects.requireNonNull(model, "model");
        this.latency = Objects.requireNonNull(latency, "latency");
    }

    public String text() {
        return text;
    }

    /**
     * The reply parsed as JSON into {@code type}. A Markdown code fence around the JSON, which
     * local models add whatever they are told, is stripped first.
     *
     * @throws ModelException of kind {@link ModelException.Kind#SCHEMA_MISMATCH} if it does not
     *     parse; inside an {@link AiHandler} with {@code repairOnce()}, that earns one repair
     */
    public <T> T as(Class<T> type) {
        try {
            return ModelRequest.JSON.readValue(unfenced(text), type);
        } catch (JsonProcessingException e) {
            throw new ModelException(ModelException.Kind.SCHEMA_MISMATCH,
                    "the reply is not a " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    static String unfenced(String text) {
        String t = text.trim();
        if (t.startsWith("```") && t.endsWith("```") && t.length() >= 6) {
            int firstLine = t.indexOf('\n');
            return firstLine < 0 ? "" : t.substring(firstLine + 1, t.length() - 3).trim();
        }
        return t;
    }

    public Usage usage() {
        return usage;
    }

    public StopReason stopReason() {
        return stopReason;
    }

    /** @return the model that answered, as the provider named it */
    public String model() {
        return model;
    }

    public Duration latency() {
        return latency;
    }

    @Override
    public String toString() {
        return "ModelResponse{model=" + model + ", stop=" + stopReason + ", " + usage
                + ", latency=" + latency.toMillis() + "ms, text=" + text.length() + " chars}";
    }
}
