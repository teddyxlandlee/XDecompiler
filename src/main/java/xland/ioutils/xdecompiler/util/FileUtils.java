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
package xland.ioutils.xdecompiler.util;

import mjson.Json;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileUtils {
    public static void deleteRecursively(Path root, boolean retainRoot) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                if (retainRoot && root.equals(dir)) return FileVisitResult.CONTINUE;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static Json readAsJson(Path path) throws IOException {
        return Json.read(Files.readString(path));
    }

    public static void extractZip(Path archive, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    Files.createDirectories(outDir.resolve(entry.getName()));
                    continue;
                }
                Path resolve = outDir.resolve(entry.getName());
                Files.createDirectories(resolve.getParent());
                Files.copy(zis, resolve);
            }
        }
    }

    public static File pathToFile(Path path) {
        try {
            return path.toFile();
        } catch (UnsupportedOperationException e) {
            try {
                var file = TempDirs.get().createFileDefaultFs();
                Files.copy(path, file.toPath());
                return file;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }
}
