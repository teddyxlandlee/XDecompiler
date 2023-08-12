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
package xland.ioutils.xdecompiler;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.util.PathConverter;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import org.slf4j.Logger;
import xland.ioutils.xdecompiler.decompile.DecompilerProvider;
import xland.ioutils.xdecompiler.mappings.MappingProvider;
import xland.ioutils.xdecompiler.mcmeta.ConcernedVersionDetail;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.merge.ExtractBundler;
import xland.ioutils.xdecompiler.merge.JarMerger;
import xland.ioutils.xdecompiler.remap.RemapUtil;
import xland.ioutils.xdecompiler.util.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;

public record Main(String version, DecompilerProvider decompilerProvider,
                   Path output, Path outputRes, Path libCache,
                   Map<String, MappingProvider> mappingProviders, Map<String, String> mappingArgs) {
    private static final Logger LOGGER = LogUtils.getLogger();

    public void runProgram() throws Exception {
        // 1. mcmeta
        LOGGER.info("1. Downloading MCMeta...");
        final VersionManifest.VersionMeta versionMeta = VersionManifest.getOrFetch().getVersion(version());
        Objects.requireNonNull(versionMeta, () -> "Missing version: " + version());
        final ConcernedVersionDetail detail = versionMeta.getOrFetchDetail();

        // 2. merge
        LOGGER.info("2. Preparing client & server jars...");
        final File clientJar = TempDirs.get().createFileDefaultFs();
        final File serverJar0 = TempDirs.get().createFileDefaultFs();
        detail.clientJar().download(clientJar.toPath());
        detail.serverJar().download(serverJar0.toPath());
        File serverJar;
        if (isBundledServerJar(serverJar0)) {
            LOGGER.info("\tDetected the server jar is bundled. Extracting...");
            serverJar = TempDirs.get().createFileDefaultFs();
            try (JarFile jf = new JarFile(serverJar0)) {
                ExtractBundler.run(jf, serverJar.toPath());
            }
        } else {
            serverJar = serverJar0;
            LOGGER.info("\tServer jar is legacy, keep...");
        }

        LOGGER.info("\tMerging...");
        final Path mergedJar = TempDirs.get().createFile();
        final Path resources = TempDirs.get().createFile();

        try (ZipOutputStream mergedJarOut = new ZipOutputStream(Files.newOutputStream(mergedJar));
             ZipOutputStream resourcesOut = new ZipOutputStream(Files.newOutputStream(resources))) {
            var jarMerger = new JarMerger(clientJar, serverJar, mergedJarOut, resourcesOut);
            jarMerger.enableSnowmanRemoval();
            jarMerger.enableSyntheticParamsOffset();
            jarMerger.merge();
        }

        LOGGER.info("\tDumping resources...");
        FileUtils.extractZip(resources, outputRes());

        // 3. libraries
        LOGGER.info("3. Downloading libraries...");
        final Collection<Path> libraries = detail.downloadLibrariesAsync(libCache());

        // 4. mappings
        LOGGER.info("4. Generating mapping tree...");
        final MappingTreeView mapping = MappingProvider.prepareAll(mappingProviders(), mappingArgs(), versionMeta);
        Collection<MappingProvider> mappingsToRemap = mappingProviders().values().stream()
                .filter(MappingProvider::isRemapTarget)
                //.map(MappingProvider::destNamespace)
                .toList();
        LOGGER.info("\tTarget namespaces to remap: {}", mappingsToRemap.stream().map(MappingProvider::destNamespace).toList());

        // 5. remap & decompile
        LOGGER.info("5. Starting remap & decompile...");
        ExecutorService executors = Executors.newFixedThreadPool(PublicProperties.remapThreads());
        CompletableFuture.allOf(mappingsToRemap.stream()
                .map(provider -> CompletableFuture.supplyAsync(() -> {
                    // remap
                    LOGGER.info("...Remapping {}", provider.id());
                    try {
                        final Path path = TempDirs.get().createFile();
                        remap(mergedJar, libraries, path, mapping, provider.destNamespace());
                        return Map.entry(path, provider.id());
                    } catch (IOException e) {
                        sneakyThrow(e);
                        throw new AssertionError(); // unreachable
                    }
                }, executors))
                .map(cf -> cf.thenAcceptAsync(pair -> {
                    // decompile
                    LOGGER.info("...Decompiling {}", pair.getValue());
                    decompilerProvider().decompile(pair.getKey(), libraries, output().resolve(pair.getValue()));
                }, executors))
                .toArray(CompletableFuture[]::new)
        ).join();
        executors.shutdown();
    }

    private static void remap(Path input, Collection<Path> libraries, Path output,
                              MappingTreeView mapping, String targetNs) throws IOException {
        final TinyRemapper r = RemapUtil.getTinyRemapper(mapping, "official", targetNs, builder -> {});

        r.readInputs(input);
        r.readClassPath(libraries.toArray(new Path[0]));
        try (OutputConsumerPath outputConsumerPath = new OutputConsumerPath.Builder(output)
                .assumeArchive(true).build()) {
            r.apply(outputConsumerPath);
        }
    }

    private static boolean isBundledServerJar(File file) throws IOException {
        try (JarFile jarFile = new JarFile(file)) {
            return (jarFile.getJarEntry("net/minecraft/bundler/Main.class")) != null;
        }
    }

    public static void main(String... args) {
        OptionParser parser = new OptionParser();
        var mappings = parser.accepts("mappings", "Mappings to load, with arguments")
                .withRequiredArg()
                .defaultsTo("intermediary", "yarn=latest", "mojmaps");
        var decompiler = parser.accepts("decompiler", "Decompiler used")
                .withRequiredArg()
                .defaultsTo("vineflower");
        var versionId = parser.nonOptions("Version id");
        var libCache0 = parser.accepts("lib-cache", "Cache directory to store libraries. " +
                "Will not be cleaned up.")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter())
                .defaultsTo(Path.of("libraries"));
        var codeOut0 = parser.accepts("output-code", "Output dir for decompiled code, sorted " +
                "by mappings")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter())
                .defaultsTo(Path.of("out", "src"));
        var resOut0 = parser.accepts("output-resources", "Output dir for resources")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter())
                .defaultsTo(Path.of("out", "resources"));
        var help = parser.accepts("help").forHelp();
        if (args.length == 0) {
            try { parser.printHelpOn(System.out); } catch (IOException e) { sneakyThrow(e); } return ;
        }

        final OptionSet parsed = parser.parse(args);
        if (parsed.has(help)) {
            try { parser.printHelpOn(System.out); } catch (IOException e) { sneakyThrow(e); } return ;
        }

        final String version = parsed.valueOf(versionId);

        DecompilerProvider decompilerProvider = ServiceProviders.identified(DecompilerProvider.class).get(parsed.valueOf(decompiler));
        if (decompilerProvider == null) {
            LOGGER.error("Error: decompiler {} not found", parsed.valueOf(decompiler));
            return ;
        }

        Map<String, MappingProvider> mappingProviders = ServiceProviders.identified(MappingProvider.class);
        Map<String, String> mappingArgs = parsed.valuesOf(mappings).stream()
                .map(s -> {
                    final int i = s.indexOf('=');
                    if (i < 0) return Map.entry(s, "");
                    return Map.entry(s.substring(0, i), s.substring(i + 1));
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (!mappingProviders.keySet().containsAll(mappingArgs.keySet())) {
            LOGGER.error("Some of given mapping providers are not available. Available providers are: {}",
                    mappingProviders.keySet());
            return ;
        }
        mappingProviders = mappingArgs.keySet().stream()
                .collect(Collectors.toMap(Function.identity(), mappingProviders::get));

        Path libCache = parsed.valueOf(libCache0);
        Path codeOut = parsed.valueOf(codeOut0);
        Path resOut = parsed.valueOf(resOut0);

        try {
            new Main(version, decompilerProvider, codeOut, resOut, libCache, mappingProviders, mappingArgs).runProgram();
        } catch (Exception e) {
            sneakyThrow(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
