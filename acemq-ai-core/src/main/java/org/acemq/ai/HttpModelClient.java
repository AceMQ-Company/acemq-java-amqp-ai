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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** What the two JSON-over-HTTP adapters share: one POST, one classification of what came back. */
abstract class HttpModelClient implements ModelClient {

    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final ModelInfo info;

    HttpModelClient(String provider, String model, URI endpoint, String apiKey, Duration timeout) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.info = new ModelInfo(provider, model, endpoint);
        // HTTP/1.1 explicitly. The JDK client otherwise offers an h2c upgrade on plain http,
        // which local inference servers handle badly with a request body (the admin client
        // met the same thing against RabbitMQ's management API).
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    abstract ObjectNode body(ModelRequest request);

    abstract ModelResponse parse(JsonNode reply, Duration latency);

    /** The provider's own error shapes; the default is the shared HTTP status table. */
    ModelException error(int status, String body, String retryAfter) {
        return ModelException.fromHttpStatus(info.provider(), status, body, retryAfter);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(request.timeout().orElse(timeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(request).toString()));
        if (apiKey != null) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ModelException(ModelException.Kind.TIMEOUT,
                    info.provider() + " gave no answer within " + request.timeout().orElse(timeout), e);
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.UNAVAILABLE,
                    "cannot reach " + info.provider() + " at " + endpoint + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.Kind.UNAVAILABLE, "interrupted waiting for " + info.provider(), e);
        }
        Duration latency = Duration.ofNanos(System.nanoTime() - start);
        if (response.statusCode() / 100 != 2) {
            throw error(response.statusCode(), response.body(),
                    response.headers().firstValue("retry-after").orElse(null));
        }
        JsonNode reply;
        try {
            reply = ModelRequest.JSON.readTree(response.body());
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.UNAVAILABLE,
                    info.provider() + " at " + endpoint + " answered something that is not JSON", e);
        }
        return parse(reply, latency);
    }

    @Override
    public ModelInfo info() {
        return info;
    }

    /** {@code base} plus {@code path}, keeping any path prefix a proxy put in front. */
    static URI under(URI base, String path) {
        String b = base.toString();
        return URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + path);
    }

    static ObjectNode schemaNode(String schema) {
        try {
            return (ObjectNode) ModelRequest.JSON.readTree(schema);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);  // validated when the request was built
        }
    }
}
