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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Tokens per window, as back-pressure rather than refusal.
 *
 * <p>A watermark: when what is left in the window falls below what the messages that could
 * be in flight might spend ({@code prefetch × maxOutputTokens}), every consumer sharing the
 * budget pauses, and resumes when the window refills. No message is ever denied mid-flight,
 * so the most a window can overspend is that reservation. One budget may be shared by
 * several consumers, which is how one API key's quota is shared.
 */
public final class TokenBudget {

    static final String REASON = "token budget";

    private final long limit;
    private final long windowNanos;
    private final List<Pauser> pausers = new CopyOnWriteArrayList<>();
    private long windowStart = System.nanoTime();
    private long used;
    private boolean exhausted;
    private long exhaustedWindow;

    private TokenBudget(long limit, Duration window) {
        if (limit <= 0 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("a token budget needs a positive limit and window");
        }
        this.limit = limit;
        this.windowNanos = window.toNanos();
    }

    public static TokenBudget perMinute(long tokens) {
        return new TokenBudget(tokens, Duration.ofMinutes(1));
    }

    public static TokenBudget per(Duration window, long tokens) {
        return new TokenBudget(tokens, window);
    }

    /** @return tokens left in the current window */
    public synchronized long remaining() {
        roll();
        return Math.max(0, limit - used);
    }

    /** @return whether the budget is holding its consumers paused */
    public synchronized boolean isPaused() {
        return exhausted;
    }

    void attach(Pauser pauser) {
        pausers.add(pauser);
        synchronized (this) {
            if (exhausted) {
                pauser.pause(REASON);
            }
        }
    }

    /** Counts a call's tokens; pauses everyone when what is left cannot cover {@code reserve}. */
    void record(Usage usage, long reserve) {
        long untilRefill;
        synchronized (this) {
            roll();
            used += usage.total();
            if (exhausted || limit - used >= reserve) {
                return;
            }
            exhausted = true;
            exhaustedWindow = windowStart;
            untilRefill = windowNanos - (System.nanoTime() - windowStart);
        }
        pausers.forEach(p -> p.pause(REASON));
        Pauser.SCHEDULER.schedule(this::refill, Math.max(1, untilRefill), TimeUnit.NANOSECONDS);
    }

    private void refill() {
        synchronized (this) {
            roll();
            if (windowStart == exhaustedWindow) {  // fired a hair early; try again at the boundary
                Pauser.SCHEDULER.schedule(this::refill,
                        Math.max(1, windowNanos - (System.nanoTime() - windowStart)), TimeUnit.NANOSECONDS);
                return;
            }
            exhausted = false;
        }
        pausers.forEach(p -> p.resume(REASON));
    }

    private void roll() {
        long now = System.nanoTime();
        long elapsed = now - windowStart;
        if (elapsed >= windowNanos) {
            windowStart = now - elapsed % windowNanos;
            used = 0;
        }
    }

    @Override
    public synchronized String toString() {
        return "TokenBudget{limit=" + limit + " per " + Duration.ofNanos(windowNanos) + ", used=" + used + "}";
    }
}
