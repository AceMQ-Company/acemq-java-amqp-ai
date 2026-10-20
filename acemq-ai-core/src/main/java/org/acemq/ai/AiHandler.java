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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import org.acemq.amqp.api.AceFatalException;
import org.acemq.amqp.api.AceMqException;
import org.acemq.amqp.api.AceRetryableException;
import org.acemq.amqp.api.Message;
import org.acemq.amqp.api.MessageHandler;

/**
 * A model call inside a message handler, behaving like any other unreliable downstream.
 *
 * <pre>{@code
 * AiHandler<Ticket> triage = AiHandler.forModel(model, Ticket.class)
 *         .request(msg -> ModelRequest.builder()
 *                 .system(TRIAGE_PROMPT)
 *                 .user(msg.payload().text())
 *                 .responseSchema(TRIAGE_SCHEMA)
 *                 .maxOutputTokens(1_024)
 *                 .build())
 *         .onResponse((msg, reply) -> route(reply.as(Triage.class)))
 *         .repairOnce()
 *         .build();
 *
 * MessageConsumer c = AceMqAi.consume(mq, "tickets.new", Ticket.class,
 *         ConsumerOptions.prefetch(4).withRetry(policy).idempotent(store),
 *         TokenBudget.perMinute(200_000), triage);
 * }</pre>
 *
 * <p>What it does with each outcome:
 *
 * <ul>
 *   <li>A rate limit pauses the consumer for {@code retry-after} (or a jittered default) and
 *       throws {@link AceRetryableException}: the pause does the waiting, the retry ladder does
 *       the counting, and nothing sleeps in the consumer thread.
 *   <li>Overload, unavailability and timeouts are retryable. Bad requests, authentication
 *       failures and a context that is too long are fatal, so the message goes to the
 *       dead-letter queue without spending more calls.
 *   <li>A refusal is fatal, unless a fallback model is configured, which is asked once.
 *   <li>A reply cut off at {@code maxOutputTokens} is fatal.
 *   <li>A reply that does not parse ({@link ModelException.Kind#SCHEMA_MISMATCH}, thrown by
 *       {@link ModelResponse#as(Class)} in {@code onResponse}) is fatal, or with
 *       {@link Builder#repairOnce()} earns exactly one repair request. Never more: unbounded
 *       repair loops are how a bill grows overnight.
 * </ul>
 *
 * <p>A redelivery of a message whose call already succeeded must not pay twice. That is the
 * consumer's {@code ConsumerOptions.idempotent(store)}, which claims by message id before this
 * handler runs; use it.
 */
public final class AiHandler<T> implements MessageHandler<T> {

    /** What to do with a successful reply. */
    @FunctionalInterface
    public interface ResponseHandler<T> {
        void accept(Message<T> message, ModelResponse response) throws Exception;
    }

    static final String RATE_LIMITED = "rate limited";

    private final ModelClient model;
    private final Function<Message<T>, ModelRequest> request;
    private final ResponseHandler<T> onResponse;
    private final ModelClient fallback;
    private final boolean repair;
    private final Duration defaultRateLimitPause;
    private final AiTelemetry telemetry;
    private volatile Pauser pauser;
    private volatile TokenBudget budget;
    private volatile org.acemq.amqp.core.MessageConsumer consumer;

    private AiHandler(Builder<T> b) {
        this.model = b.model;
        this.request = Objects.requireNonNull(b.request, "request(...) is required");
        this.onResponse = Objects.requireNonNull(b.onResponse, "onResponse(...) is required");
        this.fallback = b.fallback;
        this.repair = b.repair;
        this.defaultRateLimitPause = b.defaultRateLimitPause;
        this.telemetry = b.telemetry != null ? b.telemetry : AiTelemetry.defaults();
    }

    public static <T> Builder<T> forModel(ModelClient model, Class<T> payloadType) {
        return new Builder<>(model);
    }

    /** Wires the pause-and-resume half; {@link AceMqAi#consume} calls it. */
    void attach(Pauser pauser, TokenBudget budget, org.acemq.amqp.core.MessageConsumer consumer) {
        this.pauser = pauser;
        this.budget = budget;
        this.consumer = consumer;
    }

    AiTelemetry telemetry() {
        return telemetry;
    }

