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
import xland.ioutils.xdecompiler.mcmeta.HashMismatchException;
import xland.ioutils.xdecompiler.mcmeta.HashingUtil;
import xland.ioutils.xdecompiler.mcmeta.RemoteFile;
import xland.ioutils.xdecompiler.util.*;

import java.io.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class RemoteVineFlowerProvider implements DecompilerProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String CLASSNAME_VFEntrypoint = "xland.ioutils.xdecompiler.decompile.RemoteVineFlowerProvider$1-VineFlowerEntrypoint";
    private static final String M_VFEntrypoint = "decompile";
    private static final MethodType MTResolved_VFEntrypoint = MethodType.methodType(
            void.class,
            /* arguments: */
            File.class,         // jarIn
            Collection.class,   // classpath, Collection<File>
            File.class,         // dirOut
            PrintStream.class   // logStream
    );

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
            PrintStream printStream = switch (Objects./* should not happen */requireNonNullElse(vineFlowerLogDir, "")) {
                case "", "/dev/null" -> new PrintStream(OutputStream.nullOutputStream());
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
            @SuppressWarnings("UnnecessaryLocalVariable")   // make it a local var, in case classloading issue
            final MethodType entrypointDesc = MTResolved_VFEntrypoint;

            var thread = THREAD_BUILDER.unstarted(() -> {
                var lookup = MethodHandles.lookup();

                try {
                    Class<?> c = Class.forName(CLASSNAME_VFEntrypoint, true, Thread.currentThread().getContextClassLoader());

                    lookup = MethodHandles.privateLookupIn(c, lookup);
                    MethodHandle mh = lookup.findStatic(c, M_VFEntrypoint, entrypointDesc);
                    mh.invokeWithArguments(arguments);
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
        // Generate VineFlowerEntrypoint class
        final byte[] clazz = EntrypointFactory.getBytecode();

        // Download vineflower
        var vineflower = getOrDownloadVF();
        var maybeParent = Thread.currentThread().getContextClassLoader().getParent();
        return new URLClassLoader(new URL[]{vineflower.toUri().toURL()}, maybeParent) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (CLASSNAME_VFEntrypoint.equals(name)) {
                    return defineClass(name, clazz, 0, clazz.length);
                }
                return super.findClass(name);
            }
        };
    }

    private static volatile Path vineflowerJarClass;
    private static final Object LOCK_getOrDownloadVF = new Object();

    private static Path getOrDownloadVF() throws IOException, HashMismatchException {
        if (vineflowerJarClass == null) {
            synchronized (LOCK_getOrDownloadVF) {
                if (vineflowerJarClass == null) {
                    vineflowerJarClass = downloadVF();
                }
            }
        }
        return vineflowerJarClass;
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

    private static final class EntrypointFactory {
        static byte[] getBytecode() {
            return CLASS_FILE_ORIGINAL.clone();
        }

        private static final ConstantDesc[] OPTIONS = {
                "asc", 1,       // ascii-strings
                "iec", 1,       // include-classpath
                "iib", 1,       // ignore-invalid-bytecode
                "bsm", 1,       // bytecode-source-mapping
                "log", "INFO"   // log-level
        };

        private static final int INDEX_JAR_IN = 0;      // File jarIn
        private static final int INDEX_CLASSPATH = 1;   // Collection<File> classPath
        private static final int INDEX_DIR_OUT = 2;     // File dirOut
        private static final int INDEX_LOG_STREAM = 3;  // PrintStream logStream

        private static byte[] createClass() {
            return ClassFile.of().build(CD_VFEntrypoint, cb -> cb
                    .withFlags(AccessFlag.SYNTHETIC, AccessFlag.SUPER)
                    .withMethodBody(NAME_makeArray, /*static*/ BSM_makeArray.invocationType(), Modifier.STATIC | Modifier.PRIVATE | AccessFlag.VARARGS.mask(), code -> code
                            .aload(3)
                            .areturn()
                    )
                    .withMethodBody(M_VFEntrypoint, MT_VFEntrypoint, Modifier.STATIC, code -> {
                                code
                                        .invokestatic(CD_Decompiler, "builder", MethodTypeDesc.of(CD_DecompilerBuilder))
                                        .aload(INDEX_JAR_IN)
                                        .iconst_1()
                                        .anewarray(CD_File)
                                        .dup_x1()   // [File jarIn [File
                                        .swap()     // [File [File jarIn
                                        .iconst_0()
                                        .swap()     // [File [File 0 jarIn
                                        .aastore();
                                callBuilder(code, "inputs", CD_FileArray);

                                code
                                        .aload(INDEX_DIR_OUT)
                                        .new_(CD_DirectoryResultSaver)
                                        .dup()      // File DRS DRS
                                        .dup2_x1()  // DRS DRS File DRS DRS
                                        .pop2()     // DRS DRS File
                                        .invokespecial(CD_PrintStreamLogger, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, CD_File));
                                callBuilder(code, "output", CD_IResultSaver);

                                code
                                        .loadConstant(DynamicConstantDesc.of(BSM_makeArray, OPTIONS));
                                callBuilder(code, "options", CD_ObjectArray);

                                code
                                        .aload(INDEX_CLASSPATH)
                                        .iconst_0()
                                        .anewarray(CD_File)
                                        .invokeinterface(ConstantDescs.CD_Collection, "toArray", MethodTypeDesc.of(CD_ObjectArray, CD_ObjectArray))
                                        .checkcast(CD_FileArray);
                                callBuilder(code, "libraries", CD_FileArray);

                                code
                                        .aload(INDEX_LOG_STREAM)
                                        .new_(CD_PrintStreamLogger)
                                        .dup()      // PrintStream PSL PSL
                                        .dup2_x1()  // PSL PSL PrintStream PSL PSL
                                        .pop2()     // PSL PSL PrintStream
                                        .invokespecial(CD_PrintStreamLogger, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, CD_PrintStream));
                                callBuilder(code, "logger", CD_IFernflowerLogger);

                                code
                                        .invokevirtual(CD_DecompilerBuilder, "build", MethodTypeDesc.of(CD_Decompiler))
                                        .invokevirtual(CD_Decompiler, "decompile", ConstantDescs.MTD_void)
                                        .return_();
                            }
                    )
            );
        }

        private static final String NAME_makeArray = "makeArray";

        private static final ClassDesc CD_ObjectArray = ConstantDescs.CD_Object.arrayType();
        @Deprecated
        private static final MethodTypeDesc MT_makeArray = MethodTypeDesc.of(
                CD_ObjectArray,
                ConstantDescs.CD_MethodHandles_Lookup, ConstantDescs.CD_String, ConstantDescs.CD_Class, CD_ObjectArray
        );

        private static final ClassDesc CD_VFEntrypoint = ClassDesc.of(CLASSNAME_VFEntrypoint);
        private static final MethodTypeDesc MT_VFEntrypoint = MTResolved_VFEntrypoint.describeConstable().orElseThrow(InternalError::new);

        private static final DirectMethodHandleDesc BSM_makeArray = ConstantDescs.ofConstantBootstrap(CD_VFEntrypoint, NAME_makeArray, CD_ObjectArray, CD_ObjectArray);

        private static final ClassDesc CD_Decompiler = ClassDesc.ofInternalName("org/jetbrains/java/decompiler/api/Decompiler");
        private static final ClassDesc CD_DecompilerBuilder = ClassDesc.ofInternalName("org/jetbrains/java/decompiler/api/Decompiler$Builder");
        private static final ClassDesc CD_DirectoryResultSaver = ClassDesc.ofInternalName("org/jetbrains/java/decompiler/main/decompiler/DirectoryResultSaver");
        private static final ClassDesc CD_IResultSaver = ClassDesc.ofInternalName("org/jetbrains/java/decompiler/main/extern/IResultSaver");
        private static final ClassDesc CD_PrintStreamLogger = ClassDesc.ofInternalName("org/jetbrains/java/decompiler/main/decompiler/PrintStreamLogger");
        private static final ClassDesc CD_IFernflowerLogger = ClassDesc.ofInternalName("org/jetbrains/java/decompiler/main/extern/IFernflowerLogger");
        private static final ClassDesc CD_File = File.class.describeConstable().orElseThrow(IncompatibleClassChangeError::new);
        private static final ClassDesc CD_FileArray = CD_File.arrayType();
        private static final ClassDesc CD_PrintStream = PrintStream.class.describeConstable().orElseThrow(IncompatibleClassChangeError::new);

        private static void callBuilder(CodeBuilder code, String methodName, ClassDesc... paramTypes) {
            code.invokevirtual(CD_DecompilerBuilder, methodName, MethodTypeDesc.of(CD_DecompilerBuilder, paramTypes));
        }

        private static final byte[] CLASS_FILE_ORIGINAL = createClass();

        static {
            xland.ioutils.xdecompiler.util.DebugUtils.log(6, l -> {
                try {
                    final byte[] byteCode = getBytecode();
                    var dumpedPath = TempDirs.get().createFile(".class");
                    try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(dumpedPath))) {
                        outputStream.write(byteCode);
                    }
                    l.info("Dumped {} into {}", CLASSNAME_VFEntrypoint, dumpedPath);
                } catch (Exception e) {
                    l.warn("Failed to dump class {}", CLASSNAME_VFEntrypoint);
                }
            });
        }
    }
}
