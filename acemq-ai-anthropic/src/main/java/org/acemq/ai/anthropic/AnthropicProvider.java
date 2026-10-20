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
package org.acemq.ai.anthropic;

import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelConfig;
import org.acemq.ai.ModelProvider;

/** {@code acemq.ai.provider=anthropic}. Reads {@code acemq.ai.effort} as well as the common keys. */
public final class AnthropicProvider implements ModelProvider {

    @Override
    public String name() {
        return AnthropicModelClient.PROVIDER;
    }

    @Override
    public ModelClient create(ModelConfig config) {
        AnthropicModelClient.Builder b = AnthropicModelClient.builder(config.model())
                .apiKeyFromEnv(config.apiKeyEnv(AnthropicModelClient.DEFAULT_KEY_ENV))
                .timeout(config.timeout())
                .egress(config.egress())
                .allowHost(config.egressAllow().toArray(new String[0]));
        config.baseUrl().ifPresent(b::baseUrl);
        config.apiKey(AnthropicModelClient.DEFAULT_KEY_ENV).ifPresent(b::apiKey);
        config.get("effort").ifPresent(b::effort);
        return b.build();
    }
}
