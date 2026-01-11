package xland.ioutils.xdecompiler.merge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xland.ioutils.xdecompiler.util.CommonUtils;
import xland.ioutils.xdecompiler.util.Merger;

import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

public record ClassFileMerger(List<Function<ClassDesc, ClassTransform>> postTransformers) implements ClassMerger {
    private static final ClassDesc CD_Side = ClassDesc.ofInternalName("net/fabricmc/api/EnvType");
    private static final ClassDesc CD_Itf = ClassDesc.ofInternalName("net/fabricmc/api/EnvironmentInterface");
    private static final ClassDesc CD_ItfList = ClassDesc.ofInternalName("net/fabricmc/api/EnvironmentInterfaces");
    private static final ClassDesc CD_OnlyIn = ClassDesc.ofInternalName("net/fabricmc/api/Environment");

    @Override
    public byte @NotNull [] merge(byte @Nullable [] clientClass, byte @Nullable [] serverClass) {
        if (Arrays.equals(clientClass, serverClass)) {
            if (clientClass == null) throw new IllegalArgumentException("clientClass and serverClass cannot both be null");
            return clientClass;
        }

        final ClassFile context = ClassFile.of();
        final ClassModel clientModel = clientClass != null ? context.parse(clientClass) : null;
        final ClassModel serverModel = serverClass != null ? context.parse(serverClass) : null;
        ClassModel baseModel = clientModel != null ? clientModel : serverModel;
        Objects.requireNonNull(baseModel);  // guaranteed

        ClassTransform transform;
        if (clientModel != null && serverModel != null) {
            transform = new Transform(clientModel, serverModel);
        } else if (clientModel != null) {
            transform = MemberTransform.asClassTransform("CLIENT");
        } else {    // serverModel != null
            transform = MemberTransform.asClassTransform("SERVER");
        }

        for (var postTransformer : postTransformers) {
            transform = transform.andThen(postTransformer.apply(baseModel.thisClass().asSymbol()));
        }

        return context.transformClass(baseModel, transform);
    }

    public ClassFileMerger {
        postTransformers = List.copyOf(postTransformers);
    }

    private static final class Transform implements ClassTransform {
        private final ClassModel clientModel;
        private final ClassModel serverModel;
        private final Map<ClassDesc, String> sidedInterfaces = new LinkedHashMap<>();

        private RuntimeInvisibleAnnotationsAttribute invisibleAnnotationsAttribute;

        private Transform(ClassModel clientModel, ClassModel serverModel) {
            Objects.requireNonNull(clientModel, "clientModel");
            Objects.requireNonNull(serverModel, "serverModel");

            this.clientModel = clientModel;
            this.serverModel = serverModel;
        }

        @Override
        public void accept(ClassBuilder builder, ClassElement element) {
            switch (element) {
                case Interfaces interfaces -> {
                    Merger<ClassEntry> merger = Merger.extraWhenSided(
                            "CLIENT", "SERVER",
                            CommonUtils.dropSecond(),
                            builder::withInterfaces,
                            (e, s) -> sidedInterfaces.put(e.asSymbol(), s)
                    );
                    merger.mergePreserveOrder(interfaces.interfaces(), serverModel.interfaces(), ClassEntry::asSymbol);
                }
                case RuntimeInvisibleAnnotationsAttribute invisibleAnnotations -> // Delay visit
                        this.invisibleAnnotationsAttribute = invisibleAnnotations;
                case FieldModel _, MethodModel _,
                     InnerClassesAttribute _,
                     NestMembersAttribute _,
                     PermittedSubclassesAttribute _,

                     RuntimeInvisibleTypeAnnotationsAttribute _,
                     RuntimeVisibleAnnotationsAttribute _,
                     RuntimeVisibleTypeAnnotationsAttribute _-> {}  // compare later
                default -> builder.with(element);
            }
        }

        @Override
        public void atEnd(ClassBuilder builder) {
            mergeFieldsAndMethods(builder);
            mergeMiscAttributes(builder);
            appendInvisibleAnnotations(builder);
        }

