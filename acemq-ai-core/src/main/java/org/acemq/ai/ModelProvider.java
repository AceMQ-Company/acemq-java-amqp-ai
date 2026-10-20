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
 * Builds a {@link ModelClient} from configuration. Found through {@link java.util.ServiceLoader},
 * so adding a provider module to the classpath makes its name usable as {@code acemq.ai.provider}.
 *
 * <p>An implementation must apply {@link EgressGuard#check} before returning a client.
 */
public interface ModelProvider {

    /** @return the {@code acemq.ai.provider} value this answers to, in lower case */
    String name();

    ModelClient create(ModelConfig config);
}
