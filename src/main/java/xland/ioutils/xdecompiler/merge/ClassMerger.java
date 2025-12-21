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

import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import xland.ioutils.xdecompiler.util.CommonUtils;

import java.util.*;

public class ClassMerger {
    private static final String SIDE_DESCRIPTOR = "Lnet/fabricmc/api/EnvType;";
    private static final String ITF_DESCRIPTOR = "Lnet/fabricmc/api/EnvironmentInterface;";
    private static final String ITF_LIST_DESCRIPTOR = "Lnet/fabricmc/api/EnvironmentInterfaces;";
    private static final String SIDED_DESCRIPTOR = "Lnet/fabricmc/api/Environment;";

    private abstract static class Merger<T> {
        private final Map<String, T> entriesClient, entriesServer;
        private final List<String> entryNames;

        public Merger(List<? extends T> entriesClient, List<? extends T> entriesServer) {
            this.entriesClient = new LinkedHashMap<>();
            this.entriesServer = new LinkedHashMap<>();

            List<String> listClient = toMap(entriesClient, this.entriesClient);
            List<String> listServer = toMap(entriesServer, this.entriesServer);

            this.entryNames = CommonUtils.mergePreserveOrder(listClient, listServer);
        }

        public abstract String getName(T entry);
        public abstract void applySide(T entry, String side);

        private List<String> toMap(List<? extends T> entries, Map<String, ? super T> map) {
            List<String> list = new ArrayList<>(entries.size());
            for (T entry : entries) {
                String name = getName(entry);
                map.put(name, entry);
                list.add(name);
            }
            return list;
        }

        public void merge(List<? super T> list) {
            for (String s : entryNames) {
                T entryClient = entriesClient.get(s);
                T entryServer = entriesServer.get(s);

                if (entryClient != null && entryServer != null) {
                    // arbitrarily use client entry if both are present
                    list.add(entryClient);
                } else if (entryClient != null) {
                    applySide(entryClient, "CLIENT");
                    list.add(entryClient);
                } else {
                    applySide(entryServer, "SERVER");
                    list.add(entryServer);
                }
            }
        }
    }

    private static void visitSideAnnotation(AnnotationVisitor av, String side) {
        av.visitEnum("value", SIDE_DESCRIPTOR, side.toUpperCase(Locale.ROOT));
        av.visitEnd();
    }

    private static void visitItfAnnotation(AnnotationVisitor av, String side, List<String> itfDescriptors) {
        for (String itf : itfDescriptors) {
            AnnotationVisitor avItf = av.visitAnnotation(null, ITF_DESCRIPTOR);
            avItf.visitEnum("value", SIDE_DESCRIPTOR, side.toUpperCase(Locale.ROOT));
            avItf.visit("itf", Type.getType("L" + itf + ";"));
            avItf.visitEnd();
        }
    }

    public static class SidedClassVisitor extends ClassVisitor {
        private final String side;

        public SidedClassVisitor(int api, ClassVisitor cv, String side) {
            super(api, cv);
            this.side = side;
        }

        @Override
        public void visitEnd() {
            AnnotationVisitor av = cv.visitAnnotation(SIDED_DESCRIPTOR, true);
            visitSideAnnotation(av, side);
            super.visitEnd();
        }
    }

    public ClassMerger() {
    }

