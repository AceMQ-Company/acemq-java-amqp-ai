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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.acemq.ai.AceMqAi;
import org.acemq.ai.AiHandler;
import org.acemq.ai.AiTelemetry;
import org.acemq.ai.ChatMessage;
import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelInfo;
import org.acemq.ai.ModelRequest;
import org.acemq.ai.TokenBudget;
import org.acemq.ai.Usage;
import org.acemq.amqp.api.Envelope;
import org.acemq.amqp.api.RetryPolicy;
import org.acemq.amqp.core.AceMq;
import org.acemq.amqp.core.ConsumerOptions;
import org.acemq.amqp.core.MessageConsumer;
import org.acemq.amqp.patterns.InMemoryIdempotencyStore;
import org.acemq.amqp.test.InMemoryTransport;
import org.acemq.amqp.transport.QueueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Capability 1 end to end on the in-memory broker: the retry ladder, the dead-letter queue,
 * idempotency and back-pressure, each driven by a scripted model outcome. These are the TCK's
 * ai-001 to ai-007 cases, in Java.
 */
@Timeout(30)
class AiHandlerTest {

    private static final RetryPolicy RETRY = RetryPolicy.fixed(3, Duration.ofMillis(50)).withJitter(0);

    private AceMq mq;
    private final List<String> outcomes = new CopyOnWriteArrayList<>();
    private final List<Long> tokens = new CopyOnWriteArrayList<>();
    private final AiTelemetry telemetry = new AiTelemetry() {
        @Override
        public void recordCall(String queue, ModelInfo model, String outcome, Duration latency, Usage usage) {
            outcomes.add(outcome);
            tokens.add(usage.total());
        }
    };

    private AceMq connect(String broker) {
        mq = AceMq.connect("memory://" + broker);
        mq.declareExchange("tickets", "topic");
        mq.declareQueue("tickets.new", QueueType.CLASSIC, Collections.emptyMap());
        mq.bind("tickets.new", "tickets", "#");
        return mq;
    }

    @AfterEach
    void disconnect() {
        if (mq != null && mq.isOpen()) {
            mq.close();
        }
        InMemoryTransport.reset();
    }

    private AiHandler.Builder<String> handler(ModelClient model, List<String> answers) {
        return AiHandler.forModel(model, String.class)
                .request(msg -> ModelRequest.builder().system("route tickets").user(msg.payload())
                        .maxOutputTokens(10).build())
                .onResponse((msg, reply) -> answers.add(reply.text()))
                .telemetry(telemetry);
    }

    private void publish(String payload) {
        mq.publisher("tickets", "ticket.new").send(payload);
    }

