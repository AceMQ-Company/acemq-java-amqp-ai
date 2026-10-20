# acemq-java-amqp-ai

[![ci](https://github.com/AceMQ-Company/acemq-java-amqp-ai/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/AceMQ-Company/acemq-java-amqp-ai/actions/workflows/ci.yml)
[![authorship guard](https://github.com/AceMQ-Company/acemq-java-amqp-ai/actions/workflows/attribution-guard.yml/badge.svg?branch=main)](https://github.com/AceMQ-Company/acemq-java-amqp-ai/actions/workflows/attribution-guard.yml)
[![version](https://img.shields.io/badge/version-0.1.0-blue)](https://github.com/AceMQ-Company/acemq-java-amqp-ai/releases)
[![artifacts](https://img.shields.io/badge/artifacts-acemq.org%2Fmaven-blue)](https://acemq.org/maven/)
[![license](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange)](#requirements)

AI adapters for [AceMQ for Java](https://github.com/AceMQ-Company/acemq-java-amqp): **a model
call that behaves like any other unreliable downstream.** Bounded concurrency, retries in the
broker, a dead-letter queue, idempotency, back-pressure and token metrics, for every team that
puts an LLM behind a queue.

> **Status: in development, not released.** Phase 1: the model interface, the Ollama,
> OpenAI-compatible and hosted Messages API adapters, the egress guard, the queue handler with
> its token budget, and a scripted model for tests.

Local Ollama is the default. Hosted providers are configuration, with your own key:

```properties
# Local (the default provider). No key, nothing leaves the machine.
acemq.ai.model=qwen3-coder:30b
acemq.ai.egress=local-only

# Or a hosted provider, key from the environment:
# acemq.ai.provider=anthropic        (add the acemq-ai-anthropic artifact)
# acemq.ai.model=claude-sonnet-5-5
# acemq.ai.effort=medium
# ANTHROPIC_API_KEY=...              (environment, never source)
#
# acemq.ai.provider=openai           (any /chat/completions server)
# acemq.ai.model=gpt-6.1-sol
# OPENAI_API_KEY=...
```

```java
ModelClient model = ModelClients.fromEnv();          // ACEMQ_AI_* / -Dacemq.ai.*

AiHandler<Ticket> triage = AiHandler.forModel(model, Ticket.class)
        .request(msg -> ModelRequest.builder()
                .system(TRIAGE_PROMPT)
                .user(msg.payload().text())
                .responseSchema(TRIAGE_SCHEMA)
                .maxOutputTokens(1_024)
                .build())
        .onResponse((msg, reply) -> route(reply.as(Triage.class)))
        .repairOnce()
        .build();

MessageConsumer consumer = AceMqAi.consume(mq, "tickets.new", Ticket.class,
        ConsumerOptions.prefetch(4).withRetry(policy).idempotent(store),
        TokenBudget.perMinute(200_000),
        triage);
```

## What the handler does with each outcome

| The model... | The library | Why |
|---|---|---|
| answers | `onResponse` runs; the message is acknowledged | |
| rate-limits (429) | pauses the consumer for `retry-after` (or 30 s ±20%) **and** throws `AceRetryableException` | the pause waits, the retry ladder counts; nothing sleeps in the consumer thread |
| is overloaded (503/529), unreachable, 5xx, or times out | retryable | transient |
| rejects the request (400/404), the key (401/403), or the context length | `AceFatalException`: the dead-letter queue, no retries | retrying cannot fix it |
| refuses | fatal; or, with `fallbackOnRefusal(model)`, the fallback is asked once | deterministic for the same input |
| stops at `maxOutputTokens` | fatal | |
| replies with JSON that does not parse | fatal; or, with `repairOnce()`, **one** repair request | unbounded repair loops burn money |
| already answered this message (redelivery) | not called: `ConsumerOptions.idempotent(store)` claims by message id first | billing must not double on a failover |
| has spent the `TokenBudget` window down to `prefetch × maxOutputTokens` | every consumer sharing the budget pauses until the window refills | a watermark: overspend is bounded by what is in flight |

Concurrency is `prefetch`, as for any consumer. On a local model, set it to the number of
requests the GPU serves at once.

The library guarantees **delivery semantics, not answer semantics.** A retry after a failure
asks the model again and may get a different answer. For reproducibility on Ollama, pass
`option("seed", 1)` and `option("temperature", 0)`.

## Modules

| Artifact | Java | Contents |
|---|---|---|
| `acemq-ai-core` | 11 | `ModelClient`, `ModelRequest`/`ModelResponse`/`Usage`, `ModelException` and its classification, the Ollama and OpenAI-compatible adapters (`java.net.http`, no new jars), `Egress` and `EgressGuard`, `AiHandler`, `AceMqAi.consume`, `TokenBudget`, `AiFunction`, metrics |
| `acemq-ai-test` | 11 | `ScriptedModel` (in process) and `ScriptedModelServer` (HTTP, both wire shapes) |
| `acemq-ai-anthropic` | 11 | The hosted Messages API through the official Java SDK, with the SDK's retries set to 0 |

`acemq-ai-core` depends on `acemq-amqp-core` (whose version you choose; tested against
0.7.15), Jackson and SLF4J, all already on an AceMQ classpath. Micrometer is optional.

```xml
<repository>
  <id>acemq</id>
  <url>https://acemq.org/maven/</url>
</repository>

<dependency>
  <groupId>org.acemq</groupId>
  <artifactId>acemq-ai-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Configuration

`ModelClients.fromEnv()` reads `ACEMQ_AI_*` environment variables and `acemq.ai.*` system
properties; `ModelClients.fromConfig(map)` takes the same keys from anywhere.

| Key | Default | |
|---|---|---|
| `acemq.ai.provider` | `ollama` | `ollama`, `openai`, or a name a provider module registers (`anthropic`) |
| `acemq.ai.model` | **required** | no model name is built in; model names change faster than releases |
| `acemq.ai.base-url` | per provider | `http://localhost:11434`, `https://api.openai.com/v1`, ... |
| `acemq.ai.api-key-env` | per provider | the variable holding the key: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` |
| `acemq.ai.api-key` | | the key itself, for a configuration system that holds secrets |
| `acemq.ai.egress` | `any` | `local-only` refuses any endpoint that is not loopback or private, at startup |
| `acemq.ai.egress-allow` | | comma-separated hosts `local-only` also accepts |
| `acemq.ai.timeout` | `120` | seconds, or ISO-8601 (`PT5M`) |
| `acemq.ai.effort` | model default | hosted Messages API only: `low` ... `max` |

Keys never appear in `toString()`, exception messages or logs. See
[docs/providers.md](docs/providers.md) for each provider and the models we recommend.

## Offline: the egress guard

`acemq.ai.egress=local-only` (or `.egress(Egress.LOCAL_ONLY)`) makes building a client **fail
at startup** when its endpoint is not loopback, a private range, or an allow-listed host. The
error names the host and how to allow it. Every adapter applies it, the hosted ones included.
The library makes no network call other than the model call: no telemetry, no model-list fetch.
A model you name but have not pulled fails with the `ollama pull <model>` line.

## Metrics

| Name | Type | Tags |
|---|---|---|
| `acemq.ai.request.duration` | timer | `queue`, `provider`, `model`, `outcome` = `answered` / `timed_out` / `refused` / `failed` |
| `acemq.ai.tokens` | counter | `queue`, `provider`, `model`, `kind` = `input` / `output` / `cached_input` |
| `acemq.ai.budget.paused` | gauge | `queue` |

Recorded into Micrometer's global registry when Micrometer is on the classpath, or into
`MicrometerAiTelemetry.of(registry)` / your own `AiTelemetry`. **Prompts and replies are never
recorded or logged.** Retries and dead-lettering are counted by the consumer's own metrics.

## Testing your handlers

```java
ScriptedModel model = ScriptedModel.create()
        .rateLimited(Duration.ofSeconds(1))
        .reply("{\"queue\":\"billing\"}", 120, 8);
```

With `AceMq.connect("memory://test")` from `acemq-amqp-test`, the whole path, retry ladder and
dead-letter queue included, runs in the test JVM. `ScriptedModelServer` serves the same script
over HTTP for testing a real adapter. Nothing in this repository's CI calls a model;
`mvn verify -Plive` runs the `*LiveIT` tests against a local Ollama that already has a model
pulled, and against hosted providers when their keys are set. Each skips itself otherwise.

## What it will not do

An agent framework (tool-calling loops, memory, planners), RAG or vector stores, embedding
codecs, its own agent-to-agent protocol, or hosting and downloading models. Spring AI and
LangChain4j are agent frameworks; bridges to them are planned for phase 2.

## Requirements

Java 11 or newer at runtime; JDK 17 or newer to build. `acemq-java-amqp` 0.7.15 or newer.

## Licence

Apache 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). No model weights ship here, and no
model name is built in; check each model's own licence before you pin it.
