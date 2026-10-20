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

/** Tokens one call used, as the provider reported them. */
public final class Usage {

    /** A call that reported nothing. */
    public static final Usage NONE = new Usage(0, 0, 0);

    private final long inputTokens;
    private final long outputTokens;
    private final long cachedInputTokens;

    private Usage(long inputTokens, long outputTokens, long cachedInputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cachedInputTokens = cachedInputTokens;
    }

    public static Usage of(long inputTokens, long outputTokens) {
        return new Usage(inputTokens, outputTokens, 0);
    }

    /** @param cachedInputTokens the part of {@code inputTokens} served from the provider's cache */
    public static Usage of(long inputTokens, long outputTokens, long cachedInputTokens) {
        return new Usage(inputTokens, outputTokens, cachedInputTokens);
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long cachedInputTokens() {
        return cachedInputTokens;
    }

    /** @return input plus output, which is what a {@link TokenBudget} counts */
    public long total() {
        return inputTokens + outputTokens;
    }

    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                cachedInputTokens + other.cachedInputTokens);
    }

    @Override
    public String toString() {
        return "Usage{input=" + inputTokens + ", output=" + outputTokens + ", cachedInput=" + cachedInputTokens + "}";
    }
}
