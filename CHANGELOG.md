# Changelog

All notable changes to this project are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the version is `0.x` the public API may change in any release.

This library has its own version line, starting at `0.1.0`. Each release names
the `acemq-java-amqp` version it was tested against.

## [Unreleased]

Phase 1, tested against `acemq-java-amqp` 0.7.17.

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
