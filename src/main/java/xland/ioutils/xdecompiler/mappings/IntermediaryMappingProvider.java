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
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.mcmeta.libraries.MavenArtifact;
import xland.ioutils.xdecompiler.util.LogUtils;
import xland.ioutils.xdecompiler.util.PublicProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URL;

public class IntermediaryMappingProvider implements MappingProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String id() {
        return "intermediary";
    }

    @Override
    public String destNamespace() {
        return "intermediary";
    }

    @Override
    @NotNull
    public MappingTreeView prepare(ClassMemberInfoPool classMemberInfoPool, VersionManifest.VersionMeta versionMeta, String arg) throws IOException {
        // shortcut: unobfuscated versions has no intermediary or yarn mappings
        if (versionMeta.getOrFetchDetail().isUnobfuscated()) {
            return MappingUtil.emptyMappingTreeView();
        }

        final String versionId = versionMeta.id();
        Json meta = Json.read(URI.create("https://meta.fabricmc.net/v2/versions/intermediary/" + versionId).toURL());
        if (meta.asJsonList().isEmpty()) {
            LOGGER.warn("Missing intermediary for version {}", versionId);
            return MappingUtil.emptyMappingTreeView();
        }
        MavenArtifact artifact = MavenArtifact.of(meta.at(0).at("maven").asString());

        URL url = URI.create(PublicProperties.fabricMaven()).toURL();
        url = artifact.atMaven(url);

        MemoryMappingTree tree = new MemoryMappingTree();
        // No other intermediate visitors
        MappingUtil.readV1RemoteJar(url, tree);
        return tree;
    }
}
