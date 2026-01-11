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

import org.jetbrains.annotations.Nullable;

import java.lang.classfile.*;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.SourceDebugExtensionAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.util.List;
import java.util.Optional;

@Deprecated
public final class SnowmanRemover implements ClassTransform {
    private static final String SNOWMAN = "☃";  // U+2603

    private static boolean isSnowman(@Nullable Utf8Entry utf8Entry) {
        return utf8Entry != null && utf8Entry.stringValue().startsWith(SNOWMAN);
    }

    @Override
    public void accept(ClassBuilder classBuilder, ClassElement classElement) {
        switch (classElement) {
            // Don't trust the obfuscation on this
            case SourceFileAttribute _, SourceDebugExtensionAttribute _ -> {}
            case MethodModel method -> classBuilder.transformMethod(method, (methodBuilder, methodElement) -> {
                switch (methodElement) {
                    case MethodParametersAttribute params -> {
                        List<MethodParameterInfo> parameterInfos = params.parameters().stream()
                                .map(p -> {
                                    if (isSnowman(p.name().orElse(null)))
                                        return MethodParameterInfo.of(Optional.empty(), p.flagsMask());
                                    else
                                        return p;
                                })
                                .toList();
                        methodBuilder.with(MethodParametersAttribute.of(parameterInfos));
                    }
                    case CodeModel code -> methodBuilder.transformCode(
                            code,
                            (codeBuilder, codeElement) -> codeBuilder.with(switch (codeElement) {
                                case LocalVariable lv -> !isSnowman(lv.name()) ? codeElement : LocalVariable.of(
                                        lv.slot(),
                                        codeBuilder.constantPool().utf8Entry("$$" + lv.slot()),
                                        lv.type(),
                                        lv.startScope(), lv.endScope()
                                );
                                case LocalVariableType lvt -> !isSnowman(lvt.name()) ? codeElement : LocalVariableType.of(
                                        lvt.slot(),
                                        codeBuilder.constantPool().utf8Entry("$$" + lvt.slot()),
                                        lvt.signature(),
                                        lvt.startScope(), lvt.endScope()
                                );
                                default -> codeElement;
                            })
                    );
                    default -> methodBuilder.with(methodElement);
                }
            });
            default -> classBuilder.with(classElement);
        }
    }
}
