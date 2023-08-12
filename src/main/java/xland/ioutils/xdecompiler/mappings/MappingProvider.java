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

import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.jetbrains.annotations.Nullable;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.util.Identified;
import xland.ioutils.xdecompiler.util.PublicProperties;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public interface MappingProvider extends Identified {
    String SOURCE_NAMESPACE = "official";

    String id();

    @Nullable   // if null, then this mapping will not be considered to be decompiled
    String destNamespace();

    MappingTreeView prepare(VersionManifest.VersionMeta versionMeta, String arg) throws IOException;

    default Collection<String> dependOn() {
        return Collections.emptyList();
    }

    default boolean isRemapTarget() {
        return destNamespace() != null;
    }

    static MappingTreeView prepareAll(Map<String, MappingProvider> map, Map<String, String> args, VersionManifest.VersionMeta versionMeta) {
        map.values().forEach(provider -> {
            if (!map.keySet().containsAll(provider.dependOn()))
                throw new IllegalStateException("Mapping " + provider.id() + " depends on " + provider.dependOn() +
                        "; available providers are " + map.keySet());
        });

        MemoryMappingTree tree = new MemoryMappingTree();
        tree.setSrcNamespace("official");
        ExecutorService executors = Executors.newFixedThreadPool(PublicProperties.mappingThreads());

        CompletableFuture.allOf(map.values().stream()
                .map(p -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return p.prepare(versionMeta, args.getOrDefault(p.id(), ""));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, executors))
                .map(f -> f.thenAccept(t -> {
                    try {
                        t.accept(tree);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }))
                .toArray(CompletableFuture[]::new)
        ).join();

        executors.shutdown();

        return tree;
    }
}