        private void mergeFieldsAndMethods(ClassBuilder builder) {
            Merger<FieldModel> fieldMerger = ClassFileMerger.MemberTransform.fieldMerger(builder);
            Merger<MethodModel> methodMerger = ClassFileMerger.MemberTransform.methodMerger(builder);
            fieldMerger.mergePreserveOrder(clientModel.fields(), serverModel.fields(), f -> memberIdentifier(f.fieldName(), f.fieldType()));
            methodMerger.mergePreserveOrder(clientModel.methods(), serverModel.methods(), m -> memberIdentifier(m.methodName(), m.methodType()));
        }

        private static Comparable<?> memberIdentifier(Utf8Entry name, Utf8Entry desc) {
            return name.stringValue().concat(desc.stringValue());
        }

        private void mergeMiscAttributes(ClassBuilder builder) {
            // InnerClasses
            merge(Attributes.innerClasses(), InnerClassesAttribute::classes, info -> info.innerClass().asSymbol())
                    .ifPresent(l -> builder.with(InnerClassesAttribute.of(l)));
            // NestMembers
            merge(Attributes.nestMembers(), NestMembersAttribute::nestMembers, ClassEntry::asSymbol)
                    .ifPresent(l -> builder.with(NestMembersAttribute.of(l)));
            // PermittedSubclasses
            merge(Attributes.permittedSubclasses(), PermittedSubclassesAttribute::permittedSubclasses, ClassEntry::asSymbol)
                    .ifPresent(l -> builder.with(PermittedSubclassesAttribute.of(l)));
            // RuntimeVisibleAnnotations
            // TODO: Type Annotations; Annotations for fields/methods
            merge(Attributes.runtimeVisibleAnnotations(), RuntimeVisibleAnnotationsAttribute::annotations, Annotation::classSymbol)
                    .ifPresent(l -> builder.with(RuntimeVisibleAnnotationsAttribute.of(l)));
        }

        private <A extends Attribute<A>, T, K> Optional<List<T>> merge(AttributeMapper<A> mapper,
                                                                       Function<? super A, ? extends List<T>> listGetter,
                                                                       Function<? super T, ? extends K> keyExtractor) {
            Optional<A> first = clientModel.findAttribute(mapper);
            Optional<A> second = serverModel.findAttribute(mapper);
            return merge(first.orElse(null), second.orElse(null), listGetter, keyExtractor);
        }

        private static <A extends Attribute<A>, T, K> Optional<List<T>> merge(@Nullable A first, @Nullable A second,
                                                                              Function<? super A, ? extends List<T>> listGetter,
                                                                              Function<? super T, ? extends K> keyExtractor) {
            if (first != null && second != null) {
                List<T> result = CommonUtils.mergePreserveOrder(listGetter.apply(first), listGetter.apply(second), keyExtractor);
                if (result.isEmpty()) return Optional.empty();
                return Optional.of(result);
            }

            if (first == null && second == null) return Optional.empty();

            return Optional.of(listGetter.apply(Objects.requireNonNullElse(first, second)));
        }