    @Test
    void ai_001_answered() {
        connect("ai-001");
        ScriptedModel model = ScriptedModel.create().reply("billing", 120, 8);
        List<String> answers = new CopyOnWriteArrayList<>();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY), handler(model, answers).build())) {
            publish("my invoice is wrong");
            await().until(() -> c.acknowledged() == 1);
        }
        assertThat(answers).containsExactly("billing");
        assertThat(outcomes).containsExactly("answered");
        assertThat(tokens).containsExactly(128L);
        assertThat(model.calls().get(0).messages()).containsExactly(ChatMessage.user("my invoice is wrong"));
    }

    @Test
    void ai_002_a_rate_limit_pauses_the_consumer_and_takes_a_retry_rung() {
        connect("ai-002");
        ScriptedModel model = ScriptedModel.create().rateLimited(Duration.ofMillis(400)).reply("ok");
        List<String> answers = new CopyOnWriteArrayList<>();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY), handler(model, answers).build())) {
            long start = System.nanoTime();
            publish("t");
            await().until(c::isPaused);
            await().until(() -> c.acknowledged() == 1);
            long tookMs = (System.nanoTime() - start) / 1_000_000;

            // The 50 ms rung alone would have redelivered long before this: the pause held it.
            assertThat(tookMs).isGreaterThanOrEqualTo(400);
            assertThat(c.retried()).isEqualTo(1);
            assertThat(c.deadLettered()).isZero();
            assertThat(c.isPaused()).isFalse();
        }
        assertThat(model.callCount()).isEqualTo(2);
        assertThat(answers).containsExactly("ok");
    }

    @Test
    void ai_003_a_timeout_is_retryable() {
        connect("ai-003");
        ScriptedModel model = ScriptedModel.create().timeout(Duration.ofSeconds(1)).reply("ok");
        List<String> answers = new CopyOnWriteArrayList<>();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY), handler(model, answers).build())) {
            publish("t");
            await().until(() -> c.acknowledged() == 1);
            assertThat(c.retried()).isEqualTo(1);
        }
        assertThat(outcomes).containsExactly("timed_out", "answered");
    }

    @Test
    void ai_004_a_bad_request_goes_to_the_dlq_without_retries() {
        connect("ai-004");
        ScriptedModel model = ScriptedModel.create().badRequest("unsupported parameter").repeat();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY), handler(model, new CopyOnWriteArrayList<>()).build())) {
            publish("t");
            await().until(() -> c.deadLettered() == 1);
            assertThat(c.retried()).isZero();
        }
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(outcomes).containsExactly("failed");
    }

    @Test
    void ai_005_a_refusal_goes_to_the_dlq() {
        connect("ai-005");
        ScriptedModel model = ScriptedModel.create().refusal().repeat();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY), handler(model, new CopyOnWriteArrayList<>()).build())) {
            publish("t");
            await().until(() -> c.deadLettered() == 1);
            assertThat(c.retried()).isZero();
        }
        assertThat(outcomes).containsExactly("refused");
    }

    @Test
    void a_refusal_with_a_fallback_asks_the_fallback_once() {
        connect("ai-005b");
        ScriptedModel model = ScriptedModel.create().refusal();
        ScriptedModel fallback = ScriptedModel.named("fallback").reply("handled");
        List<String> answers = new CopyOnWriteArrayList<>();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY),
                handler(model, answers).fallbackOnRefusal(fallback).build())) {
            publish("t");
            await().until(() -> c.acknowledged() == 1);
        }
        assertThat(answers).containsExactly("handled");
        assertThat(fallback.callCount()).isEqualTo(1);
    }

    @Test
    void ai_006_a_redelivery_of_an_answered_message_is_not_billed_again() {
        connect("ai-006");
        ScriptedModel model = ScriptedModel.create().reply("once").repeat();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY).idempotent(InMemoryIdempotencyStore.forOneDay()),
                handler(model, new CopyOnWriteArrayList<>()).build())) {
            // The same envelope three times is what a redelivery after a lost ack looks like.
            Envelope envelope = Envelope.of("ticket.new").build();
            for (int i = 0; i < 3; i++) {
                mq.publisher("tickets", "ticket.new").send("t", envelope);
            }
            await().until(() -> c.acknowledged() + c.duplicates() == 3);
        }
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    void ai_007_an_exhausted_budget_pauses_and_a_refill_resumes() {
        connect("ai-007");
        ScriptedModel model = ScriptedModel.create().reply("a", 50, 50).repeat();
        TokenBudget budget = TokenBudget.per(Duration.ofMillis(500), 100);

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY), budget,
                handler(model, new CopyOnWriteArrayList<>()).build())) {
            publish("one");
            await().until(() -> c.acknowledged() == 1);
            assertThat(budget.isPaused()).isTrue();
            assertThat(c.isPaused()).isTrue();

            publish("two");
            await().until(() -> c.acknowledged() == 2);
            assertThat(c.deadLettered()).isZero();
        }
        assertThat(model.callCount()).isEqualTo(2);
    }

    @Test
    void a_reply_that_does_not_parse_earns_one_repair() {
        connect("repair");
        ScriptedModel model = ScriptedModel.create().reply("billing, I think").reply("{\"queue\":\"billing\"}");
        List<String> queues = new CopyOnWriteArrayList<>();

        AiHandler<String> h = AiHandler.forModel(model, String.class)
                .request(msg -> ModelRequest.builder().user(msg.payload()).responseSchema("{\"type\":\"object\"}").build())
                .onResponse((msg, reply) -> queues.add(String.valueOf(reply.as(Map.class).get("queue"))))
                .repairOnce()
                .telemetry(telemetry)
                .build();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY), h)) {
            publish("t");
            await().until(() -> c.acknowledged() == 1);
        }
        assertThat(queues).containsExactly("billing");
        assertThat(model.calls().get(1).messages()).hasSize(3);
    }

    @Test
    void only_one_repair_and_then_the_dlq() {
        connect("repair-fails");
        ScriptedModel model = ScriptedModel.create().reply("nope").repeat();

        AiHandler<String> h = AiHandler.forModel(model, String.class)
                .request(msg -> ModelRequest.builder().user(msg.payload()).build())
                .onResponse((msg, reply) -> reply.as(Map.class))
                .repairOnce()
                .telemetry(telemetry)
                .build();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY), h)) {
            publish("t");
            await().until(() -> c.deadLettered() == 1);
            assertThat(c.retried()).isZero();
        }
        assertThat(model.callCount()).isEqualTo(2);
    }

    @Test
    void a_reply_cut_off_at_the_limit_goes_to_the_dlq() {
        connect("max-tokens");
        ScriptedModel model = ScriptedModel.create().maxTokens("bill").repeat();

        try (MessageConsumer c = AceMqAi.consume(mq, "tickets.new", String.class,
                ConsumerOptions.prefetch(1).withRetry(RETRY), handler(model, new CopyOnWriteArrayList<>()).build())) {
            publish("t");
            await().until(() -> c.deadLettered() == 1);
        }
        assertThat(model.callCount()).isEqualTo(1);
    }
}
