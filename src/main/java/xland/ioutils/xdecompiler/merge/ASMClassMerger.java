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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import xland.ioutils.xdecompiler.util.CommonUtils;
import xland.ioutils.xdecompiler.util.Merger;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

//@Deprecated
public record ASMClassMerger(List<UnaryOperator<ClassVisitor>> postVisitors) implements ClassMerger {
    private static final String SIDE_DESCRIPTOR = "Lnet/fabricmc/api/EnvType;";
    private static final String ITF_DESCRIPTOR = "Lnet/fabricmc/api/EnvironmentInterface;";
    private static final String ITF_LIST_DESCRIPTOR = "Lnet/fabricmc/api/EnvironmentInterfaces;";
    private static final String SIDED_DESCRIPTOR = "Lnet/fabricmc/api/Environment;";

    private static void visitSideAnnotation(AnnotationVisitor av, String side) {
        av.visitEnum("value", SIDE_DESCRIPTOR, side);
        av.visitEnd();
    }

    private static void visitItfAnnotation(AnnotationVisitor av, String side, List<String> itfDescriptors) {
        for (String itf : itfDescriptors) {
            AnnotationVisitor avItf = av.visitAnnotation(null, ITF_DESCRIPTOR);
            avItf.visitEnum("value", SIDE_DESCRIPTOR, side);
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

    public ASMClassMerger {
        postVisitors = List.copyOf(postVisitors);
    }

    private byte[] postVisit(ClassNode node) {
        return postVisit(node::accept, this.postVisitors());
    }

    private byte[] postVisit(byte[] classFile) {
        if (this.postVisitors().isEmpty()) return classFile;
        return postVisit(classFile, this.postVisitors());
    }

    private byte[] postVisitSided(byte[] classFile, String side) {
        Function<ClassVisitor, ClassVisitor> visitor = cv -> new SidedClassVisitor(Opcodes.ASM9, cv, side);

        for (UnaryOperator<ClassVisitor> postVisitor : this.postVisitors()) {
            visitor = visitor.andThen(postVisitor);
        }
        return postVisit(classFile, Collections.singleton(visitor::apply));
    }

    private static byte[] postVisit(byte[] classFile, Iterable<UnaryOperator<ClassVisitor>> visitors) {
        ClassReader cr = new ClassReader(classFile);
        return postVisit(cv -> cr.accept(cv, 0), visitors);
    }

    private static byte[] postVisit(Consumer<ClassVisitor> acceptor, Iterable<UnaryOperator<ClassVisitor>> visitors) {
        ClassWriter cw = new ClassWriter(0);
        ClassVisitor cv = cw;
        for (UnaryOperator<ClassVisitor> function : visitors) {
            cv = function.apply(cv);
        }
        acceptor.accept(cv);
        return cw.toByteArray();
    }

    public byte @NotNull [] merge(byte @Nullable [] clientClass, byte @Nullable[] serverClass) {

        if (Arrays.equals(clientClass, serverClass)) {
            if (clientClass == null) {
                throw new IllegalArgumentException("clientClass and serverClass cannot both be null");
            }
            // Identical classes, no need to merge
            return this.postVisit(clientClass);
        }

        if (clientClass == null) return this.postVisitSided(serverClass, "SERVER");
        if (serverClass == null) return this.postVisitSided(clientClass, "CLIENT");

        ClassReader readerC = new ClassReader(clientClass);
        ClassReader readerS = new ClassReader(serverClass);

        ClassNode nodeC = new ClassNode(Opcodes.ASM9);
        readerC.accept(nodeC, 0);

        ClassNode nodeS = new ClassNode(Opcodes.ASM9);
        readerS.accept(nodeS, 0);

        ClassNode nodeOut = ClassNodeReflections.copy(nodeC);
        ClassNodeReflections.initLists(nodeC);
        ClassNodeReflections.initLists(nodeS);

        // Interfaces - sided-mark later
        List<String> clientInterfaces = new ArrayList<>();
        List<String> serverInterfaces = new ArrayList<>();
        nodeOut.interfaces = new ArrayList<>();
        Merger.extraWhenSided(
                clientInterfaces, serverInterfaces, CommonUtils.dropSecond(),
                nodeOut.interfaces::addLast,
                (itf, sidedList) -> sidedList.add(itf)
        ).mergePreserveOrder(nodeC.interfaces, nodeS.interfaces, Function.identity());

        // InnerClasses
        nodeOut.innerClasses = new ArrayList<>();
        Merger.samePath(CommonUtils.dropSecond(), nodeOut.innerClasses::addLast)
                .mergePreserveOrder(nodeC.innerClasses, nodeS.innerClasses, i -> i.name);

        // Fields
        nodeOut.fields = new ArrayList<>();
        Merger.extraWhenSided("CLIENT", "SERVER",
                CommonUtils.dropSecond(), nodeOut.fields::addLast,
                ((entry, side) -> {
                    AnnotationVisitor av = entry.visitAnnotation(SIDED_DESCRIPTOR, false);
                    visitSideAnnotation(av, side);
                }))
                .mergePreserveOrder(nodeC.fields, nodeS.fields, f -> f.name + ':' + f.desc);

        // Methods
        nodeOut.methods = new ArrayList<>();
        Merger.extraWhenSided("CLIENT", "SERVER",
                CommonUtils.dropSecond(), nodeOut.methods::addLast,
                ((entry, side) -> {
                    AnnotationVisitor av = entry.visitAnnotation(SIDED_DESCRIPTOR, false);
                    visitSideAnnotation(av, side);
                }))
                .mergePreserveOrder(nodeC.methods, nodeS.methods, m -> m.name + m.desc);

        // PermittedSubclasses
        nodeOut.permittedSubclasses = new ArrayList<>();
        Merger.samePath(CommonUtils.dropSecond(), nodeOut.permittedSubclasses::addLast)
                .mergePreserveOrder(nodeC.permittedSubclasses, nodeS.permittedSubclasses, Function.identity());

        // NestMembers
        nodeOut.nestMembers = new ArrayList<>();
        Merger.samePath(CommonUtils.dropSecond(), nodeOut.nestMembers::addLast)
                .mergePreserveOrder(nodeC.nestMembers, nodeS.nestMembers, Function.identity());

        // Annotations
        nodeOut.invisibleAnnotations = new ArrayList<>();
        nodeOut.visibleAnnotations = new ArrayList<>();
        nodeOut.invisibleTypeAnnotations = new ArrayList<>();
        nodeOut.visibleAnnotations = new ArrayList<>();

        AnnotationKey.merge(nodeOut, nodeC, nodeS, c -> c.invisibleAnnotations);
        AnnotationKey.merge(nodeOut, nodeC, nodeS, c -> c.visibleAnnotations);
        AnnotationKey.merge(nodeOut, nodeC, nodeS, c -> c.invisibleTypeAnnotations);
        AnnotationKey.merge(nodeOut, nodeC, nodeS, c -> c.visibleTypeAnnotations);

        // Interfaces - sided marks
        if (!clientInterfaces.isEmpty() || !serverInterfaces.isEmpty()) {
            final AnnotationNode itfList = new AnnotationNode(Opcodes.ASM9, ITF_LIST_DESCRIPTOR);
            AnnotationNode prevItf = null;
            AnnotationNode prevItfList = null;

            var itr = nodeOut.invisibleAnnotations.listIterator();
            while (itr.hasNext()) {
                AnnotationNode node = itr.next();
                switch (node.desc) {
                    case SIDED_DESCRIPTOR -> itr.remove();  // cannot keep this
                    case ITF_DESCRIPTOR -> {                // merge this later
                        prevItf = node;
                        itr.remove();
                    }
                    case ITF_LIST_DESCRIPTOR -> {           // merge this later
                        prevItfList = node;
                        itr.remove();
                    }
                }
            }
            AnnotationVisitor nested = itfList.visitArray("value");
            if (prevItf != null) nested.visit(null, prevItf);
            if (prevItfList != null) {
                prevItfList.accept(new AnnotationVisitor(Opcodes.ASM9) {
                    // @EnvironmentInterfaces(value = { @EnvironmentInterface( value: enum#EnvType, itf: class ) })
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if ("value".equals(name)) {
                            return nested;  // append list elements to `nested`
                        }
                        return null;
                    }
                });
            }
            visitItfAnnotation(nested, "CLIENT", clientInterfaces);
            visitItfAnnotation(nested, "SERVER", serverInterfaces);
            nodeOut.invisibleAnnotations.addLast(itfList);
        }

        return this.postVisit(nodeOut);
    }

    private record AnnotationKey(int typeRef, String typePathString, String annotationType) {
        // TODO: merge member annotations
        static Object fromAnnotation(AnnotationNode node) {
            if (node instanceof TypeAnnotationNode typeAnnotation) {
                return new AnnotationKey(typeAnnotation.typeRef, typeAnnotation.typePath.toString(), typeAnnotation.desc);
            } else {
                return node.desc;
            }
        }

        static <A extends AnnotationNode> void merge(ClassNode target, ClassNode first, ClassNode second, Function<ClassNode, List<A>> func) {
            Merger.samePath(CommonUtils.dropSecond(), func.apply(target)::addLast)
                    .mergePreserveOrder(func.apply(first), func.apply(second), AnnotationKey::fromAnnotation);
        }
    }
}
