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

import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import xland.ioutils.xdecompiler.mcmeta.ConcernedVersionDetail;
import xland.ioutils.xdecompiler.mcmeta.RemoteFile;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.util.LogUtils;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;

public class MojMapsMappingProvider implements MappingProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String id() {
        return "mojmaps";
    }

    @Override
    public @Nullable String destNamespace() {
        return "mojmaps";
    }

    @Override
    @NotNull
    public MappingTreeView prepare(ClassMemberInfoPool classMembers, VersionManifest.VersionMeta versionMeta, String arg) throws IOException {
        final ConcernedVersionDetail detail = versionMeta.getOrFetchDetail();

        if (detail.isUnobfuscated()) {
            // as-is; we don't need to remap anymore
            return MappingUtil.emptyMappingTreeView();
        }

        final RemoteFile clientMappings = detail.clientMappings(), serverMappings = detail.serverMappings();
        if (clientMappings == null || serverMappings == null) {
            throw new FileNotFoundException("official mappings are absent for " + versionMeta.id());
        }

        MemoryMappingTree tree = new MemoryMappingTree();
        MappingVisitor visitor = tree;
        visitor = MappingUtil.classMemberFilter(visitor, classMembers);
        visitor = new MappingSourceNsSwitch(visitor, SOURCE_NAMESPACE);

        read(clientMappings, visitor);
        read(serverMappings, visitor);
        return tree;
    }

    private static void read(RemoteFile mapping, MappingVisitor visitor) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(mapping.openFilteredInputStream()))) {
            if (xland.ioutils.xdecompiler.util.DebugUtils.flagged(4)) {
                var f = xland.ioutils.xdecompiler.util.TempDirs.get().createFile();
                LOGGER.info("Writing mapping to {} due to debug flag 4", f);
                try (var visitor0 = new net.fabricmc.mappingio.format.tiny.Tiny2FileWriter(java.nio.file.Files.newBufferedWriter(f), true)) {
                    MappingVisitor visitor1 = visitor0;
                    visitor1 = new MappingSourceNsSwitch(visitor1, SOURCE_NAMESPACE);
                    ProGuardFileReader.read(reader, "mojmaps", SOURCE_NAMESPACE, visitor1);
                }
            }
            ProGuardFileReader.read(reader, "mojmaps", SOURCE_NAMESPACE, visitor);
        }
    }
}