    @Override
    public void handle(Message<T> message) throws Exception {
        ModelRequest req = request.apply(message);
        ModelResponse reply = answer(message.queue(), req);
        try {
            onResponse.accept(message, reply);
        } catch (ModelException e) {
            if (e.kind() != ModelException.Kind.SCHEMA_MISMATCH || !repair) {
                throw translate(e);
            }
            ModelRequest again = req.followUp(ChatMessage.assistant(reply.text()), ChatMessage.user(
                    "That reply could not be used: " + e.getMessage()
                            + ". Reply again with only the JSON the schema asks for, and nothing else."));
            ModelResponse repaired = answer(message.queue(), again);
            try {
                onResponse.accept(message, repaired);
            } catch (ModelException stillWrong) {
                throw translate(stillWrong);
            }
        }
    }

    /** One call, with the stop reason checked and a refusal handed to the fallback. */
    private ModelResponse answer(String queue, ModelRequest req) {
        ModelResponse reply = call(model, queue, req);
        if (reply.stopReason() == StopReason.REFUSAL && fallback != null) {
            reply = call(fallback, queue, req);
        }
        if (reply.stopReason() == StopReason.REFUSAL) {
            throw new AceFatalException("refused: the model declined to answer");
        }
        if (reply.stopReason() == StopReason.MAX_TOKENS) {
            throw new AceFatalException("the reply was cut off at maxOutputTokens=" + req.maxOutputTokens()
                    + "; raise it or shorten the input");
        }
        return reply;
    }

    private ModelResponse call(ModelClient client, String queue, ModelRequest req) {
        long start = System.nanoTime();
        try {
            ModelResponse reply = client.complete(req);
            telemetry.recordCall(queue, client.info(),
                    reply.stopReason() == StopReason.REFUSAL ? "refused" : "answered", reply.latency(), reply.usage());
            TokenBudget b = budget;
            if (b != null) {
                org.acemq.amqp.core.MessageConsumer c = consumer;
                long inFlight = c == null ? 1 : Math.max(1, c.prefetch());
                b.record(reply.usage(), inFlight * req.maxOutputTokens());
            }
            return reply;
        } catch (ModelException e) {
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            telemetry.recordCall(queue, client.info(),
                    e.kind() == ModelException.Kind.TIMEOUT ? "timed_out"
                            : e.kind() == ModelException.Kind.REFUSED ? "refused" : "failed",
                    took, Usage.NONE);
            throw translate(e);
        }
    }

    private AceMqException translate(ModelException e) {
        if (e.kind() == ModelException.Kind.RATE_LIMITED) {
            Pauser p = pauser;
            if (p != null) {
                p.pauseFor(RATE_LIMITED, e.retryAfter().orElseGet(this::jitteredPause));
            }
        }
        String reason = e.kind().name().toLowerCase(java.util.Locale.ROOT) + ": " + e.getMessage();
        return e.isRetryable() ? new AceRetryableException(reason, e) : new AceFatalException(reason, e);
    }

    private Duration jitteredPause() {
        long base = defaultRateLimitPause.toMillis();
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(-base / 5, base / 5 + 1));
    }

    /** Builds an {@link AiHandler}. */
    public static final class Builder<T> {

        private final ModelClient model;
        private Function<Message<T>, ModelRequest> request;
        private ResponseHandler<T> onResponse;
        private ModelClient fallback;
        private boolean repair;
        private Duration defaultRateLimitPause = Duration.ofSeconds(30);
        private AiTelemetry telemetry;

        private Builder(ModelClient model) {
            this.model = Objects.requireNonNull(model, "model");
        }

        /** Turns a message into the model request. Required. */
        public Builder<T> request(Function<Message<T>, ModelRequest> request) {
            this.request = request;
            return this;
        }

        /** What to do with the reply: publish it, store it, route on it. Required. */
        public Builder<T> onResponse(ResponseHandler<T> onResponse) {
            this.onResponse = onResponse;
            return this;
        }

        /** A second model to ask, once, when the first refuses. */
        public Builder<T> fallbackOnRefusal(ModelClient fallback) {
            this.fallback = fallback;
            return this;
        }

        /** One repair request when the reply does not match its schema. Off by default. */
        public Builder<T> repairOnce() {
            this.repair = true;
            return this;
        }

        /** How long to pause on a rate limit that names no {@code retry-after}. Default 30 s, ±20%. */
        public Builder<T> rateLimitPause(Duration pause) {
            this.defaultRateLimitPause = pause;
            return this;
        }

        public Builder<T> telemetry(AiTelemetry telemetry) {
            this.telemetry = telemetry;
            return this;
        }

        public AiHandler<T> build() {
            return new AiHandler<>(this);
        }
    }
}
