# Security

Report a vulnerability privately through GitHub's security advisories for this repository,
not in a public issue.

## What this library is careful about

**Data leaving the machine.** With `acemq.ai.egress=local-only`, a client whose endpoint is not
loopback, a private address or an allow-listed host is refused when it is built, before any
message is consumed. The library makes no network call besides the model call. Prompts and
replies are never logged and never put on metrics. A host name is resolved once, at startup;
if DNS for an allow-listed name could be changed by an attacker, allow an IP literal instead.

**API keys.** Keys come from configuration or the environment. They are never written to
`toString()`, exception messages or logs. The tests use fake keys, and the live tests read real
ones from the environment without printing them.

**Prompt injection.** A message body is untrusted input. A handler that puts a body into a
prompt is giving whoever published that message a voice in the conversation. Keep the
instructions in the system prompt, frame message content as data, constrain the reply with a
response schema, and treat what the model returns as untrusted too: validate it before acting
on it, and never let it choose a routing key, a queue or a command without an allow-list.

**Cost.** A retry storm against a paid API costs money. The adapters never retry on their own,
the retry ladder has a maximum attempt count, a repair is attempted at most once, and
`TokenBudget` pauses consumers before a window is overspent by more than what is in flight.
