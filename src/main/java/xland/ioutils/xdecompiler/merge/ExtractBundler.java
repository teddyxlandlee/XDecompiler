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

import org.slf4j.Logger;
import xland.ioutils.xdecompiler.mcmeta.HashingUtil;
import xland.ioutils.xdecompiler.util.LogUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ExtractBundler {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void run(JarFile bundlerJar, Path outputFile) throws IOException {
        readAndExtractDir(bundlerJar, outputFile);
    }

    private static <T> T readResource(JarFile bundlerJar, ResourceParser<T> parser) throws IOException {
        final JarEntry jarEntry = bundlerJar.getJarEntry("META-INF/versions.list");
        if (jarEntry == null)
            throw new IllegalStateException("Resource META-INF/versions.list not found");
        try (InputStream is = bundlerJar.getInputStream(jarEntry)) {
            return parser.parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
        }
    }

    private static void readAndExtractDir(JarFile jarFile, Path outputFile) throws IOException {
        List<FileEntry> entries = readResource(jarFile, reader -> reader.lines().map(FileEntry::parseLine).toList());
        for (FileEntry entry : entries) {
            if (entry.path.startsWith("minecraft-server")) {
                continue;
            }
            checkAndExtractJar(jarFile, entry, outputFile);
        }
    }

    private static void checkAndExtractJar(JarFile jarFile, FileEntry entry, Path outputFile) throws IOException {
        if (!Files.exists(outputFile) || !checkIntegrity(outputFile, entry.hash())) {
            LOGGER.info("Unpacking {} ({}:{}) to {}", entry.path, "versions", entry.id, outputFile);
            extractJar(jarFile, entry.path, outputFile);
        }
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

    private static boolean checkIntegrity(Path file, String expectedHash) throws IOException {
        MessageDigest digest = HashingUtil.sha256();

        try (InputStream output = Files.newInputStream(file)) {
            output.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));

            byte[] actualHash = (digest.digest());
            if (HashingUtil.isSame(expectedHash, actualHash))
                return true;

            LOGGER.info("Expected file {} to have hash {}, but got {}", file, expectedHash, HashingUtil.stringify(actualHash));
        }
        return false;
    }

    @FunctionalInterface
    private interface ResourceParser<T> {
        T parse(BufferedReader param1BufferedReader) throws IOException;
    }

    private record FileEntry(String hash, String id, String path) {
        public static FileEntry parseLine(String line) {
            String[] fields = line.split("\\s+");
            if (fields.length != 3) {
                throw new IllegalStateException("Malformed library entry: " + line);
            }
            String id = fields[1];
            String path = fields[2];
            return new FileEntry(fields[0], id, path);
        }
    }
}
