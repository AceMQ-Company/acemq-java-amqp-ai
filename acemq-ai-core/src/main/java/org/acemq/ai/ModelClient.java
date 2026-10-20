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

/**
 * One model at one provider. Get one from {@link ModelClients}.
 *
 * <p>An adapter never retries. Retrying is the broker's job, through the consumer's retry
 * ladder, so a failed call throws a classified {@link ModelException} at once and nothing
 * multiplies the attempts behind the ladder's back.
 */
public interface ModelClient extends AutoCloseable {

    /**
     * Asks the model and waits for the whole reply.
     *
     * @throws ModelException when there is no answer; {@link ModelException#isRetryable()} says
     *     whether asking again could help
     */
    ModelResponse complete(ModelRequest request);

    /** @return the provider, model and endpoint this client talks to */
    ModelInfo info();

    @Override
    default void close() {
    }
}
