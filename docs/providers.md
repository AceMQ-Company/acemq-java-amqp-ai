# Providers

Every provider is selected by `acemq.ai.provider` and gets its key from configuration or the
environment. None of them retries on its own: the consumer's retry ladder does.

| Provider | `acemq.ai.provider` | Artifact | Endpoint (default) | Key |
|---|---|---|---|---|
| Ollama, native API | `ollama` | `acemq-ai-core` | `http://localhost:11434/api/chat` | none (an auth proxy's bearer token if you set one) |
| OpenAI-compatible | `openai` | `acemq-ai-core` | `https://api.openai.com/v1/chat/completions` | `OPENAI_API_KEY`; optional for local servers |
| Anthropic Messages API | `anthropic` | `acemq-ai-anthropic` | `https://api.anthropic.com/v1/messages` | `ANTHROPIC_API_KEY` |

## Ollama

The native `POST /api/chat`, not streamed, because only the native endpoint reports token
counts (`prompt_eval_count`, `eval_count`) and takes a JSON schema in `format`.
`ModelRequest.option(...)` values go into Ollama's `options` (`seed`, `temperature`,
`num_ctx`, ...), except `keep_alive` and `think`, which are top-level. A 404 for a model that
was never pulled becomes a fatal error naming the `ollama pull` command.

## OpenAI-compatible

One adapter for vLLM, LM Studio, the llama.cpp server, Ollama's `/v1` and hosted OpenAI. Set
`acemq.ai.base-url` to the server's `/v1` root. The output limit is sent as
`max_completion_tokens` to api.openai.com and as `max_tokens` elsewhere. A response schema goes
out as `response_format: {type: json_schema}`. `message.refusal` or `finish_reason:
content_filter` is a refusal. Options are top-level request fields. Features that exist only
in OpenAI's Responses API need a framework bridge.

## Anthropic Messages API

Through the official Java SDK (`com.anthropic:anthropic-java`), with its `maxRetries` set to 0.
A response schema goes out as structured output (`output_config.format`), and
`acemq.ai.effort` (or `option("effort", ...)`) as `output_config.effort`. Sampling options such
as `temperature` are never sent: current models reject them with a 400. A 529
`overloaded_error` is retryable; `stop_reason: refusal` is a refusal. Cached input
(`cache_read_input_tokens`) is counted as input and reported as `cached_input`.

## Recommended models

The library builds in no model name. These are recommendations, re-checked every release
round. Check each model's licence before you pin it.

| Use | Model | Notes |
|---|---|---|
| Local, code analysis (the migration agent) | `qwen3-coder:30b` | about 19 GB, 256K context |
| Local, general, 16 GB machine | `gpt-oss:20b` | Apache 2.0, about 14 GB |
| Hosted, default | `claude-opus-5-5` | thinking is always on; effort defaults to `medium` |
| Hosted, balanced | `claude-sonnet-5-5` | |
| Hosted, bulk and cheap | `claude-haiku-4-5-20251001` | |
| Hosted, OpenAI | `gpt-6.1-sol`, `gpt-6-astra` | through `openai` |