        private void appendInvisibleAnnotations(ClassBuilder builder) {
            RuntimeInvisibleAnnotationsAttribute attr;
            if (sidedInterfaces.isEmpty()) {
                // Visit it as is
                attr = invisibleAnnotationsAttribute;
            } else {
                var prevInvisibleAnnotations = invisibleAnnotationsAttribute;
                ArrayList<Annotation> rootAnnotations = new ArrayList<>();
                ArrayList<AnnotationValue> envInterfaceElements = new ArrayList<>();
                envInterfaceElements.ensureCapacity(sidedInterfaces.size());

                if (prevInvisibleAnnotations != null) {
                    rootAnnotations.ensureCapacity(prevInvisibleAnnotations.annotations().size());
                    prevInvisibleAnnotations.annotations().forEach(a -> {
                        if (!a.classSymbol().equals(CD_ItfList)) {
                            if (!a.classSymbol().equals(CD_Itf)) {
                                rootAnnotations.add(a);
                            } else {
                                // merge to itfList
                                envInterfaceElements.add(AnnotationValue.ofAnnotation(a));
                            }
                        } else {
                            for (AnnotationElement element : a.elements()) {
                                if (element.name().equalsString("value") && element.value() instanceof AnnotationValue.OfArray array) {
                                    for (AnnotationValue value : array.values()) {
                                        if (value instanceof AnnotationValue.OfAnnotation ann) {
                                            envInterfaceElements.add(AnnotationValue.ofAnnotation(ann.annotation()));
                                        }
                                    }
                                }
                            }
                        }
                    });

                    sidedInterfaces.forEach((itf, side) -> envInterfaceElements.add(
                            AnnotationValue.ofAnnotation(Annotation.of(
                                CD_Itf,
                                AnnotationElement.of("itf", AnnotationValue.ofClass(itf)),
                                AnnotationElement.of("value", AnnotationValue.ofEnum(CD_Side, side))
                            ))
                    ));
                }

                rootAnnotations.add(Annotation.of(
                        CD_ItfList,
                        AnnotationElement.of("value", AnnotationValue.ofArray(envInterfaceElements))
                ));
                attr = RuntimeInvisibleAnnotationsAttribute.of(rootAnnotations);
            }
            merge(
                    attr,
                    serverModel.findAttribute(Attributes.runtimeInvisibleAnnotations()).orElse(null),
                    RuntimeInvisibleAnnotationsAttribute::annotations,
                    Annotation::classSymbol
            ).ifPresent(l -> builder.with(RuntimeInvisibleAnnotationsAttribute.of(l)));
        }
    }

    private static final class MemberTransform<E extends ClassFileElement, B extends ClassFileBuilder<E, B>> {
        private final ArrayList<Annotation> invisibleAnnotations = new ArrayList<>();

        private void accept(B builder, E element) {
            if (element instanceof RuntimeInvisibleAnnotationsAttribute attribute) {
                this.invisibleAnnotations.ensureCapacity(attribute.annotations().size());
                attribute.annotations().stream()
                        .filter(a -> !a.classSymbol().equals(CD_OnlyIn))
                        .forEach(invisibleAnnotations::add);
            } else {
                builder.with(element);
            }
        }

        private void finalize(String side, Consumer<? super RuntimeInvisibleAnnotationsAttribute> consumer) {
            invisibleAnnotations.add(Annotation.of(CD_OnlyIn, AnnotationElement.of(
                    "value", AnnotationValue.ofEnum(CD_Side, side)
            )));
            consumer.accept(RuntimeInvisibleAnnotationsAttribute.of(invisibleAnnotations));
        }

        static Merger<FieldModel> fieldMerger(ClassBuilder builder) {
            return Merger.specialWhenSided(
                    "CLIENT", "SERVER",
                    CommonUtils.dropSecond(builder::with),
                    (model, side) -> builder.transformField(model, new FieldTransform() {
                        private final MemberTransform<FieldElement, FieldBuilder> wrapped = new MemberTransform<>();

                        @Override
                        public void accept(FieldBuilder builder, FieldElement element) {
                            wrapped.accept(builder, element);
                        }

                        @Override
                        public void atEnd(FieldBuilder builder) {
                            wrapped.finalize(side, builder::with);
                        }
                    })
            );
        }

        static Merger<MethodModel> methodMerger(ClassBuilder builder) {
            return Merger.specialWhenSided(
                    "CLIENT", "SERVER",
                    CommonUtils.dropSecond(builder::with),
                    (model, side) -> builder.transformMethod(model, new MethodTransform() {
                        private final MemberTransform<MethodElement, MethodBuilder> wrapped = new MemberTransform<>();

                        @Override
                        public void accept(MethodBuilder builder, MethodElement element) {
                            wrapped.accept(builder, element);
                        }

                        @Override
                        public void atEnd(MethodBuilder builder) {
                            wrapped.finalize(side, builder::with);
                        }
                    })
            );
        }

        static ClassTransform asClassTransform(String side) {
            return new ClassTransform() {
                private final MemberTransform<ClassElement, ClassBuilder> transform = new MemberTransform<>();

                @Override
                public void accept(ClassBuilder builder, ClassElement element) {
                    transform.accept(builder, element);
                }

                @Override
                public void atEnd(ClassBuilder builder) {
                    transform.finalize(side, builder::with);
                }
            };
        }
    }
}
