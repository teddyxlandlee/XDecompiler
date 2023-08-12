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
package xland.ioutils.xdecompiler.remap;

import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyRemapper;

import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class RemapUtil {
    private static final Pattern MC_LV_PATTERN = Pattern.compile("\\$\\$\\d+");

    public static IMappingProvider create(MappingTreeView mappings, String from, String to, boolean remapLocalVariables) {
        return (acceptor) -> {
            final int fromId = mappings.getNamespaceId(from);
            final int toId = mappings.getNamespaceId(to);

            for (MappingTreeView.ClassMappingView classDef : mappings.getClasses()) {
                String className = classDef.getName(fromId);
                String dstName = classDef.getName(toId);

                if (dstName == null) {
                    // Unsure if this is correct, should be better than crashing tho.
                    dstName = className;
                }

                acceptor.acceptClass(className, dstName);

                for (MappingTreeView.FieldMappingView field : classDef.getFields()) {
                    acceptor.acceptField(memberOf(className, field.getName(fromId), field.getDesc(fromId)), field.getName(toId));
                }

                for (MappingTreeView.MethodMappingView method : classDef.getMethods()) {
                    IMappingProvider.Member methodIdentifier = memberOf(className, method.getName(fromId), method.getDesc(fromId));
                    acceptor.acceptMethod(methodIdentifier, method.getName(toId));

                    if (remapLocalVariables) {
                        for (MappingTreeView.MethodArgMappingView parameter : method.getArgs()) {
                            String name = parameter.getName(toId);

                            if (name == null) {
                                continue;
                            }

                            acceptor.acceptMethodArg(methodIdentifier, parameter.getLvIndex(), name);
                        }

                        for (MappingTreeView.MethodVarMappingView localVariable : method.getVars()) {
                            acceptor.acceptMethodVar(methodIdentifier, localVariable.getLvIndex(),
                                    localVariable.getStartOpIdx(), localVariable.getLvtRowIndex(),
                                    localVariable.getName(toId));
                        }
                    }
                }
            }
        };
    }

    private static IMappingProvider.Member memberOf(String className, String memberName, String descriptor) {
        return new IMappingProvider.Member(className, memberName, descriptor);
    }

    public static TinyRemapper getTinyRemapper(MappingTreeView mappingTree, String fromM, String toM, Consumer<TinyRemapper.Builder> builderConsumer) {
        return getTinyRemapper(create(mappingTree, fromM, toM, true), builderConsumer);
    }

    public static TinyRemapper getTinyRemapper(IMappingProvider mappingProvider, Consumer<TinyRemapper.Builder> builderConsumer) {
        TinyRemapper.Builder builder = TinyRemapper.newRemapper()
                .withMappings(mappingProvider)
                //.withMappings(out -> JSR_TO_JETBRAINS.forEach(out::acceptClass))
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .invalidLvNamePattern(MC_LV_PATTERN)
                .inferNameFromSameLvIndex(true);

        builderConsumer.accept(builder);
        return builder.build();
    }
}
