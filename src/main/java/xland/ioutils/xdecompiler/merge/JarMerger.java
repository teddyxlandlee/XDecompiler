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

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import xland.ioutils.xdecompiler.util.LogUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class JarMerger implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
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
                    entries.put("META-INF/MANIFEST.MF", new Entry(fn,
                            "Manifest-Version: 1.0\nMain-Class: net.minecraft.client.Main\n".getBytes(StandardCharsets.UTF_8)));
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

        ExecutorService service = Executors.newFixedThreadPool(2);
        service.submit(() -> readToMap(inputClient, entriesClient, outputResources));
        service.submit(() -> readToMap(inputServer, entriesServer, null));
        service.shutdown();
        
        boolean mergeSuccess;
        try {
            mergeSuccess = service.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            mergeSuccess = false;
        }
        if (!mergeSuccess) {
            throw new RuntimeException("Merge failed");
        }
        
        Set<String> entriesAll = new LinkedHashSet<>();
        entriesAll.addAll(entriesClient.keySet());
        entriesAll.addAll(entriesServer.keySet());
        
        ClassMerger cm = new ClassMerger();

        entriesAll.parallelStream().map((entry) -> {
            boolean isClass = entry.endsWith(".class");

            boolean isMinecraft = entriesClient.containsKey(entry) || entry.startsWith("net/minecraft/") || !entry.contains("/");
            Entry result;
            String side = null;

            Entry entry1 = entriesClient.get(entry);
            Entry entry2 = entriesServer.get(entry);

            if (entry1 != null && entry2 != null) {
                if (Arrays.equals(entry1.data, entry2.data)) {
                    result = entry1;
                } else if (isClass) {
                    result = new Entry(entry1.path, cm.merge(entry1.data, entry2.data));
                } else {
                    result = entry1;
                }
            } else if ((result = entry1) != null) {
                side = "CLIENT";
            } else if ((result = entry2) != null) {
                side = "SERVER";
            }

            if (isClass && !isMinecraft && "SERVER".equals(side)) {
                // Server bundles libraries, client doesn't - skip them
                return null;
            }

            if (result != null) {
                if (isMinecraft && isClass) {
                    if (LOGGER.isDebugEnabled()) {
                        String cvr = ClassVersionUtil.classVersion1(result.data);
                        if (cvr != null)
                            LOGGER.debug("Merging {}, class version {}", result.path, cvr);
                    }
                    byte[] data = result.data;
                    ClassReader reader = new ClassReader(data);
                    ClassWriter writer = new ClassWriter(0);
                    ClassVisitor visitor = writer;

                    if (side != null) {
                        visitor = new ClassMerger.SidedClassVisitor(Opcodes.ASM9, visitor, side);
                    }

                    if (removeSnowmen) {
                        visitor = new SnowmanClassVisitor(Opcodes.ASM9, visitor);
                    }

                    if (offsetSyntheticsParams) {
                        visitor = new SyntheticParameterClassVisitor(Opcodes.ASM9, visitor);
                    }

                    if (visitor != writer) {
                        reader.accept(visitor, 0);
                        data = writer.toByteArray();
                        result = new Entry(result.path, data);
                    }
                }

                return result;
            } else {
                return null;
            }
        }).filter(Objects::nonNull).forEachOrdered(e -> {
            try {
                output.putNextEntry(new ZipEntry(e.path));
                output.write(e.data);
                output.closeEntry();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    public record Entry(String path, byte[] data) {}

    @Override
    public void close() throws Exception {
        inputClient.close();
        inputServer.close();
        output.finish();
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
