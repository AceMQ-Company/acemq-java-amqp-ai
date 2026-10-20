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

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.acemq.amqp.api.AceMqException;

/**
 * A model call that did not produce an answer, classified once so that every adapter agrees
 * on what is worth retrying.
 *
 * <p>The classification is the whole point. A retry that cannot succeed costs a model call
 * each time it runs, so {@link #isRetryable()} is true only for conditions that can clear on
 * their own: a rate limit, an overloaded or unreachable provider, a timeout. A bad request, an
 * authentication failure, a context that is too long, a refusal and a reply that does not
 * match its schema all fail the same way on every attempt and go to the dead-letter queue.
 */
public class ModelException extends AceMqException {

    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind {
        /** 429. The consumer pauses for {@code retry-after}; the message takes a retry rung. */
        RATE_LIMITED(true),
        /** 503, or 529 {@code overloaded_error}. */
        OVERLOADED(true),
        /** No answer within the timeout. Outcome {@code timed_out}. */
        TIMEOUT(true),
        /** Connection refused, another 5xx, or a reply that was not the protocol. */
        UNAVAILABLE(true),
        /** 400 or 404, including a model that is not installed. */
        BAD_REQUEST(false),
        /** The input does not fit the model's context window. */
        CONTEXT_TOO_LONG(false),
        /** 401 or 403. A missing or wrong API key. */
        AUTH(false),
        /** The model declined. Outcome {@code refused}. */
        REFUSED(false),
        /** The reply does not match the response schema. */
        SCHEMA_MISMATCH(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    private final Kind kind;
    private final Duration retryAfter;

    public ModelException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public ModelException(Kind kind, String message, Throwable cause) {
        this(kind, message, null, cause);
    }

    public ModelException(Kind kind, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    /** @return how long the provider asked us to wait, when it said */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public boolean isRetryable() {
        return kind.isRetryable();
    }

    /**
     * Classifies an HTTP error response. Shared by every adapter, so the table in
     * {@link Kind} is applied in one place.
     *
     * @param detail the response body or the provider's message; truncated, never logged whole
     * @param retryAfter the {@code retry-after} header value, or null
     */
    public static ModelException fromHttpStatus(String provider, int status, String detail, String retryAfter) {
        String d = detail == null ? "" : detail.length() > 500 ? detail.substring(0, 500) + "..." : detail;
        String message = provider + " answered HTTP " + status + (d.isEmpty() ? "" : ": " + d);
        return new ModelException(kindOf(status, d), message, parseRetryAfter(retryAfter), null);
    }

    static Kind kindOf(int status, String detail) {
        String d = detail.toLowerCase(Locale.ROOT);
        if (status == 429) {
            return Kind.RATE_LIMITED;
        }
        if (status == 503 || status == 529 || d.contains("overloaded_error")) {
            return Kind.OVERLOADED;
        }
        if (status == 408 || status == 504) {
            return Kind.TIMEOUT;
        }
        if (status >= 500) {
            return Kind.UNAVAILABLE;
        }
        if (status == 401 || status == 403) {
            return Kind.AUTH;
        }
        if (status == 413 || d.contains("context_length_exceeded") || d.contains("context length")
                || d.contains("context window") || d.contains("prompt is too long")) {
            return Kind.CONTEXT_TOO_LONG;
        }
        return Kind.BAD_REQUEST;
    }

    /** Seconds (integer or decimal) or an HTTP date. Null for absent or unreadable. */
    static Duration parseRetryAfter(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String v = value.trim();
        try {
            double seconds = Double.parseDouble(v);
            return seconds < 0 ? null : Duration.ofMillis((long) (seconds * 1000));
        } catch (NumberFormatException notSeconds) {
            try {
                Duration until = Duration.between(ZonedDateTime.now(),
                        ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME));
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }
}
