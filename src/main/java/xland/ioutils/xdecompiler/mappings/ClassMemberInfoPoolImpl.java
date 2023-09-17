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
package xland.ioutils.xdecompiler.mappings;

import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ClassMemberInfoPoolImpl implements ClassMemberInfoPool {
    private final Map<String, Set<Map.Entry<String, String>>> fieldMap, methodMap;

    ClassMemberInfoPoolImpl() {
        fieldMap = new HashMap<>();
        methodMap = new HashMap<>();
    }

    static ClassMemberInfoPoolImpl fromJar(Path jar) throws IOException {
        ClassMemberInfoPoolImpl pool = new ClassMemberInfoPoolImpl();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.getName().endsWith(".class"))
                    continue;
                ClassReader cr = new ClassReader(zis);
                final String className = cr.getClassName();
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        putMember(pool.fieldMap, className, name, descriptor);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        putMember(pool.methodMap, className, name, descriptor);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            }
        }
        return pool;
    }

    @Override
    public boolean hasField(String owner, String name, String desc) {
        return hasMember(fieldMap, owner, name, desc);
    }

    @Override
    public boolean hasMethod(String owner, String name, String desc) {
        return hasMember(methodMap, owner, name, desc);
    }

    private static boolean hasMember(Map<String, Set<Map.Entry<String, String>>> map,
                                     String owner, String name, String desc) {
        var classInfo = map.get(owner.replace('.', '/'));
        if (classInfo == null) return false;
        return classInfo.contains(Map.entry(name, desc));
    }

    private static void putMember(Map<String, Set<Map.Entry<String, String>>> map,
                                  String owner, String name, String desc) {
        map.computeIfAbsent(owner, k -> new HashSet<>()).add(Map.entry(name, desc));
    }
}
