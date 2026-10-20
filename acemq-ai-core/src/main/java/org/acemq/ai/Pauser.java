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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.acemq.amqp.core.MessageConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pauses one consumer for any number of reasons and resumes it only when the last one clears,
 * so a rate limit ending cannot resume a consumer the token budget is still holding.
 *
 * <p>Nothing here sleeps in a consumer thread: resumption is scheduled.
 */
final class Pauser {

    private static final Logger LOG = LoggerFactory.getLogger(Pauser.class);

    static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "acemq-ai-resume");
        t.setDaemon(true);
        return t;
    });

    private final String queue;
    private final Set<String> reasons = new HashSet<>();
    private MessageConsumer consumer;
    private long rateLimitedUntil;

    Pauser(String queue) {
        this.queue = queue;
    }

    /** A pause asked for before the consumer existed is applied now. */
    synchronized void attach(MessageConsumer consumer) {
        if (this.consumer != null) {
            throw new IllegalStateException("this handler already serves a consumer on " + queue);
        }
        this.consumer = consumer;
        if (!reasons.isEmpty()) {
            consumer.pause();
        }
    }

    synchronized void pause(String reason) {
        if (reasons.add(reason) && reasons.size() == 1 && consumer != null) {
            consumer.pause();
        }
    }

    synchronized void resume(String reason) {
        if (reasons.remove(reason) && reasons.isEmpty() && consumer != null) {
            consumer.resume();
            LOG.info("resumed consuming {}", queue);
        }
    }

    synchronized boolean isPaused(String reason) {
        return reasons.contains(reason);
    }

    /** Pauses for at least {@code wait}; a longer wait asked for meanwhile extends it. */
    void pauseFor(String reason, Duration wait) {
        long until = System.nanoTime() + wait.toNanos();
        synchronized (this) {
            if (until - rateLimitedUntil <= 0 && reasons.contains(reason)) {
                return;
            }
            rateLimitedUntil = until;
        }
        LOG.warn("pausing {} for {} ms: {}", queue, wait.toMillis(), reason);
        pause(reason);
        SCHEDULER.schedule(() -> resumeIfDue(reason), wait.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void resumeIfDue(String reason) {
        synchronized (this) {
            if (System.nanoTime() - rateLimitedUntil < 0) {
                return;  // extended since; that later schedule resumes it
            }
        }
        resume(reason);
    }
}
