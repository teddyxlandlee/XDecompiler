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
package xland.ioutils.xdecompiler.merge;

import xland.ioutils.xdecompiler.util.LogUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ExtractBundler {
    public static void run(JarFile bundlerJar, Path outputFile) throws IOException {
        List<FileEntry> entries;
        final JarEntry jarEntry = bundlerJar.getJarEntry("META-INF/versions.list");
        if (jarEntry == null)
            throw new IllegalStateException("Resource META-INF/versions.list not found");
        try (InputStream is = bundlerJar.getInputStream(jarEntry)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            entries = reader.lines().filter(s -> !s.isBlank()).map(FileEntry::parseLine).toList();
        }
        if (entries.size() != 1) {
            throw new IllegalStateException("Multiple versions found: " + entries);
        }

        checkAndExtractJar(bundlerJar, entries.getFirst(), outputFile);
    }

    private static void checkAndExtractJar(JarFile jarFile, FileEntry entry, Path outputFile) throws IOException {
        LogUtils.getLogger().info("Unpacking {} ({}:{}) to {}", entry.path, "versions", entry.id, outputFile);
        extractJar(jarFile, entry.path, outputFile);
    }

    private static void extractJar(JarFile jarFile, String jarPath, Path outputFile) throws IOException {
        Files.createDirectories(outputFile.getParent());

        final JarEntry jarEntry = jarFile.getJarEntry("META-INF/versions/" + jarPath);
        if (jarEntry == null)
            throw new IllegalStateException("Declared library " + jarPath + " not found");
        try (InputStream input = jarFile.getInputStream(jarEntry)) {
            Files.copy(input, outputFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record FileEntry(String hash, String id, String path) {
        public static FileEntry parseLine(String line) {
            String[] fields = line.split("\\s+");
            if (fields.length != 3) {
                throw new IllegalStateException("Malformed library entry: " + line);
            }
            return new FileEntry(fields[0], fields[1], fields[2]);
        }
    }

    private ExtractBundler() {}
}
