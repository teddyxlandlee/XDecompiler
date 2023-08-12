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
import xland.ioutils.xdecompiler.mcmeta.libraries.Library;
import xland.ioutils.xdecompiler.mcmeta.libraries.MavenArtifact;
import xland.ioutils.xdecompiler.util.PublicProperties;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public record ConcernedVersionDetail(RemoteFile clientJar, RemoteFile serverJar,
                                     RemoteFile clientMappings, RemoteFile serverMappings,
                                     List<Library> libraries) {
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
                    return new Library(artifact, remoteFile);
                })
                .filter(Objects::nonNull)
                .toList();
        return new ConcernedVersionDetail(clientJar, serverJar, clientMappings, serverMappings, libraries);
    }

    public Collection<Path> downloadLibrariesAsync(Path repo) {
        Collection<Path> paths = new CopyOnWriteArrayList<>();
        ExecutorService service = Executors.newFixedThreadPool(PublicProperties.downloadThreads());

        CompletableFuture.allOf(libraries().stream()
                .map(lib -> CompletableFuture.supplyAsync(() -> lib.getOrDownload(repo), service))
                .map(c -> c.thenAccept(paths::add))
                .toArray(CompletableFuture[]::new)
        ).join();

        service.shutdown();
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

    private static RemoteFile fileFromJson(Json json) {
        try {
            return RemoteFile.create(json.at("url").asString(), json.at("sha1").asString(), json.at("size").asLong());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL found: " + json.at("url"));
        }
    }
}
