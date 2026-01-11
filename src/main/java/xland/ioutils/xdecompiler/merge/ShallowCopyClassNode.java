package xland.ioutils.xdecompiler.merge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import xland.ioutils.xdecompiler.util.CommonUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

final class ShallowCopyClassNode {
    private static final Collection<Field> PUBLIC_FIELDS =
            Arrays.stream(ClassNode.class.getFields())
                    .filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .toList();

    static ClassNode copy(ClassNode node) {
        ClassNode copy = new ClassNode(Opcodes.ASM9);
        for (Field field : PUBLIC_FIELDS) {
            try {
                Object o = field.get(node);
                if (List.class.isAssignableFrom(field.getType())) {
                    if (o != null) {
                        o = new ArrayList<>((Collection<?>) o);
                    } else {
                        o = new ArrayList<>();
                    }
                }

                if (o == null) continue;
                field.set(copy, o);
            } catch (IllegalAccessException e) {
                CommonUtils.sneakyThrow(e);
            }
        }
        return copy;
    }
}
