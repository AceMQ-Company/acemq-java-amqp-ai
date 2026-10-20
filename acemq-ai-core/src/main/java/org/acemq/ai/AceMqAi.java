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

import org.acemq.amqp.core.AceMq;
import org.acemq.amqp.core.ConsumerOptions;
import org.acemq.amqp.core.MessageConsumer;

/**
 * Starts a consumer whose handler calls a model, with the back-pressure wired in: a rate
 * limit or an exhausted {@link TokenBudget} pauses this consumer, and it resumes by itself.
 *
 * <p>Concurrency is {@code ConsumerOptions.prefetch}, exactly as for any consumer: on a local
 * model, set it to the number of requests the GPU serves at once.
 */
public final class AceMqAi {

    private AceMqAi() {
    }

    public static <T> MessageConsumer consume(AceMq mq, String queue, Class<T> payloadType,
            ConsumerOptions options, AiHandler<T> handler) {
        return consume(mq, queue, payloadType, options, null, handler);
    }

    /** @param budget shared by every consumer given the same instance; null for none */
    public static <T> MessageConsumer consume(AceMq mq, String queue, Class<T> payloadType,
            ConsumerOptions options, TokenBudget budget, AiHandler<T> handler) {
        Pauser pauser = new Pauser(queue);
        // Wired before the first delivery can arrive, so even the first call is accounted for;
        // a pause it asks for is applied the moment the consumer exists.
        handler.attach(pauser, budget, null);
        if (budget != null) {
            budget.attach(pauser);
            handler.telemetry().registerBudget(queue, budget::isPaused);
        }
        MessageConsumer consumer = mq.consume(queue, payloadType, options, handler);
        handler.attach(pauser, budget, consumer);
        pauser.attach(consumer);
        return consumer;
    }
}
