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
package xland.ioutils.xdecompiler.test;


import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.ProGuardReader;
import net.fabricmc.mappingio.format.Tiny2Writer;
import xland.ioutils.xdecompiler.mappings.ClassMemberInfoPool;
import xland.ioutils.xdecompiler.mcmeta.RemoteFile;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.merge.JarMerger;
import xland.ioutils.xdecompiler.util.TempDirs;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

public class MojMapsTest {
    public static void main(String[] args) throws IOException {
        System.setProperty("xdecompiler.internal.debug", String.valueOf(Integer.getInteger("xdecompiler.internal.debug", 0) | 2));

        final var detail = VersionManifest.getOrFetch()
                .getVersion("1.14.4").getOrFetchDetail();   // implicit null-check

        final File clientJar = TempDirs.get().createFileDefaultFs();
        final File serverJar0 = TempDirs.get().createFileDefaultFs();
        detail.clientJar().download(clientJar.toPath());
        detail.serverJar().download(serverJar0.toPath());
        System.out.println("\tServer jar is legacy, keep...");

        System.out.println("\tMerging...");
        final Path mergedJar = TempDirs.get().createFile(".jar");

        try (ZipOutputStream mergedJarOut = new ZipOutputStream(Files.newOutputStream(mergedJar))) {
            var jarMerger = new JarMerger(clientJar, serverJar0, mergedJarOut, null);
            jarMerger.enableSnowmanRemoval();
            jarMerger.enableSyntheticParamsOffset();
            jarMerger.merge();
        }

        final Path mappingOut = TempDirs.get().createFile(".tiny");
        System.out.println("Output mapping will be stored at " + mappingOut);

        try (MappingWriter mw = new Tiny2Writer(Files.newBufferedWriter(mappingOut), false)) {
            MappingVisitor visitor = mw;
            visitor = classMemberFilter(visitor, ClassMemberInfoPool.fromJar(mergedJar));
            visitor = new MappingSourceNsSwitch(visitor, "official");

            final RemoteFile clientMappings = detail.clientMappings(), serverMappings = detail.serverMappings();
            if (clientMappings == null || serverMappings == null)
                throw new FileNotFoundException("official mappings are absent for 1.14.4");

            read(clientMappings, visitor);
            read(serverMappings, visitor);
        }
    }

    private static final MethodHandle classMemberFilterM;

    static {
        var lookup = MethodHandles.lookup();
        try {
            var c = Class.forName("xland.ioutils.xdecompiler.mappings.MappingUtil");
            var m = c.getDeclaredMethod("classMemberFilter", MappingVisitor.class, ClassMemberInfoPool.class);
            if (m.trySetAccessible()) {
                classMemberFilterM = lookup.unreflect(m);
            } else throw new IllegalAccessException("Failed trySetAccessible: " + m);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static MappingVisitor classMemberFilter(MappingVisitor visitor, ClassMemberInfoPool classMembers) {
        try {
            return (MappingVisitor) classMemberFilterM.invoke(visitor, classMembers);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke classMemberFilter", t);
        }
    }

    private static void read(RemoteFile mapping, MappingVisitor visitor) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(mapping.openFilteredInputStream()))) {
            ProGuardReader.read(reader, "mojmaps", "official", visitor);
        }
    }
}
