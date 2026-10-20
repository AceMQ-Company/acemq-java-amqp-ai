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

import java.net.URI;
import java.util.Objects;

/** Which model a client talks to, and where. The {@code provider} and {@code model} metric tags. */
public final class ModelInfo {

    private final String provider;
    private final String model;
    private final URI endpoint;

    public ModelInfo(String provider, String model, URI endpoint) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = Objects.requireNonNull(model, "model");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public URI endpoint() {
        return endpoint;
    }

    /**
     * @return whether the endpoint is loopback or a private address, judged from the URL alone
     *     without a DNS lookup; {@link EgressGuard} is the check that resolves names
     */
    public boolean isLocal() {
        return EgressGuard.isLocalLiteral(endpoint.getHost());
    }

    @Override
    public String toString() {
        return provider + ":" + model + " at " + endpoint;
    }
}
