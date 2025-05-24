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
package xland.ioutils.xdecompiler.decompile;

import org.slf4j.Logger;
import xland.ioutils.xdecompiler.decompile.vineflower.VineFlowerEntrypoint;
import xland.ioutils.xdecompiler.mcmeta.HashMismatchException;
import xland.ioutils.xdecompiler.mcmeta.HashingUtil;
import xland.ioutils.xdecompiler.mcmeta.RemoteFile;
import xland.ioutils.xdecompiler.util.*;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

public class RemoteVineFlowerProvider implements DecompilerProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String VF_ENTRYPOINT = "xland.ioutils.xdecompiler.decompile.vineflower.VineFlowerEntrypoint";
    private static final Thread.Builder THREAD_BUILDER = Thread.ofPlatform().name("vineflower-instance-", 1);

    public RemoteVineFlowerProvider() {}

    @Override
    public String id() {
        return "vineflower";
    }

    @Override
    public void decompile(Path jarIn, Collection<Path> classpath, Path dirOut) {
        xland.ioutils.xdecompiler.util.DebugUtils.log(3, l -> {
            l.info("Listing decompile arguments due to debug flag 3");
            l.info("jarIn:\t{}", jarIn);
            l.info("dirOut:\t{}", dirOut);
            l.info("classpath:");
            classpath.forEach(p -> l.info("\t- {}", p));
            l.info("====================");
        });
        try (var classLoader = classLoader()) {
            String vineFlowerLogDir = PublicProperties.vineFlowerLogDir();
            PrintStream printStream = switch(vineFlowerLogDir) {
                case "", "/dev/null" -> new PrintStream(OutputStream.nullOutputStream());
                case null -> new PrintStream(OutputStream.nullOutputStream());  // should not happen
                case "/dev/stdout" -> System.out;
                case "/dev/stderr" -> System.err;
                default -> {
                    Path logFile = Path.of(vineFlowerLogDir).resolve(
                            dirOut.getFileName() + "-" + UUID.randomUUID().toString().substring(24) + ".txt"
                    );
                    LOGGER.info("Decompile log for {} will be dumped into {}", dirOut, logFile);
                    Files.createDirectories(logFile.getParent());
                    yield new PrintStream(Files.newOutputStream(logFile));
                }
            };

            final List<?> arguments = List.of(
                    FileUtils.pathToFile(jarIn),
                    classpath.stream().map(FileUtils::pathToFile).toList(),
                    FileUtils.pathToFile(dirOut),
                    printStream
            );

            // CPU-consuming task, requiring a platform thread
            var thread = THREAD_BUILDER.unstarted(() -> {
                var lookup = MethodHandles.lookup();
                try {
                    MethodHandle mh = lookup.findConstructor(
                            Class.forName(
                                    VF_ENTRYPOINT,
                                    true,
                                    Thread.currentThread().getContextClassLoader()
                            ),
                            MethodType.methodType(void.class, File.class, Collection.class, File.class, PrintStream.class)
                    );
                    Runnable runnable = (Runnable) mh.invokeWithArguments(arguments);
                    runnable.run();
                } catch (Throwable t) {
                    throw new RuntimeException("Failed to decompile", t);
                }
            });
            thread.setContextClassLoader(classLoader);
            thread.start();
            thread.join();  // The classloader won't be closed until finished
        } catch (IOException | InterruptedException e) {
            CommonUtils.sneakyThrow(e);
        }
    }

    private static URLClassLoader classLoader() throws IOException {
        // Read VineFlowerEntrypoint class
        final byte[] clazz;
        try (var inputStream = RemoteVineFlowerProvider.class.getClassLoader().getResourceAsStream(VF_ENTRYPOINT.replace('.', '/').concat(".class"))) {
            Objects.requireNonNull(inputStream, VF_ENTRYPOINT);
            clazz = inputStream.readAllBytes();
        }

        // Download vineflower
        var vineflower = downloadVF();
        var maybeParent = Thread.currentThread().getContextClassLoader().getParent();
        return new URLClassLoader(new URL[]{vineflower.toUri().toURL()}, maybeParent) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (VF_ENTRYPOINT.equals(name)) {
                    return defineClass(name, clazz, 0, clazz.length);
                }
                return super.findClass(name);
            }
        };
    }

    private static Path downloadVF() throws IOException, HashMismatchException {
        final Path file = TempDirs.get().createFile();
        final RemoteFile remoteFile = new RemoteFile(
                URI.create(PublicProperties.vineFlowerUrl()).toURL(),
                PublicProperties.vineFlowerSha512(),
                null, /*1_000_433 for 1.9.2*/
                HashingUtil::sha512);
        remoteFile.download(file);
        return file;
    }
}
