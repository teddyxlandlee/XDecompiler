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

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import xland.ioutils.xdecompiler.mcmeta.HashMismatchException;
import xland.ioutils.xdecompiler.mcmeta.HashingUtil;
import xland.ioutils.xdecompiler.mcmeta.RemoteFile;
import xland.ioutils.xdecompiler.util.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.zip.GZIPOutputStream;

public class RemoteVineFlowerProvider implements DecompilerProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public RemoteVineFlowerProvider() {}

    @Override
    public String id() {
        return "vineflower";
    }

    @Override
    public void decompile(Path jarIn, Collection<Path> classpath, Path dirOut) {
        List<String> args = new ArrayList<>(16);
        Collections.addAll(args,
                "--folder",
                "-asc=1",
                "-iec=1",
                "-jvn=1", /* Fix the try-catch variable */
                "-iib=1",
                "-bsm=1",
                //"-dcl=1", /* we dump into folder */
                "-log=TRACE"
        );
        classpath.forEach(cp -> args.add("-e=" + cp));
        // source, not yet considered realmsClient
        args.add(String.valueOf(jarIn));
        // dest
        args.add(String.valueOf(dirOut));
        
        Path logFile = null;
        OutputStream alt = null;
        final String prop = PublicProperties.vineFlowerLogDir();
        switch (prop) {
            case "", "/dev/null" -> {}
            case "/dev/stdout" -> alt = System.out;
            case "/dev/stderr" -> alt = System.err;
            default -> {
                logFile = Path.of(prop).resolve(dirOut.getFileName() + "-"
                        + UUID.randomUUID().toString().substring(24) + ".txt.gz");
                LOGGER.info("Decompile log for " + dirOut + " will be dumped into " + logFile);
            }
        }

        VineFlowerInstance vf = VineFlowerInstance.getOrCreate();
        vf.execute(logFile, alt, args.toArray(new String[0]));
    }
}

record VineFlowerInstance(ClassLoader cl, String mainClass) {
    private static final AtomicInteger RUNNER_ID = new AtomicInteger(1);
    private static final String HOLDER_CLASS = "xland/ioutils/xdecompiler/decompile/impl/vineflower/LoggerHolder";

