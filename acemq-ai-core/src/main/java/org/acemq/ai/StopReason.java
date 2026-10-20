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

/** Why the model stopped writing. */
public enum StopReason {
    /** It finished. */
    END,
    /** It ran into {@link ModelRequest#maxOutputTokens()}; the text is cut off. */
    MAX_TOKENS,
    /** It declined, or a provider's content filter stopped it. Deterministic for the same input. */
    REFUSAL,
    /** It asked for a tool. This library runs no tools, so treat it as unexpected. */
    TOOL_USE,
    /** Anything the provider reported that has no name here. */
    OTHER
}
