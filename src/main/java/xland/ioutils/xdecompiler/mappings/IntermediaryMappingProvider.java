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
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.mcmeta.libraries.MavenArtifact;
import xland.ioutils.xdecompiler.util.PublicProperties;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;

public class IntermediaryMappingProvider implements MappingProvider {

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
    public MappingTreeView prepare(VersionManifest.VersionMeta versionMeta, String arg) throws IOException {
        final String versionId = versionMeta.id();
        Json meta = Json.read(new URL("https://meta.fabricmc.net/v2/versions/intermediary/" + versionId));
        if (meta.asJsonList().isEmpty()) {
            throw new FileNotFoundException("Missing intermediary for version " + versionId);
        }
        MavenArtifact artifact = MavenArtifact.of(meta.at(0).at("maven").asString());

        URL url = new URL(PublicProperties.fabricMaven());
        url = artifact.atMaven(url);

        MemoryMappingTree tree = new MemoryMappingTree();
        // No other intermediate visitors
        MappingUtil.readV1RemoteJar(url, tree);
        return tree;
    }
}
