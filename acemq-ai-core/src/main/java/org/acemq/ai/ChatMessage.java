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

import java.util.Objects;

/**
 * One turn of a conversation. The system prompt is not a turn: it goes on
 * {@link ModelRequest.Builder#system(String)}, because providers disagree about where it lives.
 */
public final class ChatMessage {

    /** Who said it. */
    public enum Role { USER, ASSISTANT }

    private final Role role;
    private final String text;

    private ChatMessage(Role role, String text) {
        this.role = Objects.requireNonNull(role, "role");
        this.text = Objects.requireNonNull(text, "text");
    }

    /** @return a turn from the user */
    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text);
    }

    /** @return a turn from the model */
    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text);
    }

    public Role role() {
        return role;
    }

    public String text() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChatMessage)) {
            return false;
        }
        ChatMessage that = (ChatMessage) other;
        return role == that.role && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, text);
    }

    /** The length only: a prompt is customer data and does not belong in a log line. */
    @Override
    public String toString() {
        return role + "(" + text.length() + " chars)";
    }
}
