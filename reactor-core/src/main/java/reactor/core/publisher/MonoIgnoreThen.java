/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;

/**
 * Concatenates a several Mono sources with a final Mono source by
 * ignoring values from the first set of sources and emitting the value
 * the last Mono source generates.
 *
 * @param <T> the final value type
 */
final class MonoIgnoreThen<T> extends Mono<T> implements Fuseable, Scannable {

    final Publisher<?>[] ignore;
    
    final Mono<T> last;
    
    MonoIgnoreThen(Publisher<?>[] ignore, Mono<T> last) {
        this.ignore = Objects.requireNonNull(ignore, "ignore");
        this.last = Objects.requireNonNull(last, "last");
    }
    
    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
        ThenIgnoreMain<T> manager = new ThenIgnoreMain<>(actual, this.ignore, this.last);
        actual.onSubscribe(manager);
    }
    
    /**
     * Shifts the current last Mono into the ignore array and sets up a new last Mono instance.
     * @param <U> the new last value type
     * @param newLast the new last Mono instance
     * @return the new operator set up
     */
    <U> MonoIgnoreThen<U> shift(Mono<U> newLast) {
        Objects.requireNonNull(newLast, "newLast");
        Publisher<?>[] a = this.ignore;
        int n = a.length;
        Publisher<?>[] b = new Publisher[n + 1];
        System.arraycopy(a, 0, b, 0, n);
        b[n] = this.last;
        
        return new MonoIgnoreThen<>(b, newLast);
    }

    @Override
    public Object scanUnsafe(Attr key) {
        return null; //no particular key to be represented, still useful in hooks
    }
    
    static final class ThenIgnoreMain<T> implements InnerOperator<T, T> {
        
        final Publisher<?>[] ignoreMonos;

        final Mono<T> lastMono;

        final CoreSubscriber<? super T> actual;

        int index;

        volatile Subscription activeSubscription;

        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<ThenIgnoreMain, Subscription> ACTIVE_SUBSCRIPTION =
                AtomicReferenceFieldUpdater.newUpdater(ThenIgnoreMain.class, Subscription.class, "activeSubscription");
        
        ThenIgnoreMain(CoreSubscriber<? super T> subscriber,
		        Publisher<?>[] ignoreMonos, Mono<T> lastMono) {
            this.actual = subscriber;
            this.ignoreMonos = ignoreMonos;
            this.lastMono = lastMono;

            ACTIVE_SUBSCRIPTION.lazySet(this, Operators.EmptySubscription.INSTANCE);
        }

        @Override
        public CoreSubscriber<? super T> actual() {
            return this.actual;
        }

        @Override
        public void cancel() {
            Operators.terminate(ACTIVE_SUBSCRIPTION, this);
        }

        @Override
        public void request(long n) {
            final Subscription current = this.activeSubscription;
            if (current == Operators.EmptySubscription.INSTANCE && ACTIVE_SUBSCRIPTION.getAndSet(this, null) == Operators.EmptySubscription.INSTANCE) {
                subscribeNext();
            }
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (Operators.setOnce(ACTIVE_SUBSCRIPTION, this, s)) {
                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(T t) {
            if (this.index != this.ignoreMonos.length) {
                // ignored
                Operators.onDiscard(t, this.actual.currentContext());
                return;
            }

            Subscription current = this.activeSubscription;
            if (current == Operators.CancelledSubscription.INSTANCE) {
                Operators.onDiscard(t, this.actual.currentContext());
                return;
            }

            this.actual.onNext(t);
        }

        @Override
        public void onComplete() {
            if (this.index != this.ignoreMonos.length) {
                ACTIVE_SUBSCRIPTION.lazySet(this, null);
                this.index++;
                subscribeNext();
                return;
            }

            Subscription current = this.activeSubscription;
            if (current == Operators.CancelledSubscription.INSTANCE || ACTIVE_SUBSCRIPTION.getAndSet(
                    this,
                    Operators.CancelledSubscription.INSTANCE) == Operators.CancelledSubscription.INSTANCE) {
                return;
            }

            this.actual.onComplete();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void subscribeNext() {
            final Publisher<?>[] a = this.ignoreMonos;

            for (;;) {
                final int i = this.index;

                if (i == a.length) {
                    Mono<T> m = this.lastMono;
                    if (m instanceof Callable) {
                        T v;
                        try {
                            v = ((Callable<T>)m).call();
                        }
                        catch (Throwable ex) {
                            onError(Operators.onOperatorError(ex, this.actual.currentContext()));
                            return;
                        }

                        if (v != null) {
                            onNext(v);
                        }
                        onComplete();
                    } else {
                        m.subscribe(this);
                    }
                    return;
                } else {
                    final Publisher<?> m = a[i];

                    if (m instanceof Callable) {
                        try {
                            ((Callable<?>) m).call();
                        }
                        catch (Throwable ex) {
                            onError(Operators.onOperatorError(ex, this.actual.currentContext()));
                            return;
                        }

                        this.index = i + 1;
                        continue;
                    }

                    m.subscribe((CoreSubscriber) this);
                    return;
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            Subscription current = this.activeSubscription;
            if (current == Operators.CancelledSubscription.INSTANCE || ACTIVE_SUBSCRIPTION.getAndSet(
                    this,
                    Operators.CancelledSubscription.INSTANCE) == Operators.CancelledSubscription.INSTANCE) {
                Operators.onErrorDropped(t, this.actual.currentContext());
                return;
            }

            this.actual.onError(t);
        }
    }
}
