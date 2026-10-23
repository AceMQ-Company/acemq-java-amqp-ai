# Changelog

All notable changes to this project are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the version is `0.x` the public API may change in any release.

This library has its own version line, starting at `0.1.0`. Each release names
the `acemq-java-amqp` version it was tested against.

## [Unreleased]

## [0.1.1] - 2026-10-22

### Changed

- Built and tested against `acemq-java-amqp` 0.7.20, from 0.7.18. Nothing in
  this library's API changed. What a queue handler gains from the library:
  - **A park or dead letter the broker refuses is requeued after a one-second
    pause** (0.7.20) instead of at once, so a model request that cannot be
    parked or dead-lettered is no longer refused at broker speed until a
    quorum queue's delivery limit drops it. The pause holds one prefetch slot.
  - **Closing a consumer waits for the handler that is running**, up to 20
    seconds (0.7.19), so a model call in flight finishes and acks instead of
    being redelivered and paid for twice.
  - **A pipeline hop keeps unknown `x-acemq-` headers** and the outbox relay
    publishes a record's headers (0.7.19).

## [0.1.0] - 2026-10-21

The first release. Phase 1, tested against `acemq-java-amqp` 0.7.18.

### Added

- **`ModelClient`**, with `ModelRequest`, `ModelResponse`, `Usage` and
  `StopReason`. `ModelResponse.as(Class)` parses a JSON reply, stripping a
  Markdown fence first.
- **`ModelException`** and its `Kind`: one classification for every adapter.
  Rate limits, overload, unavailability and timeouts are retryable; bad
  requests, authentication failures, a context that is too long, refusals and
  schema mismatches are not. `retry-after` is read as seconds or an HTTP date.
- **Ollama adapter** (native `/api/chat`) and **OpenAI-compatible adapter**
  (`/chat/completions`), on `java.net.http`, adding no jars.
- **Hosted Messages API adapter** in its own artifact, through the official
  SDK with its retries set to 0, structured output and `effort`.
- **Configuration**: `ModelClients.fromEnv()`, `fromConfig(map)` and
  `ModelConfig`, with providers found through `ServiceLoader`. No model name is
  built in. Keys come from configuration or the environment and are never
  printed.
- **Egress guard**: `acemq.ai.egress=local-only` refuses, at startup, any
  endpoint that is not loopback, a private address or allow-listed.
- **`AiHandler`** and **`AceMqAi.consume`**: a rate limit pauses the consumer
  for `retry-after` and takes a retry rung; fatal outcomes go to the dead-letter
  queue; a refusal can go to a fallback model once; a schema mismatch can earn
  one repair.
- **`TokenBudget`**: a per-window watermark that pauses every consumer sharing
  it and resumes them when the window refills.
- **`AiFunction`**, for a model behind `AceMq.respond`.
- **Metrics**: `acemq.ai.request.duration`, `acemq.ai.tokens` and
  `acemq.ai.budget.paused`, into Micrometer when it is present.
- **`acemq-ai-test`**: `ScriptedModel` and `ScriptedModelServer`.
