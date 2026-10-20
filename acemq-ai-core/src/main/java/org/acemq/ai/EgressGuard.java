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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Locale;

/**
 * The {@link Egress#LOCAL_ONLY} check, run when a client is built.
 *
 * <p>Every adapter calls it, the hosted ones included, so a hosted provider configured under
 * {@code local-only} is refused at startup rather than discovered in a network capture.
 */
public final class EgressGuard {

    private EgressGuard() {
    }

    /**
     * @throws IllegalStateException under {@link Egress#LOCAL_ONLY} when {@code endpoint} is not
     *     loopback, a private address, or one of {@code allowedHosts}; the message names the host
     *     and says how to allow it
     */
    public static void check(Egress egress, String provider, URI endpoint, Collection<String> allowedHosts) {
        if (egress != Egress.LOCAL_ONLY) {
            return;
        }
        String host = endpoint.getHost();
        if (host == null) {
            throw new IllegalStateException("acemq.ai.egress=local-only cannot check " + endpoint + ": it has no host");
        }
        for (String allowed : allowedHosts) {
            if (allowed.trim().equalsIgnoreCase(host)) {
                return;
            }
        }
        if (isLocalLiteral(host) || resolvesOnlyToLocal(host)) {
            return;
        }
        throw new IllegalStateException("acemq.ai.egress=local-only refuses the " + provider + " client for "
                + endpoint + ": " + host + " is not loopback or a private address. Point it at a local server, "
                + "or, if " + host + " is inside your network, allow it with acemq.ai.egress-allow=" + host);
    }

    /** Loopback or private, from the name alone: {@code localhost} or an IP literal. No DNS. */
    static boolean isLocalLiteral(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost")) {
            return true;
        }
        if (!looksLikeIp(h)) {
            return false;
        }
        try {
            // A literal never reaches DNS.
            return isLocal(InetAddress.getByName(h));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // ponytail: resolved once at startup; a name that later resolves elsewhere (DNS rebinding)
    // is not caught. Pin an IP literal or an allow-listed host where that matters.
    private static boolean resolvesOnlyToLocal(String host) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) {
                if (!isLocal(a)) {
                    return false;
                }
            }
            return all.length > 0;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        // IPv6 unique local addresses, fc00::/7: Java's isSiteLocalAddress covers only fec0::/10.
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static boolean looksLikeIp(String h) {
        return h.indexOf(':') >= 0 || h.startsWith("[") || h.matches("[0-9.]+");
    }
}
