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
import net.fabricmc.mappingio.format.ProGuardReader;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.jetbrains.annotations.Nullable;
import xland.ioutils.xdecompiler.mcmeta.ConcernedVersionDetail;
import xland.ioutils.xdecompiler.mcmeta.RemoteFile;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class MojMapsMappingProvider implements MappingProvider {
    @Override
    public String id() {
        return "mojmaps";
    }

    @Override
    public @Nullable String destNamespace() {
        return "mojmaps";
    }

    @Override
    public MappingTreeView prepare(VersionManifest.VersionMeta versionMeta, String arg) throws IOException {
        final ConcernedVersionDetail detail = versionMeta.getOrFetchDetail();

        MemoryMappingTree tree = new MemoryMappingTree();
        MappingVisitor visitor = tree;
        visitor = new MappingSourceNsSwitch(visitor, "official");

        read(detail.clientMappings(), visitor);
        read(detail.serverMappings(), visitor);
        return tree;
    }

    private static void read(RemoteFile mapping, MappingVisitor visitor) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(mapping.openFilteredInputStream()))) {
            ProGuardReader.read(reader, "mojmaps", "official", visitor);
        }
    }
}
