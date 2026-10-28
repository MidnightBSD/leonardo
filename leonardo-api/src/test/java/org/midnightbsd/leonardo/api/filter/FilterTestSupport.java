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
package org.midnightbsd.leonardo.api.filter;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs a filter's synchronous publisher and returns the single response it emits. */
final class FilterTestSupport {

    private FilterTestSupport() {
    }

    static HttpResponse<?> first(final Publisher<MutableHttpResponse<?>> publisher) {
        final AtomicReference<HttpResponse<?>> holder = new AtomicReference<>();
        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(final Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(final MutableHttpResponse<?> response) {
                holder.set(response);
            }

            @Override
            public void onError(final Throwable t) {
                throw new AssertionError(t);
            }

            @Override
            public void onComplete() {
                // nothing to do
            }
        });
        assertThat(holder.get()).as("filter emitted a response").isNotNull();
        return holder.get();
    }
}
