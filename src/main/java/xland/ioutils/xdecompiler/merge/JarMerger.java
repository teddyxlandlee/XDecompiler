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
package xland.ioutils.xdecompiler.merge;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassVisitor;
import xland.ioutils.xdecompiler.util.CommonUtils;
import xland.ioutils.xdecompiler.util.ConcurrentUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class JarMerger implements AutoCloseable {
    private final ZipFile inputClient, inputServer;
    private final ZipOutputStream output;
    private final @Nullable ZipOutputStream outputResources;

    private boolean removeSnowmen = false;
    private boolean offsetSyntheticsParams = false;

    public JarMerger(File inputClient, File inputServer,
            /*new*/ ZipOutputStream output, @Nullable ZipOutputStream outputResources) throws IOException {
        this.inputClient = new ZipFile(inputClient);
        this.inputServer = new ZipFile(inputServer);
        this.output = output;
        this.outputResources = outputResources;
    }

    private static void readToMap(ZipFile file, Map<String, Entry> entries, @Nullable ZipOutputStream outputRes) throws UncheckedIOException {
        file.stream().forEach(e -> {
            if (e.isDirectory()) return;

            final String fn = e.getName();
            if (!fn.endsWith(".class")) {
                if ("META-INF/MANIFEST.MF".equals(fn)) {
                    entries.put("META-INF/MANIFEST.MF", new Entry(
                            fn, """
                            Manifest-Version: 1.0
                            Main-Class: net.minecraft.client.Main
                            """.getBytes(StandardCharsets.UTF_8)
                    ));
                } else {
                    if (fn.startsWith("META-INF/")) {
                        if (fn.endsWith(".SF") || fn.endsWith(".RSA")) {
                            return;
                        }
                    }
                    if (outputRes == null) return;

                    try {
                        outputRes.putNextEntry(e);
                        try (InputStream is = file.getInputStream(e)) {
                            is.transferTo(outputRes);
                        }
                        outputRes.closeEntry();
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }

                return;
            }

            byte[] output;
            try (InputStream is = file.getInputStream(e)) {
                output = is.readAllBytes();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }

            entries.put(fn, new Entry(fn, output));
        });
    }

    public void merge() throws RuntimeException {
        Map<String, Entry> entriesClient = new LinkedHashMap<>();
        Map<String, Entry> entriesServer = new LinkedHashMap<>();

        boolean mergeSuccess;
        Throwable mergeFailure = null;
        try (ExecutorService service = ConcurrentUtils.namedVirtualThreadExecutor("jar-merger")) {
            service.submit(() -> readToMap(inputClient, entriesClient, outputResources));
            service.submit(() -> readToMap(inputServer, entriesServer, null));
            service.shutdown();

            try {
                mergeSuccess = service.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                mergeSuccess = false;
                mergeFailure = e;
            }
        }
        if (!mergeSuccess) {
            RuntimeException ex = new RuntimeException("Merge failed");
            if (mergeFailure != null) {
                ex.initCause(mergeFailure);
            }
            throw ex;
        }
        
        Set<String> entriesAll = LinkedHashSet.newLinkedHashSet(entriesClient.size() + entriesServer.size());
        entriesAll.addAll(entriesClient.keySet());
        entriesAll.addAll(entriesServer.keySet());

        ArrayList<UnaryOperator<ClassVisitor>> mergerExtraTransformers = new ArrayList<>(2);
        if (this.removeSnowmen) mergerExtraTransformers.add(SnowmanClassVisitor::new);
        if (this.offsetSyntheticsParams) mergerExtraTransformers.add(SyntheticParameterClassVisitor::new);
        
        ClassMerger cm = new ASMClassMerger(mergerExtraTransformers);

        BlockingQueue<Entry> entryQueue = new ArrayBlockingQueue<>(entriesAll.size());
        AtomicBoolean isComplete = new AtomicBoolean();

        Thread writingThread = Thread.ofVirtual().name("jar-merger-writer").start(() -> {
            while (!isComplete.get() || !entryQueue.isEmpty()) {
                try {
                    Entry e = entryQueue.poll();
                    if (e == null) continue;

                    output.putNextEntry(new ZipEntry(e.path));
                    output.write(e.data);
                    output.closeEntry();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        });

        entriesAll.parallelStream().forEach((String entry) -> {
            boolean isClass = entry.endsWith(".class");
            boolean isMinecraft = entriesClient.containsKey(entry) || entry.startsWith("net/minecraft/") || !entry.contains("/");

            Entry result;

            Entry entry1 = entriesClient.get(entry);
            Entry entry2 = entriesServer.get(entry);
            assert entry1 != null || entry2 != null;
            // entry1 != null -> isMinecraft
            assert !isMinecraft || entry1 != null;

            if (!isClass) {
                // Arbitrarily choose entry1
                result = Objects.requireNonNullElse(entry1, entry2);
            } else if (!isMinecraft && entry1 == null) {
                // server-only non-minecraft classes
                // Server may bundle libraries (before net.minecraft.bundler was introduced), client doesn't - skip them
                return;
            } else {
                result = new Entry(entry, cm.merge(Entry.getData(entry1), Entry.getData(entry2)));
            }

            entryQueue.add(result);
        });

        isComplete.set(true);
        try {
            writingThread.join();
        } catch (InterruptedException e) {
            CommonUtils.sneakyThrow(e);
        }
    }

    private record Entry(String path, byte[] data) {
        @Contract("null -> null")
        private static byte @Nullable[] getData(@Nullable Entry entry) {
            return entry != null ? entry.data() : null;
        }
    }

    @Override
    public void close() throws Exception {
        inputClient.close();
        inputServer.close();
        output.finish();    // no need to close
        if (outputResources != null)
            outputResources.finish();
    }

    public void enableSnowmanRemoval() {
        removeSnowmen = true;
    }

    public void enableSyntheticParamsOffset() {
        offsetSyntheticsParams = true;
    }
}
