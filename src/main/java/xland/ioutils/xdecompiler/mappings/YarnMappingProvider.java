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

import mjson.Json;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.mcmeta.libraries.MavenArtifact;
import xland.ioutils.xdecompiler.util.LogUtils;
import xland.ioutils.xdecompiler.util.PublicProperties;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class YarnMappingProvider implements MappingProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String id() {
        return "yarn";
    }

    @Override
    public @Nullable String destNamespace() {
        return "yarn";
    }

    @Override
    public MappingTreeView prepare(VersionManifest.VersionMeta versionMeta, String arg) throws IOException {
        final String versionId = versionMeta.id();
        Json meta = Json.read(new URL("https://meta.fabricmc.net/v2/versions/yarn/" + versionId));
        if (meta.asJsonList().isEmpty()) {
            throw new RuntimeException("Missing yarn for version " + versionId);
        }

        if (arg.isEmpty() || "latest".equalsIgnoreCase(arg))
            meta = meta.at(0);
        else {
            int version;
            try {
                version = Integer.parseInt(arg);
                meta = meta.asJsonList().stream()
                        .filter(j -> j.at("build").asInteger() == version)
                        .findFirst()
                        .orElseThrow();
            } catch (NumberFormatException | NoSuchElementException e) {
                LOGGER.warn("Invalid mapping provider argument: {}, treat as default", arg);
                meta = meta.at(0);
            }
        }

        MavenArtifact artifact = MavenArtifact.of(meta.at("maven").asString());

        URL url = new URL(PublicProperties.fabricMaven());
        url = artifact.atMaven(url);

        MemoryMappingTree tree = new MemoryMappingTree();
        MappingVisitor visitor = tree;
        visitor = new MappingDstNsReorder(visitor, "yarn");
        visitor = new MappingSourceNsSwitch(visitor, "intermediary");
        visitor = new MappingNsRenamer(visitor, Map.of("named", "yarn"));

        if (xland.ioutils.xdecompiler.util.DebugUtils.flagged(3)) {
            var f = xland.ioutils.xdecompiler.util.TempDirs.get().createFile();
            LOGGER.info("Dumping mapping to {} due to debug flag 3...", f);
            try (var w = new net.fabricmc.mappingio.format.Tiny2Writer(java.nio.file.Files.newBufferedWriter(f), true)) {
                MappingVisitor visitor1 = w;
                visitor1 = new MappingDstNsReorder(visitor1, "yarn");
                visitor1 = new MappingSourceNsSwitch(visitor1, "intermediary");
                visitor1 = new MappingNsRenamer(visitor1, Map.of("named", "yarn"));
                MappingUtil.readV1RemoteJar(url, visitor1);
            }
        }

        MappingUtil.readV1RemoteJar(url, visitor);

        return tree;
    }

    @Override
    public Collection<String> dependOn() {
        return List.of("intermediary");
    }
}
