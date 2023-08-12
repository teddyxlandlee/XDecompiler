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
import org.slf4j.Logger;
import xland.ioutils.xdecompiler.util.FileUtils;
import xland.ioutils.xdecompiler.util.LogUtils;
import xland.ioutils.xdecompiler.util.PublicProperties;
import xland.ioutils.xdecompiler.util.TempDirs;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.time.chrono.ChronoZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class VersionManifest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile VersionManifest instance;
    private static final Object LOCK = new Object();
    private final String latestRelease;
    private final String latestSnapshot;
    private final List<VersionMeta> versions;
    private volatile transient List<String> versionNameCache;
    private final transient Object versionNameCacheLock = new Object();

    public VersionManifest(String latestRelease, String latestSnapshot, List<VersionMeta> versions) {
        this.latestRelease = latestRelease;
        this.latestSnapshot = latestSnapshot;
        this.versions = versions;
    }

    public static VersionManifest getOrFetch() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    try {
                        URL url = new URL(PublicProperties.versionManifestUrl());
                        instance = fromJson(Json.read(url));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to get version manifest from " + PublicProperties.versionManifestUrl(), e);
                    }
                }
            }
        }
        return instance;
    }

    public static VersionManifest fromJson(Json json) {
        final String latestRelease = json.at("latest").at("release").asString();
        final String latestSnapshot = json.at("latest").at("snapshot").asString();
        final List<VersionMeta> versions = json.at("versions").asJsonList()
                .stream().map(VersionMeta::fromJson).toList();
        return new VersionManifest(latestRelease, latestSnapshot, versions);
    }

    public String latestRelease() {
        return latestRelease;
    }

    public String latestSnapshot() {
        return latestSnapshot;
    }

    public List<VersionMeta> versions() {
        return versions;
    }

    public VersionMeta getVersion(String id) {
        final List<String> cache = versionNameCache();
        final int i = cache.indexOf(id);
        if (i < 0) return null;
        return versions().get(i);
    }

    List<String> versionNameCache() {
        if (versionNameCache == null) {
            synchronized (versionNameCacheLock) {
                if (versionNameCache == null)
                    versionNameCache = versions().stream().map(VersionMeta::id).toList();
            }
        }
        return versionNameCache;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (VersionManifest) obj;
        return Objects.equals(this.latestRelease, that.latestRelease) &&
                Objects.equals(this.latestSnapshot, that.latestSnapshot) &&
                Objects.equals(this.versions, that.versions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(latestRelease, latestSnapshot, versions);
    }

    @Override
    public String toString() {
        return "VersionManifest[" +
                "latestRelease=" + latestRelease + ", " +
                "latestSnapshot=" + latestSnapshot + ", " +
                "versions=" + versions + ']';
    }


    public static final class VersionMeta {
        private final String id;
        private final boolean isSnapshot;
        private final boolean isLegacy;
        private final RemoteFile file;
        private final ChronoZonedDateTime<?> time;
        private final ChronoZonedDateTime<?> releaseTime;

        private volatile transient ConcernedVersionDetail detail;
        private final transient Object detailLock = new Object();

        public VersionMeta(String id, boolean isSnapshot, boolean isLegacy, RemoteFile file, ChronoZonedDateTime<?> time, ChronoZonedDateTime<?> releaseTime) {
            this.id = id;
            this.isSnapshot = isSnapshot;
            this.isLegacy = isLegacy;
            this.file = file;
            this.time = time;
            this.releaseTime = releaseTime;
        }

        public static VersionMeta fromJson(Json json) {
            final String id = json.at("id").asString();
            final ChronoZonedDateTime<?> time = zonedDateTime(json, "time"), releaseTime = zonedDateTime(json, "releaseTime");

            final RemoteFile file;
            try {
                file = RemoteFile.create(json.at("url").asString(), json.at("sha1").asString());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Got invalid url at " + id + ": " + json.at("url"), e);
            }

            String type = json.at("type").asString();
            boolean isSnapshot, isLegacy = false;
            switch (type) {
                case "release" -> isSnapshot = false;
                case "snapshot" -> isSnapshot = true;
                case "old_beta", "old_alpha" -> {
                    isSnapshot = false;
                    isLegacy = true;
                }
                default -> {
                    LOGGER.warn("Unrecognized version type `{}` at {}", type, id);
                    isSnapshot = false;
                }
            }

            return new VersionMeta(id, isSnapshot, isLegacy, file, time, releaseTime);
        }

        private static ChronoZonedDateTime<?> zonedDateTime(Json json, String key) {
            return ChronoZonedDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(json.at(key).asString()));
        }

        public ConcernedVersionDetail getOrFetchDetail() {
            if (detail == null) {
                synchronized (detailLock) {
                    if (detail == null) {
                        try {
                            Path path = TempDirs.get().createFile();
                            this.file().download(path);
                            detail = ConcernedVersionDetail.fromJson(FileUtils.readAsJson(path));
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to fetch detail of " + id, e);
                        }
                    }
                }
            }
            return detail;
        }

        public String id() {
            return id;
        }

        public boolean isSnapshot() {
            return isSnapshot;
        }

        public boolean isLegacy() {
            return isLegacy;
        }

        public RemoteFile file() {
            return file;
        }

        public ChronoZonedDateTime<?> time() {
            return time;
        }

        public ChronoZonedDateTime<?> releaseTime() {
            return releaseTime;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (VersionMeta) obj;
            return Objects.equals(this.id, that.id) &&
                    this.isSnapshot == that.isSnapshot &&
                    this.isLegacy == that.isLegacy &&
                    Objects.equals(this.file, that.file) &&
                    Objects.equals(this.time, that.time) &&
                    Objects.equals(this.releaseTime, that.releaseTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, isSnapshot, isLegacy, file, time, releaseTime);
        }

        @Override
        public String toString() {
            return "VersionMeta[" +
                    "id=" + id + ", " +
                    "isSnapshot=" + isSnapshot + ", " +
                    "isLegacy=" + isLegacy + ", " +
                    "file=" + file + ", " +
                    "time=" + time + ", " +
                    "releaseTime=" + releaseTime + ']';
        }

    }
}
