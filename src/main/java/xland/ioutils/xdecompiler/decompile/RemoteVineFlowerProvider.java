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

import org.objectweb.asm.*;
import org.objectweb.asm.commons.InstructionAdapter;
import org.slf4j.Logger;
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

public class RemoteVineFlowerProvider implements DecompilerProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String VF_ENTRYPOINT_CLASS = "xland.ioutils.xdecompiler.decompile.vineflower.VineFlowerEntrypoint";
    private static final String VF_ENTRYPOINT_NAME = "decompile";
//    private static final MethodType VF_ENTRYPOINT_TYPE = Type.getMethodDescriptor(
//            Type.VOID_TYPE,
//            /* arguments: */
//            Type.getType(File.class),               // jarIn
//            Type.getType(Collection.class),         // classpath, Collection<File>
//            Type.getType(File.class),               // dirOut
//            Type.getType(PrintStream.class)         // logStream
//    );
    private static final MethodType VF_ENTRYPOINT_TYPE = MethodType.methodType(
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
            @SuppressWarnings("UnnecessaryLocalVariable")   // make it a local var, in case classloading issue
            final MethodType entrypointDesc = VF_ENTRYPOINT_TYPE;
            var thread = THREAD_BUILDER.unstarted(() -> {
                var lookup = MethodHandles.lookup();
                try {
                    MethodHandle mh = lookup.findStatic(
                            Class.forName(
                                    VF_ENTRYPOINT_CLASS,
                                    true,
                                    Thread.currentThread().getContextClassLoader()
                            ),
                            VF_ENTRYPOINT_NAME,
                            entrypointDesc
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
        // Generate VineFlowerEntrypoint class
        final byte[] clazz = EntrypointFactory.getBytecode();

        // Download vineflower
        var vineflower = downloadVF();
        var maybeParent = Thread.currentThread().getContextClassLoader().getParent();
        return new URLClassLoader(new URL[]{vineflower.toUri().toURL()}, maybeParent) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (VF_ENTRYPOINT_CLASS.equals(name)) {
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

    private static final class EntrypointFactory {
        static byte[] getBytecode() {
            return CLASS_FILE_ORIGINAL.clone();
        }

        private static final Object[] OPTIONS = {
                "asc", 1,       // ascii-strings
                "iec", 1,       // include-classpath
                "iib", 1,       // ignore-invalid-bytecode
                "bsm", 1,       // bytecode-source-mapping
                "log", "INFO"   // log-level
        };

        private static byte[] createClass() {
            ClassWriter cw = new ClassWriter(3);
            final String className = VF_ENTRYPOINT_CLASS.replace('.', '/');
            cw.visit(Opcodes.V17, Opcodes.ACC_SYNTHETIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
            InstructionAdapter mv;

            mv = new InstructionAdapter(cw.visitMethod(
                    Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_VARARGS,
                    HANDLE_BOOSTRAP_NAME,
                    HANDLE_BOOTSTRAP_DESC,
                    null, null
            ));
            {
                mv.visitCode();

                mv.load(3, InstructionAdapter.OBJECT_TYPE);
                mv.areturn(InstructionAdapter.OBJECT_TYPE);

                mv.visitMaxs(-1, -1);
                mv.visitEnd();
            }

            mv = new InstructionAdapter(cw.visitMethod(
                    Opcodes.ACC_STATIC, VF_ENTRYPOINT_NAME, VF_ENTRYPOINT_TYPE.descriptorString(),
                    null, null
            ));
            createEntrypointMethod(mv, className);

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static final int INDEX_JAR_IN = 0;
        private static final int INDEX_CLASSPATH = 1;
        private static final int INDEX_DIR_OUT = 2;
        private static final int INDEX_LOG_STREAM = 3;

        private static void createEntrypointMethod(InstructionAdapter mv, final String className) {
            mv.visitCode();
            // var0: File jarIn
            // var1: Collection<File> classpath
            // var2: File dirOut
            // var3: PrintStream logStream
            final Type t_fileArray = Type.getType(File[].class);
            final Type t_directoryResultSaver = Type.getObjectType("org/jetbrains/java/decompiler/main/decompiler/DirectoryResultSaver");
            final Type t_printStreamLogger = Type.getObjectType("org/jetbrains/java/decompiler/main/decompiler/PrintStreamLogger");

            mv.invokestatic(
                    T_DECOMPILER.getInternalName(), "builder",
                    Type.getMethodDescriptor(T_DECOMPILER_BUILDER),
                    false
            );

            mv.iconst(1);
            mv.newarray(Type.getType(File.class));
            mv.iconst(0);
            mv.load(INDEX_JAR_IN, InstructionAdapter.OBJECT_TYPE);
            mv.astore(InstructionAdapter.OBJECT_TYPE);

            callBuilder(mv, "inputs", t_fileArray);

            mv.anew(t_directoryResultSaver);
            mv.dup();
            mv.load(INDEX_DIR_OUT, InstructionAdapter.OBJECT_TYPE);
            mv.invokespecial(t_directoryResultSaver.getInternalName(), "<init>", "(Ljava/io/File;)V", false);
            callBuilder(mv, "output", Type.getType("Lorg/jetbrains/java/decompiler/main/extern/IResultSaver;"));

            mv.cconst(new ConstantDynamic(
                    "$", "[Ljava/lang/Object;",
                    new Handle(Opcodes.H_INVOKESTATIC, className, HANDLE_BOOSTRAP_NAME, HANDLE_BOOTSTRAP_DESC, false),
                    OPTIONS
            ));
            callBuilder(mv, "options", Type.getType("[Ljava/lang/Object;"));

            mv.load(INDEX_CLASSPATH, InstructionAdapter.OBJECT_TYPE);
            mv.iconst(0);
            mv.newarray(t_fileArray);
            mv.invokeinterface("java/lang/Collection", "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;");
            mv.checkcast(t_fileArray);
            callBuilder(mv, "libraries", t_fileArray);

            mv.anew(t_printStreamLogger);
            mv.dup();
            mv.load(INDEX_LOG_STREAM, InstructionAdapter.OBJECT_TYPE);
            mv.invokespecial(t_printStreamLogger.getInternalName(), "<init>", "(Ljava/io/PrintStream;)V", false);
            callBuilder(mv, "logger", Type.getType("Lorg/jetbrains/java/decompiler/main/extern/IFernflowerLogger;"));

            mv.invokevirtual(T_DECOMPILER_BUILDER.getInternalName(), "build", Type.getMethodDescriptor(T_DECOMPILER), false);

            mv.invokevirtual(T_DECOMPILER.getInternalName(), "decompile", "()V", false);

            mv.visitMaxs(-1, -1);
            mv.visitEnd();
        }

        private static final Type T_DECOMPILER = Type.getType("Lorg/jetbrains/java/decompiler/api/Decompiler;");
        private static final Type T_DECOMPILER_BUILDER = Type.getType("Lorg/jetbrains/java/decompiler/api/Decompiler$Builder;");
        private static final String HANDLE_BOOSTRAP_NAME = "makeArray";
        private static final String HANDLE_BOOTSTRAP_DESC = MethodType.methodType(
                Object[].class,
                MethodHandles.Lookup.class, String.class, MethodType.class, Object[].class
        ).descriptorString();

        private static void callBuilder(InstructionAdapter mv, String methodName, Type... paramTypes) {
            mv.invokevirtual(T_DECOMPILER_BUILDER.getInternalName(), methodName, Type.getMethodDescriptor(T_DECOMPILER_BUILDER, paramTypes), false);
        }

        private static final byte[] CLASS_FILE_ORIGINAL = createClass();
    }
}
