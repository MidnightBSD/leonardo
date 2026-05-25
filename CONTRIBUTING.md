# Contributing to Leonardo

Thanks for your interest in contributing to Leonardo. This document covers the
basics; more detail will land as the project matures past M0.

## Licensing

Leonardo is licensed under the Apache License, Version 2.0. By submitting a
contribution, you agree that your work may be redistributed under those terms.

Every new source file must carry the SPDX header:

```java
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
```

## Development setup

You need:

- JDK 21 (Temurin or OpenJDK 21 from your distribution)
- No system Gradle — use the wrapper (`./gradlew`)

To build and test:

```sh
./gradlew build
```

To run the daemon locally:

```sh
./gradlew :leonardo-app:run
```

This activates the `dev` Micronaut environment, which writes to
`/tmp/leonardo-dev` and skips fsyncs for speed.

## Code style

- Java 21, no preview features
- 4-space indent, no tabs, LF line endings
- `final` on locals and parameters wherever it adds clarity
- Records for immutable data; explicit classes only when behavior is needed
- Compiler runs with `-Werror -Xlint:all` — fix the warning, don't suppress it
  (rare suppressions need a comment explaining why)

## Tests

- Unit tests next to the code they cover (`src/test/java`)
- Use JUnit 5 + AssertJ
- Storage-layer invariants get property tests via jqwik
- Integration tests that need the full app context use Micronaut's
  `@MicronautTest` (lives in `leonardo-api/src/test/java`)

## Pull request flow

1. File or pick up an issue describing what you're solving
2. Branch off `main` named `<initials>/<short-slug>` (e.g., `lh/sigv4-trailers`)
3. Keep commits small and explain *why*, not just *what*
4. Run `./gradlew build` locally before pushing — CI runs the same command
5. One reviewer for routine work; two for anything touching the storage layer,
   the wire-level S3 contract, or the auth path

## Reporting security issues

Do not file public GitHub issues for security vulnerabilities. Email
security@midnightbsd.org with details. We will acknowledge within 72 hours.
