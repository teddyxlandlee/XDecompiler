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
package xland.ioutils.xdecompiler.script.difftwo;

import joptsimple.OptionSet;
import joptsimple.util.PathConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import xland.ioutils.xdecompiler.mcmeta.HashingUtil;
import xland.ioutils.xdecompiler.mcmeta.RemoteFile;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.script.Script;
import xland.ioutils.xdecompiler.util.CommonUtils;
import xland.ioutils.xdecompiler.util.LogUtils;
import xland.ioutils.xdecompiler.util.PublicProperties;
import xland.ioutils.xdecompiler.util.TimeUtils;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

public class DiffTwoScript extends Script {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final String ver1, ver2;
    private final Path baseDir;
    private final Path dir1, dir2;
    private final Path output;

    private DiffTwoScript(String ver1, String ver2, Path baseDir, Path out1, Path out2, Path output) {
        this.ver1 = ver1;
        this.ver2 = ver2;
        this.baseDir = baseDir;
        dir1 = out1;
        dir2 = out2;
        this.output = output;
    }

    @Override
    protected void runScript() throws Exception {
        LOGGER.info("1. Prepare DiffPatch tool...");
        Path pathToJar = getOrCreateDiffPatch();
        final String[] args = {
                "-dA", "ZIP",
                "-o", output.toString(),
                dir1.toString(), dir2.toString()
        };

        final URLClassLoader cl = new URLClassLoader(new URL[]{pathToJar.toUri().toURL()});
        LOGGER.info("2. Diffing {} -> {}", ver1, ver2);
        final Thread thread = getThread(cl, args);
        thread.start();
        thread.join();
    }

    @NotNull
    private static Thread getThread(URLClassLoader cl, String[] args) {
        Thread thread = new Thread(() -> {
            long t0 = System.nanoTime();
            var lookup = MethodHandles.lookup();
            try {
                Class<?> c = Class.forName("codechicken.diffpatch.DiffPatch", true, cl);
                var handle = lookup.findStatic(c, "main", MethodType.methodType(void.class, String[].class))
                        .asFixedArity()
                        .bindTo(args);
                handle.invoke();
            } catch (Throwable t) {
                CommonUtils.sneakyThrow(t);
            }
            LOGGER.info("Diff completed in {}", TimeUtils.timeFormat(System.nanoTime() - t0));
        }, "DiffTwoScript-DiffPatch");
        thread.setContextClassLoader(cl);
        return thread;
    }

    private static String diffPatchUrl() {
        return System.getProperty("xdecompiler.script.difftwo.diffpatch.url",
                "https://maven.neoforged.net/releases/net/minecraftforge/DiffPatch/2.0.7/DiffPatch-2.0.7-all.jar"
        );
    }

    private static String diffPatchSha512() {
        return System.getProperty("xdecompiler.script.difftwo.diffpatch.sha512",
                "4803b69251f845667a8d56fd268d629e8934007f117b554c2327bc4622c4a132ca3ba64b90b80fbedd7e6ebe520c1cfd8eef55db1e3b2e7493dec50ca0df2ec5"
        );
    }

    private static final Object diffPatchLock = new Object();
    private volatile Path diffPatch;

    private Path getOrCreateDiffPatch() throws IOException {
        if (diffPatch == null) {
            synchronized (diffPatchLock) {
                if (diffPatch == null) {
                    final Path path = this.baseDir.resolve("DiffPatch.jar");
                    final RemoteFile remoteFile = new RemoteFile(
                            URI.create(diffPatchUrl()).toURL(),
                            diffPatchSha512(),
                            null,
                            HashingUtil::sha512
                    );
                    if (!remoteFile.matchHash(path))
                        remoteFile.download(path);
                    diffPatch = path;
                }
            }
        }
        return diffPatch;
    }

