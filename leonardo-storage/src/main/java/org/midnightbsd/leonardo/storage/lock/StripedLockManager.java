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
package org.midnightbsd.leonardo.storage.lock;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Striped lock manager: maps a string key (bucket name, or {@code bucket/key})
 * to one of N {@link ReentrantReadWriteLock} instances. Reads acquire shared
 * locks; writes acquire exclusive ones. False contention between unrelated keys
 * is bounded by the stripe count.
 *
 * <p>Holds are short-lived in the storage layer — only across metadata
 * read/write, never across payload streaming. This is critical: long writes
 * (multi-GB PUTs) must not hold locks that block reads of other keys.
 *
 * <p>The default stripe count of 4096 is plenty for a single-node server; the
 * lock objects themselves are tiny so the memory overhead is negligible.
 */
public final class StripedLockManager {

    private static final int DEFAULT_STRIPES = 4096;

    private final ReentrantReadWriteLock[] stripes;
    private final int mask;

    public StripedLockManager() {
        this(DEFAULT_STRIPES);
    }

    public StripedLockManager(final int stripeCount) {
        if (Integer.bitCount(stripeCount) != 1) {
            throw new IllegalArgumentException("stripe count must be a power of two: " + stripeCount);
        }
        this.stripes = new ReentrantReadWriteLock[stripeCount];
        this.mask = stripeCount - 1;
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new ReentrantReadWriteLock();
        }
    }

    public ReentrantReadWriteLock lockFor(final String key) {
        // Spread bits with the Wang-Jenkins-style mix so keys that hash close
        // together don't all land on adjacent stripes.
        final int h = key.hashCode();
        final int spread = h ^ (h >>> 16);
        return stripes[spread & mask];
    }
}
