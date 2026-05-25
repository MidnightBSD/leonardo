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
 * Leonardo daemon application module. Owns {@code main()}, the Micronaut
 * application context, and startup-time invariant checks. Other modules avoid
 * direct dependencies on this one — it's the composition root.
 */
package org.midnightbsd.leonardo.app;
