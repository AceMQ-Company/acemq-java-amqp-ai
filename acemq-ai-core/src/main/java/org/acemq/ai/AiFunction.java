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

import java.util.function.Function;

import org.acemq.amqp.api.AceFatalException;
import org.acemq.amqp.api.AceRetryableException;

/**
 * A model behind a queue: the function {@code AceMq.respond} wants.
 *
 * <pre>{@code
 * mq.respond("ai.summarise", SummariseRequest.class,
 *         AiFunction.of(model, SummariseRequest::toModelRequest, Summary.class));
 * }</pre>
 *
 * <p>One GPU box or one API key then serves every service that can publish, in fair turn.
 * Generation often takes longer than a sensible request timeout; for long work, reply to a
 * queue of your own rather than waiting on direct reply-to.
 */
public final class AiFunction {

    private AiFunction() {
    }

    /** @param answerType {@code String.class} for the text as it came, anything else is parsed as JSON */
    public static <Q, A> Function<Q, A> of(ModelClient model, Function<Q, ModelRequest> request, Class<A> answerType) {
        return question -> {
            try {
                ModelResponse reply = model.complete(request.apply(question));
                if (reply.stopReason() == StopReason.REFUSAL) {
                    throw new AceFatalException("refused: the model declined to answer");
                }
                if (reply.stopReason() == StopReason.MAX_TOKENS) {
                    throw new AceFatalException("the reply was cut off at maxOutputTokens");
                }
                return answerType == String.class ? answerType.cast(reply.text()) : reply.as(answerType);
            } catch (ModelException e) {
                throw e.isRetryable() ? new AceRetryableException(e.getMessage(), e)
                        : new AceFatalException(e.getMessage(), e);
            }
        };
    }
}
