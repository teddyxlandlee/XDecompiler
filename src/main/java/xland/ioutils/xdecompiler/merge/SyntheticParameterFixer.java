/*
 * Copyright 2026 teddyxlandlee
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

import java.lang.classfile.*;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.AccessFlag;
import java.util.List;

public final class SyntheticParameterFixer implements ClassTransform {
    private final ClassDesc thisClass;

    public SyntheticParameterFixer(ClassDesc thisClass) {
        this.thisClass = thisClass;
    }

    private boolean disableMethodTransform;
    private List<ClassDesc> syntheticArgs = List.of();

    @Override
    public void accept(ClassBuilder builder, ClassElement element) {
        switch (element) {
            case ClassFileVersion version -> {
                if (version.majorVersion() >= ClassFile.JAVA_11_VERSION) {
                    disableMethodTransform = true;
                }
                builder.with(element);
            }
            case InnerClassesAttribute innerClasses when !disableMethodTransform -> {
                if (syntheticArgs.isEmpty()) {
                    for (InnerClassInfo info : innerClasses.classes()) {
                        if (info.innerClass().matches(thisClass) && // assumed to be parent class
                                info.innerName().isPresent() &&     // non-anonymous
                                info.outerClass().isPresent() &&    // non-anonymous
                                                                    // non-static
                                !info.flags().contains(AccessFlag.STATIC)) {
                            syntheticArgs = List.of(info.outerClass().get().asSymbol());
                            break;
                        }
                    }
                }
                builder.with(element);
            }
            case AccessFlags accessFlags when !disableMethodTransform -> {
                if (accessFlags.has(AccessFlag.ENUM)) {
                    syntheticArgs = List.of(ConstantDescs.CD_String, ConstantDescs.CD_int);
                }
                builder.with(element);
            }
            case MethodModel methodModel when (
                    !disableMethodTransform &&
                            methodModel.methodName().equalsString("<init>") &&
                            !syntheticArgs.isEmpty() &&
                            methodModel.methodTypeSymbol().parameterList().subList(0, syntheticArgs.size()).equals(syntheticArgs)
            ) -> {
                int truncatedCount = syntheticArgs.size();
                // transform synthetic method parameter annotations
                builder.transformMethod(methodModel, (methodBuilder, methodElement) -> methodBuilder.with(switch (methodElement) {
                    case RuntimeInvisibleParameterAnnotationsAttribute invParamAnnotation ->
                            RuntimeInvisibleParameterAnnotationsAttribute.of(truncate(
                                    invParamAnnotation.parameterAnnotations(), truncatedCount
                            ));
                    case RuntimeVisibleParameterAnnotationsAttribute visParamAnnotation ->
                        RuntimeVisibleParameterAnnotationsAttribute.of(truncate(
                                visParamAnnotation.parameterAnnotations(), truncatedCount
                        ));
                    default -> methodElement;
                }));
            }
            default -> builder.with(element);
        }
    }

    private static <E> List<E> truncate(List<E> list, int count) {
        return list.subList(count, list.size());
    }
}
