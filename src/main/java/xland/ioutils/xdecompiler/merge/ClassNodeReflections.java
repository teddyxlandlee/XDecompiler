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

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

final class ClassNodeReflections {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Collection<VarHandle> PUBLIC_FIELDS =
            Arrays.stream(ClassNode.class.getFields())
                    .filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .map(f -> {
                        try {
                            return LOOKUP.unreflectVarHandle(f);
                        } catch (IllegalAccessException e) {
                            throw new ExceptionInInitializerError(e);
                        }
                    })
                    .toList();

    static ClassNode copy(ClassNode node) {
        ClassNode copy = new ClassNode(Opcodes.ASM9);
        for (VarHandle varHandle : PUBLIC_FIELDS) {
            Object o = varHandle.get(node);
            if (List.class.isAssignableFrom(varHandle.varType())) {
                if (o != null) {
                    o = new ArrayList<>((Collection<?>) o);
                } else {
                    o = new ArrayList<>();
                }
            }

            if (o == null) continue;
            varHandle.set(copy, o);
        }
        return copy;
    }

    static void initLists(ClassNode node) {
        for (VarHandle varHandle : PUBLIC_FIELDS) {
            if (!List.class.isAssignableFrom(varHandle.varType())) continue;
            varHandle.compareAndSet(node, null, new ArrayList<>());
        }
    }

    private ClassNodeReflections() {}
}