    public static void main(String[] args) {
        start(parser -> {
            var mappings0 = parser.accepts("mappings")
                    .withRequiredArg()
                    .defaultsTo("intermediary", "yarn=latest", "mojmaps");
            var decompiler0 = parser.accepts("decompiler", "Decompiler used")
                    .withRequiredArg()
                    .defaultsTo("vineflower");
            final Path thisDir = Path.of(".");
            var workingDir0 = parser.accepts("working-dir", "Working directory")
                    .withRequiredArg()
                    .withValuesConvertedBy(new PathConverter())
                    .defaultsTo(thisDir);
            var nonOptions0 = parser.nonOptions("<version1> <version2>, in which version can be " +
                    "either string or LATEST[_RELEASE][-[R]<num>]");
            var output0 = parser.accepts("output", "Output archive")
                    .withRequiredArg()
                    .withValuesConvertedBy(new PathConverter());

            var exc1 = parser.accepts("1exc", "Don't generate source for ver1");
            var exc2 = parser.accepts("2exc", "Don't generate source for ver2");

            var dir1 = parser.accepts("1dir", "Subdir name for ver1")
                    .withRequiredArg()
                    .defaultsTo("out1");
            var dir2 = parser.accepts("2dir", "Subdir name for ver2")
                    .withRequiredArg()
                    .defaultsTo("out2");

            if (args.length == 0)
                printHelpAndExit(parser);

            final OptionSet parsed = parser.parse(args);

            List<String> mappings = parsed.valuesOf(mappings0);
            String decompiler = parsed.valueOf(decompiler0);
            Path workingDir = parsed.valueOf(workingDir0);
            Path output = parsed.valueOf(output0);

            if (output == null)
                printHelpAndExit(parser);

            String out1 = parsed.valueOf(dir1);
            String out2 = parsed.valueOf(dir2);
            if (checkValidSubdirName(out1)) printHelpAndExit(parser);
            if (checkValidSubdirName(out2)) printHelpAndExit(parser);

            boolean hasExc1 = parsed.has(exc1), hasExc2 = parsed.has(exc2);

            List<String> nonOptions = parsed.valuesOf(nonOptions0);
            if (nonOptions.size() != 2 || nonOptions.stream().anyMatch(Objects::isNull))
                printHelpAndExit(parser);
            String ver1 = nonOptions.get(0), ver2 = nonOptions.get(1);
            if (!hasExc1) ver1 = getRealVersion(ver1);
            if (!hasExc2) ver2 = getRealVersion(ver2);
            if (Objects.equals(ver1, ver2)) {
                LOGGER.error("ver1 == ver2 ({})", ver1);
                printHelpAndExit(parser);
            }

            LOGGER.info("Diffing for {} -> {}", ver1, ver2);

            if (hasExc1 && hasExc2) {
                // Don't need to run the toolchain
                return new DiffTwoScript(ver1, ver2, workingDir, workingDir.resolve(out1), workingDir.resolve(out2), output);
            }

            List<String> basicArgs = new ArrayList<>();
            Collections.addAll(basicArgs, "--decompiler", decompiler);
            mappings.forEach(s -> Collections.addAll(basicArgs, "--mappings", s));
            if (!workingDir.toAbsolutePath().equals(thisDir.toAbsolutePath())) {
                Collections.addAll(basicArgs, "--lib-cache", workingDir.resolve("libraries").toString());
                System.setProperty("xdecompiler.vineflower.log.dir", workingDir.resolve(PublicProperties.vineFlowerLogDir()).toString());
            }

            List<String> ver1Args = new ArrayList<>(basicArgs), ver2Args = new ArrayList<>(basicArgs);
            Collections.addAll(ver1Args,
                    "--output-code", workingDir.resolve(out1).resolve("src").toString(),
                    "--output-resources", workingDir.resolve(out1).resolve("resources").toString(),
                    ver1
            );
            Collections.addAll(ver2Args,
                    "--output-code", workingDir.resolve(out2).resolve("src").toString(),
                    "--output-resources", workingDir.resolve(out2).resolve("resources").toString(),
                    ver2
            );

            if (!hasExc1) {
                xland.ioutils.xdecompiler.Main.main(ver1Args.toArray(new String[0]));
            }

            if (!hasExc2) {
                xland.ioutils.xdecompiler.Main.main(ver2Args.toArray(new String[0]));
            }

            LOGGER.info("Running diff script");
            return new DiffTwoScript(ver1, ver2, workingDir, workingDir.resolve(out1), workingDir.resolve(out2), output);
        });
    }

    private static String getRealVersion(String s) throws IllegalArgumentException {
        if (!s.startsWith("LATEST"))
            return s;
        VersionManifest manifest = VersionManifest.getOrFetch();

        s = s.substring(6);
        if (s.isEmpty())
            return manifest.latestSnapshot();

        boolean startsWithRelease = false;
        if (s.startsWith("_RELEASE")) {
            s = s.substring(8);
            if (s.isEmpty())
                return manifest.latestRelease();
            startsWithRelease = true;
        }
        if (!s.startsWith("-"))
            throw new IllegalArgumentException("LATEST_RELEASE not followed with '-'");
        if (s.length() < 2)
            throw new IllegalArgumentException("LATEST_RELEASE- followed by nothing");
        boolean minusRelease = false;
        if (s.charAt(1) == 'R') {
            minusRelease = true;
            s = s.substring(2);
        } else {
            s = s.substring(1);
        }
        int minus = Integer.parseInt(s);
        if (minus < 0) throw new IllegalArgumentException("MINUS can't be negative");
        if (minusRelease) {
            if (!startsWithRelease) {
                return manifest.versions().stream()
                        .filter(Predicate.not(VersionManifest.VersionMeta::isSnapshot))
                        .skip(minus)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Specified version minus is absent"))
                        .id();
            } else {
                int j = 0;
                for (VersionManifest.VersionMeta version : manifest.versions()) {
                    if (version.isSnapshot()) continue;
                    if (j++ >= minus) return version.id();
                }
                throw new IllegalArgumentException("Specified version minus is absent");
            }
        } else {
            if (!startsWithRelease) {
                return manifest.versions().stream()
                        .skip(minus)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Specified version minus is absent"))
                        .id();
            } else {
                boolean foundHead = false;
                int j = 0;
                for (VersionManifest.VersionMeta version : manifest.versions()) {
                    if (!foundHead) {
                        if (version.isSnapshot()) continue;
                        foundHead = true;
                    }
                    if (j++ >= minus) return version.id();
                }
                throw new IllegalArgumentException("Specified version minus is absent");
            }
        }
    }

    private static final Collection<String> ILLEGAL_FORMATS = List.of(
            ".zip", ".jar",
            ".tar",
            ".tar.xz", ".txz",
            ".tar.gz", ".taz", ".tgz",
            ".tar.bz2", ".tb2", ".tbz", ".tbz2", ".tz2"
    );

    private static boolean checkValidSubdirName(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        for (String format : ILLEGAL_FORMATS) {
            if (s.endsWith(format)) {
                LOGGER.error("Invalid subdir name: {}", t);
                return true;
            }
        }
        return false;
    }
}
