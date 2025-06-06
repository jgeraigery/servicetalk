/*
 * Copyright © 2018, 2021 Apple Inc. and the ServiceTalk project authors
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

import io.servicetalk.concurrent.Cancellable;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * An {@link Executor} that simply delegates all calls to another {@link Executor}.
 */
public class DelegatingExecutor extends DelegatingListenableAsyncCloseable<Executor> implements Executor {

    /**
     * New instance.
     *
     * @param delegate {@link Executor} to delegate all calls to.
     */
    protected DelegatingExecutor(final Executor delegate) {
        super(delegate);
    }

    /**
     * Get the {@link Executor} that this class delegates to.
     *
     * @return the {@link Executor} that this class delegates to.
     */
    @Override
    @SuppressWarnings("PMD.UselessOverridingMethod") // Method is overridden to preserve binary compatibility
    protected Executor delegate() {
        return super.delegate();
    }

    @Override
    public Cancellable execute(final Runnable task) throws RejectedExecutionException {
        return delegate().execute(task);
    }

    @Override
    public Cancellable schedule(final Runnable task, final long delay, final TimeUnit unit)
            throws RejectedExecutionException {
        return delegate().schedule(task, delay, unit);
    }

    @Override
    public Cancellable schedule(final Runnable task, final Duration delay) throws RejectedExecutionException {
        return delegate().schedule(task, delay);
    }

    @Override
    public Completable timer(final long delay, final TimeUnit unit) {
        return delegate().timer(delay, unit);
    }

    @Override
    public Completable timer(final Duration delay) {
        return delegate().timer(delay);
    }

    @Override
    public Completable submit(final Runnable runnable) {
        return delegate().submit(runnable);
    }

    @Override
    public Completable submitRunnable(final Supplier<Runnable> runnableSupplier) {
        return delegate().submitRunnable(runnableSupplier);
    }

    @Override
    public <T> Single<T> submit(final Callable<? extends T> callable) {
        return delegate().submit(callable);
    }

    @Override
    public <T> Single<T> submitCallable(final Supplier<? extends Callable<? extends T>> callableSupplier) {
        return delegate().submitCallable(callableSupplier);
    }

    @Override
    public long currentTime(TimeUnit unit) {
        return delegate().currentTime(unit);
    }
}
