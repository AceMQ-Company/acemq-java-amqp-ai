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

import java.util.Locale;

/** Where model calls may go. Set with {@code acemq.ai.egress}. */
public enum Egress {

    /** Anywhere the configuration points. The default. */
    ANY,

    /**
     * Only loopback, private addresses, and hosts named in {@code acemq.ai.egress-allow}. A
     * client pointed anywhere else fails when it is built, before any message is consumed, so
     * input that must not leave the machine cannot leave it by a configuration mistake.
     */
    LOCAL_ONLY;

    /** Parses {@code any} or {@code local-only}, in any case. */
    public static Egress parse(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        switch (v) {
            case "any":
                return ANY;
            case "local-only":
            case "local":
                return LOCAL_ONLY;
            default:
                throw new IllegalArgumentException("acemq.ai.egress is 'any' or 'local-only', not '" + value + "'");
        }
    }
}
