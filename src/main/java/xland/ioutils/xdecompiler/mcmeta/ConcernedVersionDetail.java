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
package xland.ioutils.xdecompiler.mcmeta;

import mjson.Json;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import xland.ioutils.xdecompiler.mcmeta.libraries.Library;
import xland.ioutils.xdecompiler.mcmeta.libraries.MavenArtifact;
import xland.ioutils.xdecompiler.util.ConcurrentUtils;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public record ConcernedVersionDetail(RemoteFile clientJar, RemoteFile serverJar,
                                     @Nullable RemoteFile clientMappings, @Nullable RemoteFile serverMappings,
                                     List<Library> libraries,
                                     boolean isUnobfuscated) {
    /**
     * @deprecated <p>Unobfuscated-ness becomes a crucial property as of the un-obfuscation of Java Edition.
     * <p>See: <a href="https://www.minecraft.net/zh-hans/article/removing-obfuscation-in-java-edition">
     * Removing obfuscation in Java Edition</a>
     *
     * <p>Still need to confirm the technical change of intermediary/yarn:
     * <ul>
     *     <li>Do they still exist?</li>
     *     <li>If they still exist, then what changes are made to the <code>official</code> namespace?</li>
     * </ul>
     */
    @Deprecated(since = "1.6", forRemoval = true)
    public ConcernedVersionDetail(RemoteFile clientJar, RemoteFile serverJar,
                                  @Nullable RemoteFile clientMappings, @Nullable RemoteFile serverMappings,
                                  List<Library> libraries) {
        this(clientJar, serverJar, clientMappings, serverMappings, libraries, false);
    }

    private static final ChronoZonedDateTime<?> TIME_PRE_OBF_REMOVAL = ZonedDateTime.of(
            2025, 10, 30, 0, 0, 0, 0, ZoneId.of("UTC")
    );

    public static ConcernedVersionDetail fromJson(Json json) {
        Json sub = json.at("downloads");

        final RemoteFile clientJar, serverJar, clientMappings, serverMappings;
        clientJar = fileFromJson(sub.at("client"));
        serverJar = fileFromJson(sub.at("server"));
        clientMappings = fileFromJson(sub.at("client_mappings"));
        serverMappings = fileFromJson(sub.at("server_mappings"));

        sub = json.at("libraries");
        final List<Library> libraries = sub.asJsonList().stream()
                .map(lib -> {
                    MavenArtifact artifact = MavenArtifact.of(lib.at("name").asString());
                    if (isLibraryExcluded(artifact)) return null;
                    final RemoteFile remoteFile = fileFromJson(lib.at("downloads").at("artifact"));
                    Objects.requireNonNull(remoteFile, "artifact");
                    return new Library(artifact, remoteFile);
                })
                .filter(Objects::nonNull)
                .toList();

        // TODO: figure out the format of metadata of 'un-obfuscated “experimental release” versions'.
        // What is sure is that the "experimental releases" are not included in launcher meta, whose ids are suffixes with `_unobfuscated`.
        // reference: https://www.minecraft.net/zh-hans/article/removing-obfuscation-in-java-edition
        boolean isUnobfuscated = false;
        if (clientMappings == null && serverMappings == null) {
            ChronoZonedDateTime<?> releaseTime = VersionManifest.VersionMeta.zonedDateTime(json, "releaseTime");
            isUnobfuscated = TIME_PRE_OBF_REMOVAL.isBefore(releaseTime);
        }

        return new ConcernedVersionDetail(clientJar, serverJar, clientMappings, serverMappings, libraries, isUnobfuscated);
    }

    public Collection<Path> downloadLibrariesAsync(Path repo) {
        Collection<Path> paths = new CopyOnWriteArrayList<>();
        ConcurrentUtils.runVirtual("download-libraries", service -> libraries().stream()
                .map(lib -> CompletableFuture.supplyAsync(() -> lib.getOrDownload(repo), service))
                .map(c -> c.thenAccept(paths::add))
        );
        return paths;
    }

    private static boolean isLibraryExcluded(MavenArtifact artifact) {
        final String classifier = artifact.classifier();
        return switch (artifact.group()) {
            case "io.netty" -> classifier != null;
            case "org.lwjgl" -> classifier != null && classifier.startsWith("natives-");
            default -> false;
        };
    }

    @Contract("null->null")
    private static RemoteFile fileFromJson(@Nullable Json json) {
        if (json == null) return null;
        try {
            return RemoteFile.create(json.at("url").asString(), json.at("sha1").asString(), json.at("size").asLong());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL found: " + json.at("url"));
        }
    }
}
