/*
 * Copyright 2023 teddyxlandlee
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xland.ioutils.xdecompiler.util;

import org.jetbrains.annotations.Contract;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.stream.Stream;

public final class ConcurrentUtils {
    public static ExecutorService namedVirtualThreadExecutor(String prefix) {
        return Executors.newThreadPerTaskExecutor(ExecutorServiceFactory.VIRTUAL.threadFactory(prefix));
    }

    public static ExecutorService namedPlatformThreadExecutor(String prefix, int threadCount) {
        return ExecutorServiceFactory.PLATFORM.newService(prefix, threadCount);
    }

    @FunctionalInterface
    private interface ExecutorServiceFactory {
        Thread.Builder threadBuilder();

        default ThreadFactory threadFactory(String prefix) {
            return threadBuilder().name(prefix + '-', 1).factory();
        }

        @Contract("_, _ -> new")
        default ExecutorService newService(String prefix, int threadCount) {
            return Executors.newFixedThreadPool(threadCount, threadFactory(prefix));
        }

        ExecutorServiceFactory PLATFORM = Thread::ofPlatform;
        ExecutorServiceFactory VIRTUAL = Thread::ofVirtual;
    }

    private static class ThrowableHolder {
        volatile Throwable t0;

        synchronized void addSuppressed(Throwable t) {
            if (t0 == null)
                t0 = t;
            else
                t0.addSuppressed(t);
        }
    }

    public static void runVirtual(String prefix, Function<ExecutorService, Stream<CompletableFuture<Void>>> streamSupplier) {
        run0(namedVirtualThreadExecutor(prefix), streamSupplier);
    }

    public static void runPlatform(String prefix, int threadCount, Function<ExecutorService, Stream<CompletableFuture<Void>>> streamSupplier) {
        run0(namedPlatformThreadExecutor(prefix, threadCount), streamSupplier);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void run0(ExecutorService service, Function<ExecutorService, Stream<CompletableFuture<Void>>> streamSupplier) throws T {
        ThrowableHolder throwableHolder = new ThrowableHolder();

        try {
            CompletableFuture.allOf(streamSupplier.apply(service)
                    .map(f -> f.exceptionally(t -> {
                        throwableHolder.addSuppressed(t);
                        return null;
                    }))
                    .toArray(CompletableFuture[]::new)
            ).join();
        } finally {
            service.shutdown();
        }

        if (throwableHolder.t0 == null) return;
        throw (T) throwableHolder.t0;
    }

    private ConcurrentUtils() {}
}
