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
package org.acemq.ai.test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelException;
import org.acemq.ai.ModelInfo;
import org.acemq.ai.ModelRequest;
import org.acemq.ai.ModelResponse;
import org.acemq.ai.StopReason;
import org.acemq.ai.Usage;

/**
 * A model that answers from a script, one step per call, and records every request.
 *
 * <pre>{@code
 * ScriptedModel model = ScriptedModel.create()
 *         .rateLimited(Duration.ofMillis(200))
 *         .reply("{\"queue\":\"billing\"}", 120, 8);
 * }</pre>
 *
 * <p>Real models never run in tests. Non-determinism is the model's property, not the
 * library's, and the library is what is under test. Over HTTP, see {@link ScriptedModelServer}.
 */
public final class ScriptedModel implements ModelClient {

    enum Kind { REPLY, ERROR, HANG }

    static final class Step {
        final Kind kind;
        final String text;
        final Usage usage;
        final StopReason stop;
        final ModelException.Kind error;
        final String detail;
        final Duration duration;

        Step(Kind kind, String text, Usage usage, StopReason stop, ModelException.Kind error, String detail,
                Duration duration) {
            this.kind = kind;
            this.text = text;
            this.usage = usage;
            this.stop = stop;
            this.error = error;
            this.detail = detail;
            this.duration = duration;
        }
    }

    private final String model;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<ModelRequest> calls = new ArrayList<>();
    private boolean repeatLast;

    private ScriptedModel(String model) {
        this.model = model;
    }

    public static ScriptedModel create() {
        return named("scripted");
    }

    public static ScriptedModel named(String model) {
        return new ScriptedModel(model);
    }

    /** A reply with token counts a test can assert on: 10 in, and about a token per four characters out. */
    public ScriptedModel reply(String text) {
        return reply(text, 10, text.length() / 4 + 1);
    }

    public ScriptedModel reply(String text, long inputTokens, long outputTokens) {
        return add(new Step(Kind.REPLY, text, Usage.of(inputTokens, outputTokens), StopReason.END, null, null, null));
    }

    /** A reply cut off at the output limit. */
    public ScriptedModel maxTokens(String partial) {
        return add(new Step(Kind.REPLY, partial, Usage.of(10, 1), StopReason.MAX_TOKENS, null, null, null));
    }

    /** The model declines. */
    public ScriptedModel refusal() {
        return add(new Step(Kind.REPLY, "I can't help with that.", Usage.of(10, 6), StopReason.REFUSAL, null, null,
                null));
    }

    /** HTTP 429, with {@code retry-after} when {@code retryAfter} is not null. */
    public ScriptedModel rateLimited(Duration retryAfter) {
        return add(new Step(Kind.ERROR, null, null, null, ModelException.Kind.RATE_LIMITED, "rate limited",
                retryAfter));
    }

    public ScriptedModel overloaded() {
        return add(new Step(Kind.ERROR, null, null, null, ModelException.Kind.OVERLOADED, "overloaded", null));
    }

    public ScriptedModel unavailable() {
        return add(new Step(Kind.ERROR, null, null, null, ModelException.Kind.UNAVAILABLE, "internal error", null));
    }

    public ScriptedModel badRequest(String detail) {
        return add(new Step(Kind.ERROR, null, null, null, ModelException.Kind.BAD_REQUEST, detail, null));
    }

    public ScriptedModel contextTooLong() {
        return add(new Step(Kind.ERROR, null, null, null, ModelException.Kind.CONTEXT_TOO_LONG,
                "context_length_exceeded", null));
    }

    public ScriptedModel unauthorized() {
        return add(new Step(Kind.ERROR, null, null, null, ModelException.Kind.AUTH, "invalid api key", null));
    }

    /**
     * No answer. In process this is the client's timeout, thrown at once; over HTTP the server
     * holds the request for {@code hang} and the client's own timeout decides.
     */
    public ScriptedModel timeout(Duration hang) {
        return add(new Step(Kind.HANG, "late", Usage.of(10, 1), StopReason.END, ModelException.Kind.TIMEOUT,
                "timed out", hang));
    }

    /** Over HTTP, Ollama's 404 for a model never pulled. In process, a bad request. */
    public ScriptedModel modelNotFound() {
        return add(new Step(Kind.ERROR, null, null, null, ModelException.Kind.BAD_REQUEST,
                "model \"" + model + "\" not found, try pulling it first", Duration.ZERO));
    }

    /** The last step answers every call after it, instead of the script running out. */
    public ScriptedModel repeat() {
        repeatLast = true;
        return this;
    }

    private synchronized ScriptedModel add(Step step) {
        steps.add(step);
        return this;
    }

    /** @return every request made, in order */
    public synchronized List<ModelRequest> calls() {
        return Collections.unmodifiableList(new ArrayList<>(calls));
    }

    public synchronized int callCount() {
        return calls.size();
    }

    synchronized Step next(ModelRequest request) {
        calls.add(request);
        Step step = repeatLast && steps.size() == 1 ? steps.peek() : steps.poll();
        if (step == null) {
            throw new IllegalStateException("the script ran out at call " + calls.size()
                    + "; add a step, or end it with repeat()");
        }
        return step;
    }

    String model() {
        return model;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        Step step = next(request);
        switch (step.kind) {
            case REPLY:
                return new ModelResponse(step.text, step.usage, step.stop, model, Duration.ofMillis(1));
            case HANG:
                throw new ModelException(ModelException.Kind.TIMEOUT, "scripted: no answer");
            default:
                throw new ModelException(step.error, "scripted: " + step.detail,
                        step.error == ModelException.Kind.RATE_LIMITED ? step.duration : null, null);
        }
    }

    @Override
    public ModelInfo info() {
        return new ModelInfo("scripted", model, URI.create("http://localhost/scripted"));
    }
}
