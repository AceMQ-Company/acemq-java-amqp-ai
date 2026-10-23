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
package org.acemq.ai.tck;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.acemq.ai.AceMqAi;
import org.acemq.ai.AiHandler;
import org.acemq.ai.MicrometerAiTelemetry;
import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelClients;
import org.acemq.ai.ModelRequest;
import org.acemq.ai.TokenBudget;
import org.acemq.amqp.api.ApplyMode;
import org.acemq.amqp.api.RetryPolicy;
import org.acemq.amqp.api.Topology;
import org.acemq.amqp.core.AceMq;
import org.acemq.amqp.core.ConsumerOptions;
import org.acemq.amqp.core.MessageConsumer;
import org.acemq.amqp.core.MicrometerSupport;
import org.acemq.amqp.patterns.InMemoryIdempotencyStore;
import org.acemq.amqp.transport.ConnectionConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The AceMQ TCK driver for the AI adapters: the {@code AI} cases of spec/14-ai.md in
 * github.com/AceMQ-Company/acemq-amqp-tck. The runner plays the model; this only wires the
 * public API ({@link AceMqAi#consume}, {@link AiHandler}, {@link TokenBudget}) to the model URL a
 * case names, and asserts nothing.
 *
 * <pre>
 * mvn -q -pl acemq-ai-test -am test-compile dependency:build-classpath \
 *     -Dmdep.outputFile=target/tck.classpath -Dmdep.includeScope=test
 * java -cp "acemq-ai-test/target/test-classes:acemq-ai-test/target/classes:$(cat acemq-ai-test/target/tck.classpath)" \
 *     org.acemq.ai.tck.TckDriver
 * </pre>
 */
public final class TckDriver {

    private static final List<String> OPS = List.of("hello", "begin", "end", "aiConsume", "handled", "metrics");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String url;
    private AceMq mq;
    private SimpleMeterRegistry recorder = new SimpleMeterRegistry();
    private final List<AutoCloseable> opened = new ArrayList<>();
    private final Map<String, ConcurrentMap<String, AtomicInteger>> counts = new ConcurrentHashMap<>();

    private TckDriver(String url) {
        this.url = url;
    }

    public static void main(String[] args) throws Exception {
        // The protocol owns stdout; everything else, the logger included, goes to stderr.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = System.err;
        System.setOut(err);
        TckDriver driver = new TckDriver(
                System.getenv().getOrDefault("ACEMQ_TCK_AMQP_URL", "amqp://guest:guest@localhost:5672/"));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> answer = new LinkedHashMap<>();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = JSON.readValue(line, Map.class);
                answer.put("id", request.get("id"));
                answer.put("result", driver.dispatch(String.valueOf(request.get("op")), request));
                answer.put("ok", true);
            } catch (Exception failure) {
                err.println("tck driver: " + line.strip() + " failed");
                failure.printStackTrace(err);
                answer.put("ok", false);
                answer.put("error", Map.of(
                        "kind", failure instanceof UnsupportedOperationException ? "unsupported" : "other",
                        "type", failure.getClass().getSimpleName(),
                        "message", String.valueOf(failure.getMessage())));
            }
            out.println(JSON.writeValueAsString(answer));
        }
        driver.end();
        System.exit(0);
    }

    private Map<String, Object> dispatch(String op, Map<String, Object> args) throws Exception {
        switch (op) {
            case "hello":
                Package where = AceMqAi.class.getPackage();
                String version = where == null ? null : where.getImplementationVersion();
                return Map.of("library", "acemq-java-amqp-ai", "version", version == null ? "unknown" : version,
                        "ops", OPS);
            case "begin":
                end();
                recorder = new SimpleMeterRegistry();
                mq = AceMq.connect(ConnectionConfig.url(url)
                        .clientName(String.valueOf(args.getOrDefault("clientName", "tck"))).build(),
                        MicrometerSupport.telemetry(recorder, "rabbitmq"));
                return Map.of();
            case "end":
                return end();
            case "aiConsume":
                return aiConsume(args);
            case "handled":
                Map<String, Integer> seen = new LinkedHashMap<>();
                counts.getOrDefault(String.valueOf(args.get("handle")), new ConcurrentHashMap<>())
                        .forEach((id, n) -> seen.put(id, n.get()));
                return Map.of("counts", seen);
            case "metrics":
                return metrics();
            default:
                throw new UnsupportedOperationException(op);
        }
    }

    private Map<String, Object> end() throws Exception {
        for (AutoCloseable c : opened) {
            c.close();
        }
        opened.clear();
        counts.clear();
        if (mq != null) {
            mq.close();
            mq = null;
        }
        return Map.of();
    }

    /**
     * The queue declared as a consumed queue is ({@code queueWithDeadLetter}), consumed through
     * {@link AceMqAi#consume} with an {@link AiHandler} over the case's model. The handler asks the
     * model about the body and, once it answered, counts {@code body.id} and sleeps
     * {@code behaviour.sleepMs}: after the answer, before the acknowledgement.
     */
    private Map<String, Object> aiConsume(Map<String, Object> args) {
        String queue = String.valueOf(args.get("queue"));
        String api = String.valueOf(args.getOrDefault("api", "ollama"));
        String base = String.valueOf(args.get("model"));
        ModelClients.Builder model = api.equals("openai")
                ? ModelClients.openAiCompatible("scripted").baseUrl(base + "/v1")
                : ModelClients.ollama("scripted").baseUrl(base);
        if (args.get("timeoutMs") != null) {
            model.timeout(Duration.ofMillis(intOf(args.get("timeoutMs"), 0)));
        }
        ModelClient client = model.build();
        opened.add(client);

        Map<?, ?> behaviour = args.get("behaviour") instanceof Map ? (Map<?, ?>) args.get("behaviour") : Map.of();
        long sleepMs = intOf(behaviour.get("sleepMs"), 0);
        int maxOutputTokens = intOf(args.get("maxOutputTokens"), 64);
        ConcurrentMap<String, AtomicInteger> counter =
                counts.computeIfAbsent(String.valueOf(args.get("handle")), h -> new ConcurrentHashMap<>());

        AiHandler<Object> handler = AiHandler.forModel(client, Object.class)
                .request(message -> ModelRequest.builder()
                        .user(String.valueOf(message.payload()))
                        .maxOutputTokens(maxOutputTokens)
                        .build())
                .onResponse((message, reply) -> {
                    Object payload = message.payload();
                    String id = payload instanceof Map ? String.valueOf(((Map<?, ?>) payload).get("id")) : "";
                    counter.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                    if (sleepMs > 0) {
                        Thread.sleep(sleepMs);
                    }
                })
                .telemetry(MicrometerAiTelemetry.of(recorder))
                .build();

        ConsumerOptions options = ConsumerOptions.prefetch(intOf(args.get("prefetch"), 1));
        if (args.get("retry") instanceof Map) {
            Map<?, ?> retry = (Map<?, ?>) args.get("retry");
            options = options.withRetry(RetryPolicy.fixed(
                    intOf(retry.get("maxAttempts"), 3), Duration.ofMillis(intOf(retry.get("delayMs"), 1000))));
        }
        if (Boolean.TRUE.equals(args.get("idempotent"))) {
            options = options.idempotent(InMemoryIdempotencyStore.forOneDay());
        }
        TokenBudget budget = null;
        if (args.get("budget") instanceof Map) {
            Map<?, ?> b = (Map<?, ?>) args.get("budget");
            budget = TokenBudget.per(Duration.ofMillis(intOf(b.get("windowMs"), 60_000)), intOf(b.get("tokens"), 1));
        }
        mq.topology().apply(Topology.define().queueWithDeadLetter(queue).build(), ApplyMode.CREATE_ONLY);
        MessageConsumer consumer = AceMqAi.consume(mq, queue, Object.class, options, budget, handler);
        opened.add(0, consumer);  // consumers close before the model clients they call
        return Map.of();
    }

    /** One sample per meter, with its value: a counter's total, a timer's count. */
    private Map<String, Object> metrics() {
        List<Map<String, Object>> samples = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Meter meter : recorder.getMeters()) {
            Map<String, Object> tags = new LinkedHashMap<>();
            for (Tag tag : meter.getId().getTags()) {
                tags.put(tag.getKey(), tag.getValue());
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("name", meter.getId().getName());
            sample.put("tags", tags);
            if (meter instanceof Counter) {
                sample.put("value", ((Counter) meter).count());
            } else if (meter instanceof Timer) {
                sample.put("value", ((Timer) meter).count());
            }
            samples.add(sample);
            if (!names.contains(meter.getId().getName())) {
                names.add(meter.getId().getName());
            }
        }
        return Map.of("names", names, "samples", samples);
    }

    private static int intOf(Object value, int otherwise) {
        return value instanceof Number ? ((Number) value).intValue() : otherwise;
    }
}
