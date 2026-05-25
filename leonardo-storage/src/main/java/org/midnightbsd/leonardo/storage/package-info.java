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

/**
 * Filesystem-backed storage layer. Atomic file writes, layout resolution,
 * striped locking. Designed to run safely on ZFS, NFS (with warnings), and any
 * POSIX filesystem that supports atomic renames.
 *
 * <p>See project plan §3 (on-disk layout) and §5 (concurrency model).
 */
package org.midnightbsd.leonardo.storage;
