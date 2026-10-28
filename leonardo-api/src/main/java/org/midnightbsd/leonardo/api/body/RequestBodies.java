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
package org.midnightbsd.leonardo.api.body;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.ServerHttpRequest;

import java.io.IOException;
import java.io.InputStream;

/**
 * Helpers for request bodies that a handler answers without reading.
 *
 * <p>Netty only reads the socket while something is consuming the request
 * body. When a handler responds before touching the body (NoSuchUpload on
 * UploadPart, AccessDenied on PutObject, ...), the unread bytes stay in the
 * socket and the next keep-alive request on that connection is never parsed,
 * so the client hangs until its read timeout. Answering with
 * {@code Connection: close} instead is not safe either: closing a socket with
 * unread inbound data makes the kernel send a TCP reset, and on the BSDs that
 * reset discards the response the client was about to read. The reliable
 * option is to tell Micronaut to read and drop the rest of the body, which
 * {@link #discard} and {@link #closeQuietly} do.
 *
 * <p>The exception is a request that is still waiting for {@code 100 Continue}
 * (botocore and the AWS SDKs send {@code Expect: 100-continue} on uploads).
 * The client has not transmitted the body yet and, after a final status, is
 * entitled never to, so there is nothing to drain and the server must not sit
 * waiting for it. Nothing is unread on the socket either, so answering with
 * {@code Connection: close} is both safe and the only correct choice; see
 * {@link #awaitingContinue}.
 */
public final class RequestBodies {

    private RequestBodies() {
    }

    /**
     * Whether the client sent {@code Expect: 100-continue} and therefore has
     * not transmitted the body yet. Only meaningful before anything has
     * consumed the body, since consuming it is what triggers the interim
     * {@code 100 Continue}.
     */
    public static boolean awaitingContinue(final HttpRequest<?> request) {
        final String expect = request.getHeaders().get(HttpHeaders.EXPECT);
        return expect != null && expect.equalsIgnoreCase("100-continue");
    }

    /**
     * Marks the body of {@code request} as discardable so the server drains
     * and drops whatever the handler did not read. Safe to call on any
     * request, including ones whose body has already been consumed.
     */
    public static void discard(final HttpRequest<?> request) {
        if (request instanceof ServerHttpRequest<?> server) {
            try {
                server.byteBody().allowDiscard();
            } catch (final IllegalStateException alreadyClaimed) {
                // A handler already took ownership of the body; closing that
                // stream (see closeQuietly) is what discards the remainder.
            }
        }
    }

    /**
     * Closes a bound {@code @Body InputStream}. For a streamed body this
     * cancels the subscription, which makes Micronaut discard the unread
     * remainder while keeping the connection alive.
     */
    public static void closeQuietly(final InputStream body) {
        try {
            body.close();
        } catch (final IOException | RuntimeException ignored) {
            // Nothing useful to do; the response is already decided.
        }
    }
}