    public byte[] merge(byte[] classClient, byte[] classServer) {
        Objects.requireNonNull(classClient, "classClient");
        Objects.requireNonNull(classServer, "classServer");

        if (Arrays.equals(classClient, classServer)) {
            // Identical classes, no need to merge
            return classClient;
        }

        ClassReader readerC = new ClassReader(classClient);
        ClassReader readerS = new ClassReader(classServer);
        ClassWriter writer = new ClassWriter(0);

        ClassNode nodeC = new ClassNode(Opcodes.ASM9);
        readerC.accept(nodeC, 0);

        ClassNode nodeS = new ClassNode(Opcodes.ASM9);
        readerS.accept(nodeS, 0);

        ClassNode nodeOut = new ClassNode(Opcodes.ASM9);
        nodeOut.version = nodeC.version;
        nodeOut.access = nodeC.access;
        nodeOut.name = nodeC.name;
        nodeOut.signature = nodeC.signature;
        nodeOut.superName = nodeC.superName;
        nodeOut.sourceFile = nodeC.sourceFile;
        nodeOut.sourceDebug = nodeC.sourceDebug;
        nodeOut.outerClass = nodeC.outerClass;
        nodeOut.outerMethod = nodeC.outerMethod;
        nodeOut.outerMethodDesc = nodeC.outerMethodDesc;
        nodeOut.module = nodeC.module;
        nodeOut.nestHostClass = nodeC.nestHostClass;
        nodeOut.nestMembers = nodeC.nestMembers;
        nodeOut.attrs = nodeC.attrs;

        if (nodeC.invisibleAnnotations != null) {
            nodeOut.invisibleAnnotations = new ArrayList<>();
            nodeOut.invisibleAnnotations.addAll(nodeC.invisibleAnnotations);
        }
        if (nodeC.invisibleTypeAnnotations != null) {
            nodeOut.invisibleTypeAnnotations = new ArrayList<>();
            nodeOut.invisibleTypeAnnotations.addAll(nodeC.invisibleTypeAnnotations);
        }
        if (nodeC.visibleAnnotations != null) {
            nodeOut.visibleAnnotations = new ArrayList<>();
            nodeOut.visibleAnnotations.addAll(nodeC.visibleAnnotations);
        }
        if (nodeC.visibleTypeAnnotations != null) {
            nodeOut.visibleTypeAnnotations = new ArrayList<>();
            nodeOut.visibleTypeAnnotations.addAll(nodeC.visibleTypeAnnotations);
        }

        List<String> interfaces = CommonUtils.mergePreserveOrder(nodeC.interfaces, nodeS.interfaces);
        nodeOut.interfaces = new ArrayList<>();

        List<String> clientInterfaces = new ArrayList<>();
        List<String> serverInterfaces = new ArrayList<>();

        for (String s : interfaces) {
            boolean nc = nodeC.interfaces.contains(s);
            boolean ns = nodeS.interfaces.contains(s);
            nodeOut.interfaces.add(s);
            if (nc && !ns) {
                clientInterfaces.add(s);
            } else if (ns && !nc) {
                serverInterfaces.add(s);
            }
        }

        if (!clientInterfaces.isEmpty() || !serverInterfaces.isEmpty()) {
            AnnotationVisitor envInterfaces = nodeOut.visitAnnotation(ITF_LIST_DESCRIPTOR, false);
            AnnotationVisitor eiArray = envInterfaces.visitArray("value");

            if (!clientInterfaces.isEmpty()) {
                visitItfAnnotation(eiArray, "CLIENT", clientInterfaces);
            }
            if (!serverInterfaces.isEmpty()) {
                visitItfAnnotation(eiArray, "SERVER", serverInterfaces);
            }
            eiArray.visitEnd();
            envInterfaces.visitEnd();
        }

        new Merger<>(nodeC.innerClasses, nodeS.innerClasses) {
            @Override
            public String getName(InnerClassNode entry) {
                return entry.name;
            }

            @Override
            public void applySide(InnerClassNode entry, String side) {
            }
        }.merge(nodeOut.innerClasses);

        new Merger<>(nodeC.fields, nodeS.fields) {
            @Override
            public String getName(FieldNode entry) {
                return entry.name + ";;" + entry.desc;
            }

            @Override
            public void applySide(FieldNode entry, String side) {
                AnnotationVisitor av = entry.visitAnnotation(SIDED_DESCRIPTOR, false);
                visitSideAnnotation(av, side);
            }
        }.merge(nodeOut.fields);

        new Merger<>(nodeC.methods, nodeS.methods) {
            @Override
            public String getName(MethodNode entry) {
                return entry.name + entry.desc;
            }

            @Override
            public void applySide(MethodNode entry, String side) {
                AnnotationVisitor av = entry.visitAnnotation(SIDED_DESCRIPTOR, false);
                visitSideAnnotation(av, side);
            }
        }.merge(nodeOut.methods);

        nodeOut.accept(writer);
        return writer.toByteArray();
    }
}
