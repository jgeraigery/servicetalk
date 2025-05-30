/*
 * Copyright © 2018-2019 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.concurrent.api;

import io.servicetalk.concurrent.api.SubscribableSources.SubscribablePublisher;

import java.util.function.Supplier;

import static io.servicetalk.concurrent.internal.SubscriberUtils.deliverErrorFromSource;
import static java.util.Objects.requireNonNull;

/**
 * As returned by {@link Publisher#defer(Supplier)}.
 *
 * @param <T> Type of items emitted by this {@link Publisher}.
 */
final class PublisherDefer<T> extends SubscribablePublisher<T> {
    private final Supplier<? extends Publisher<? extends T>> publisherFactory;

    PublisherDefer(Supplier<? extends Publisher<? extends T>> publisherFactory) {
        this.publisherFactory = requireNonNull(publisherFactory);
    }

    @Override
    protected void handleSubscribe(Subscriber<? super T> subscriber) {
        final Publisher<? extends T> publisher;
        try {
            publisher = requireNonNull(publisherFactory.get(), () -> "Factory " + publisherFactory + " returned null");
        } catch (Throwable cause) {
            deliverErrorFromSource(subscriber, cause);
            return;
        }
        // There are technically two sources, this one and the one returned by the factory.
        // Since, we are invoking user code (publisherFactory) we need this method to be run using an Executor
        // and also use the configured Executor for subscribing to the Publisher returned from publisherFactory.
        publisher.subscribeInternal(subscriber);
    }
}
