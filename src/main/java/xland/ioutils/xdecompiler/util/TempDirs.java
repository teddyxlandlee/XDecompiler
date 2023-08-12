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

import org.slf4j.Logger;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class TempDirs implements Closeable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Path baseDir;

    private static TempDirs dirs;

    private TempDirs(Path baseDir) {
        this.baseDir = baseDir;
    }

    public Path createFile() throws IOException {
        return createFile("tmp");
    }

    public Path createFile(String suffix) throws IOException {
        return Files.createTempFile(baseDir, UUID.randomUUID().toString(), suffix);
    }

    public File createFileDefaultFs() throws IOException {
        return createFileDefaultFs("tmp");
    }

    public File createFileDefaultFs(String suffix) throws IOException {
        return createFile(suffix).toFile();
    }

    static TempDirs create() {
        return new TempDirs(newBaseDir());
    }

    public static TempDirs get() {
        if (dirs != null) return dirs;
        synchronized (TempDirs.class) {
            dirs = create();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    LOGGER.info("Cleaning up TempDirs...");
                    dirs.close();
                } catch (IOException e) {
                    LOGGER.error("Failed to cleanup TempDirs at {}. Please Delete it manually if possible.",
                            dirs.baseDir, e);
                }
            }, "TempDirs-Cleanup"));
            return dirs;
        }
    }

    private static Path newBaseDir() {
        try {
            return Files.createTempDirectory(UUID.randomUUID().toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void flush() throws IOException {
        FileUtils.deleteRecursively(baseDir, true);
    }

    @Override
    public void close() throws IOException {
        FileUtils.deleteRecursively(baseDir, false);
    }
}
