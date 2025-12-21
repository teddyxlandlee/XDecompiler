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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.util.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public interface MappingProvider extends Identified {
    String SOURCE_NAMESPACE = "official";

    String id();

    @Nullable   // if null, then this mapping will not be considered to be decompiled
    String destNamespace();

    @NotNull
    MappingTreeView prepare(ClassMemberInfoPool classMembers, VersionManifest.VersionMeta versionMeta, String arg) throws IOException;

    default Collection<String> dependOn() {
        return Collections.emptyList();
    }

    default boolean isRemapTarget() {
        return destNamespace() != null;
    }

    static Map.Entry<MappingTreeView, Collection<MappingProvider>> prepareAll(Map<String, MappingProvider> map,
                                                                              Map<String, String> args,
                                                                              ClassMemberInfoPool classMemberInfoPool,
                                                                              VersionManifest.VersionMeta versionMeta) {
        map.values().forEach(provider -> {
            if (!map.keySet().containsAll(provider.dependOn()))
                throw new IllegalStateException("Mapping " + provider.id() + " depends on " + provider.dependOn() +
                        "; available providers are " + map.keySet());
        });

        MemoryMappingTree tree = new MemoryMappingTree();
        tree.setSrcNamespace(SOURCE_NAMESPACE);
        tree.setDstNamespaces(map.values().stream().map(MappingProvider::destNamespace).collect(Collectors.toList()));
        CopyOnWriteArrayList<MappingTreeView> treeViews = new CopyOnWriteArrayList<>();
        AtomicBoolean isNonEmptyTree = new AtomicBoolean();
        CopyOnWriteArrayList<MappingProvider> mappingsToRemap = new CopyOnWriteArrayList<>();

        ConcurrentUtils.runVirtual("mapping-provider", executors -> map.values().stream()
                .map(p -> CompletableFuture.supplyAsync(() -> {
                    try {
                        final MappingTreeView treeView = p.prepare(classMemberInfoPool, versionMeta, args.getOrDefault(p.id(), ""));
                        if (p.isRemapTarget()) mappingsToRemap.add(p);
                        if (!(treeView instanceof MappingUtil.EmptyMappingTreeView)) isNonEmptyTree.set(true);
                        return treeView;
                    } catch (FileNotFoundException e) {
                        LogUtils.getLogger().warn("Failed to prepare {} because the corresponding mapping is absent: {}",
                                p.id(), e.toString());
                        return null;
                    } catch (IOException e) {
                        CommonUtils.sneakyThrow(e);
                        throw new AssertionError();
                    }
                }, executors))
                .map(f -> f.thenAccept(treeViews::add))
        );

        if (!isNonEmptyTree.get()) {    // all mappings are empty
            return Map.entry(MappingUtil.emptyMappingTreeView(), mappingsToRemap);
        }

        try {
            for (MappingTreeView treeView : treeViews) {
                if (treeView != null)
                    treeView.accept(tree);
            }
        } catch (IOException e) {
            CommonUtils.sneakyThrow(e);
        }

        return Map.entry(tree, mappingsToRemap);
    }
}
