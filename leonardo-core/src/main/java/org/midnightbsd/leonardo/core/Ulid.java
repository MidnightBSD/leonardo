/*
 * Copyright 2026 The Leonardo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.core;

import java.security.SecureRandom;

/**
 * Generates Universally Unique Lexicographically Sortable Identifiers (ULIDs).
 *
 * <p>Format: 26 Crockford base-32 characters:
 * <ul>
 *   <li>10 chars — 48-bit millisecond timestamp (MSB first), sortable</li>
 *   <li>16 chars — 80-bit cryptographically random component</li>
 * </ul>
 *
 * <p>Used as server-generated object IDs when the caller does not supply
 * {@code x-leonardo-object-id} (project plan §6).
 */
public final class Ulid {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] CROCKFORD =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private Ulid() {}

    /** Generates a new ULID. Thread-safe. */
    public static String generate() {
        final long ts = System.currentTimeMillis();
        final var rand = new byte[10]; // 80 random bits
        RNG.nextBytes(rand);

        final var buf = new char[26];

        // Encode 48-bit timestamp in 10 Crockford base-32 chars (5 bits each, MSB first)
        long t = ts;
        for (int i = 9; i >= 0; i--) {
            buf[i] = CROCKFORD[(int) (t & 0x1F)];
            t >>>= 5;
        }

        // Encode 80-bit random in 16 Crockford base-32 chars (5 bits each)
        // Stream 10 bytes (80 bits) → 16 × 5-bit values
        int bits = 0;
        int bitsLeft = 0;
        int byteIdx = 0;
        for (int i = 10; i < 26; i++) {
            while (bitsLeft < 5) {
                bits = (bits << 8) | (rand[byteIdx++] & 0xFF);
                bitsLeft += 8;
            }
            bitsLeft -= 5;
            buf[i] = CROCKFORD[(bits >>> bitsLeft) & 0x1F];
        }

        return new String(buf);
    }
}