    void execute(@Nullable Path loggingFile, @Nullable OutputStream alt, String... args) {
        final ClassLoader cl = cl();
        Thread thread = new Thread(() -> {
            try {
                var lookup = MethodHandles.lookup();
                Class<?> c = Class.forName(HOLDER_CLASS.replace('/', '.'), true, cl);
                var handle = lookup.findStaticGetter(c, "threadLocal", ThreadLocal.class);
                @SuppressWarnings("unchecked")
                final ThreadLocal<PrintStream> threadLocal = (ThreadLocal<PrintStream>) handle.invoke();
                final PrintStream ps;
                if (loggingFile == null) {
                    if (alt == null) {
                        ps = (new PrintStream(OutputStream.nullOutputStream()));
                    } else {
                        ps = new PrintStream(alt) {
                            private static final ThreadLocal<String> thrNamePrefix =
                                    ThreadLocal.withInitial(() -> '[' + Thread.currentThread().getName() + "] ");

                            @Override
                            public synchronized void println(@Nullable String x) {
                                super.print(thrNamePrefix.get());
                                super.println(x);
                            }
                        };
                    }
                } else {
                    Files.createDirectories(loggingFile.getParent());
                    ps = (new PrintStream(new GZIPOutputStream(Files.newOutputStream(loggingFile))));
                }
                threadLocal.set(ps);

                final long t0 = System.nanoTime();
                try (ps) {
                    c = Class.forName(mainClass(), true, cl);
                    handle = lookup.findStatic(c, "main", MethodType.methodType(void.class, String[].class)).asFixedArity();
                    handle.invoke((Object) args);
                    LogUtils.getLogger().info("...Decompile finished in {}.", TimeUtils.timeFormat(System.nanoTime() - t0));
                } finally {
                    threadLocal.remove();
                }
            } catch (Throwable t) {
                CommonUtils.sneakyThrow(t);
            }
        }, "VineFlower-Runner-" + RUNNER_ID.getAndIncrement());
        thread.setContextClassLoader(cl);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            CommonUtils.sneakyThrow(e);
        }
    }

    static VineFlowerInstance fromJar(Path jar) throws IOException {
        final URL url;
        try {
            url = jar.toAbsolutePath().toUri().toURL();
        } catch (MalformedURLException e) {
            jar = Files.copy(jar, TempDirs.get().createFile());
            return fromJar(jar);
        }

        final String mainClass;
        try (var jis = new JarFile(jar.toFile())) {
            mainClass = jis.getManifest().getMainAttributes().getValue("Main-Class");
        }

        ClassLoader maybeParent = Thread.currentThread().getContextClassLoader().getParent();
        URLClassLoader urlClassLoader = new VineFlowerClassLoader(new URL[]{url}, maybeParent);
        return new VineFlowerInstance(urlClassLoader, mainClass);
    }

    private static class VineFlowerClassLoader extends URLClassLoader {

        VineFlowerClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            return switch (name) {
                case "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler" -> {
                    try (InputStream in = findResource(name.replace('.', '/') + ".class").openStream()) {
                        byte[] b = in.readAllBytes();
                        b = modifyMainClass(b);
                        yield defineClass(name, b, 0, b.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                case "xland.ioutils.xdecompiler.decompile.impl.vineflower.LoggerHolder" -> {
                    byte[] b = createLoggerHolderClass();
                    yield defineClass(name, b, 0, b.length);
                }
                default -> super.findClass(name);
            };
        }
    }

    private static byte[] modifyMainClass(byte[] b) {
        class MainClassModify extends ClassVisitor {
            MainClassModify(ClassVisitor classVisitor) {
                super(Opcodes.ASM9, classVisitor);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!("main".equals(name) && "([Ljava/lang/String;)V".equals(descriptor) && Modifier.isPublic(access) && Modifier.isStatic(access)))
                    return mv;
                if (mv == null) return null;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (!(opcode == Opcodes.GETSTATIC && "java/lang/System".equals(owner) && "out".equals(name) && "Ljava/io/PrintStream;".equals(descriptor))) {
                            super.visitFieldInsn(opcode, owner, name, descriptor);
                            return;
                        }
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOLDER_CLASS, "get", "()Ljava/io/PrintStream;", false);
                    }
                };
            }
        }

        ClassReader cr = new ClassReader(b);
        ClassWriter cw = new ClassWriter(cr, 3);
        ClassVisitor cv = cw;
        cv = new MainClassModify(cv);
        cr.accept(cv, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    private static byte[] createLoggerHolderClass() {
        ClassWriter cw = new ClassWriter(3);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                HOLDER_CLASS,
                null, "java/lang/Object", null);
        var fv = cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "threadLocal", "Ljava/lang/ThreadLocal;", null, null);
        fv.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "get", "()Ljava/io/PrintStream;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, HOLDER_CLASS,
                "threadLocal", "Ljava/lang/ThreadLocal;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/io/PrintStream");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(-1, -1);
        mv.visitEnd();
        
        mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, HOLDER_CLASS, "threadLocal", "Ljava/lang/ThreadLocal;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(-1, -1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static volatile VineFlowerInstance instance;

    static VineFlowerInstance getOrCreate() {
        if (instance == null) {
            synchronized (VineFlowerInstance.class) {
                if (instance == null) {
                    try {
                        instance = fromJar(downloadVF());
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to create VineFlowerInstance", e);
                    }
                }
            }
        }
        return instance;
    }

    private static Path downloadVF() throws IOException, HashMismatchException {
        final Path file = TempDirs.get().createFile();
        final RemoteFile remoteFile = new RemoteFile(
                new URL(PublicProperties.vineFlowerUrl()),
                PublicProperties.vineFlowerSha512(),
                null, /*1_000_433 for 1.9.2*/
                HashingUtil::sha512);
        remoteFile.download(file);
        return file;
    }
}
