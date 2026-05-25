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
 * Path resolution for the on-disk layout (project plan §3). Pure path math —
 * no I/O. All other modules go through {@link
 * org.midnightbsd.leonardo.storage.layout.StorageLayout} rather than
 * hard-coding directory structures.
 */
package org.midnightbsd.leonardo.storage.layout;
