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
import xland.ioutils.xdecompiler.mappings.ClassMemberInfoPool;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;

public record Main(String version, DecompilerProvider decompilerProvider,
                   Path output, Path outputRes, Path libCache,
                   Map<String, MappingProvider> mappingProviders, Map<String, String> mappingArgs) {
    private static final Logger LOGGER = LogUtils.getLogger();

    public void runProgram() throws Exception {
        LOGGER.info("Running for {}", version());
        // 1. mcmeta
        LOGGER.info("1. Downloading MCMeta...");
        final VersionManifest.VersionMeta versionMeta = VersionManifest.getOrFetch().getVersion(version());
        Objects.requireNonNull(versionMeta, () -> "Missing version: " + version());
        final ConcernedVersionDetail detail = versionMeta.getOrFetchDetail();

        // 2. merge
        LOGGER.info("2. Preparing client & server jars...");
        final File clientJar = TempDirs.get().createFileDefaultFs();
        final File serverJarUnprocessed = TempDirs.get().createFileDefaultFs();
        detail.clientJar().download(clientJar.toPath());
        detail.serverJar().download(serverJarUnprocessed.toPath());
        File serverJar;
        if (isBundledServerJar(serverJarUnprocessed)) {
            LOGGER.info("\tDetected the server jar is bundled. Extracting...");
            serverJar = TempDirs.get().createFileDefaultFs();
            try (JarFile jf = new JarFile(serverJarUnprocessed)) {
                ExtractBundler.run(jf, serverJar.toPath());
            }
        } else {
            serverJar = serverJarUnprocessed;
            LOGGER.info("\tServer jar is legacy, keep...");
        }

        LOGGER.info("\tMerging...");
        final Path mergedJar = TempDirs.get().createFile(".jar");
        final Path resources = TempDirs.get().createFile();

        try (ZipOutputStream mergedJarOut = new ZipOutputStream(Files.newOutputStream(mergedJar));
             ZipOutputStream resourcesOut = new ZipOutputStream(Files.newOutputStream(resources))) {
            var jarMerger = new JarMerger(clientJar, serverJar, mergedJarOut, resourcesOut);
            if (!detail.isUnobfuscated()) {     // optimizes unobfuscated versions
                jarMerger.enableSnowmanRemoval();
                jarMerger.enableSyntheticParamsOffset();
            }
            jarMerger.merge();
        }

        LOGGER.info("\tDumping resources...");
        LOGGER.debug("Resources zip at {}", resources);
        xland.ioutils.xdecompiler.util.DebugUtils.log(DebugUtils.DELETE_OLD_RESOURCES, l -> {
            l.info("Deleting old files due to debug flag {}", DebugUtils.DELETE_OLD_RESOURCES);
            try {
                xland.ioutils.xdecompiler.util.FileUtils.deleteRecursively(outputRes(), true);
            } catch (IOException e) {
                l.warn("\tFailed to delete");
            }
        });
        FileUtils.extractZip(resources, outputRes());

        // 3. libraries
        LOGGER.info("3. Downloading libraries...");
        final Collection<Path> libraries = detail.downloadLibrariesAsync(libCache());

        // 4. read class member info
        LOGGER.info("4. Reading class member info...");
        final ClassMemberInfoPool classMemberInfoPool = ClassMemberInfoPool.fromJar(mergedJar);

        // 5. mappings
        LOGGER.info("5. Generating mapping tree...");
        final MappingTreeView mapping;
        final Map.Entry<MappingTreeView, Collection<MappingProvider>> preparedMappings = MappingProvider.prepareAll(mappingProviders(), mappingArgs(), classMemberInfoPool, versionMeta);
        mapping = preparedMappings.getKey();
        Collection<MappingProvider> mappingsToRemap = preparedMappings.getValue();
        LOGGER.info("\tTarget namespaces to remap: {}", mappingsToRemap.stream().map(MappingProvider::destNamespace).toList());

        xland.ioutils.xdecompiler.util.DebugUtils.log(DebugUtils.DUMP_MAPPING_TREE, l -> {
            try {
                Path path = TempDirs.get().createFile();
                l.info("Dumping mapping tree to {} due to debug flag {}", path, DebugUtils.DUMP_MAPPING_TREE);
                try (var v = new net.fabricmc.mappingio.format.tiny.Tiny1FileWriter(Files.newBufferedWriter(path))) {
                    mapping.accept(v);
                }
            } catch (IOException e) {
                l.error("Failed to dump mapping tree", e);
            }
        });

        // 6. remap & decompile
        LOGGER.info("6. Starting remap & decompile...");
        // If there is more than one remap-free provider, then we can reuse its decompile result
        AtomicReference<String> firstRemapFreeProviderId = new AtomicReference<>();
        var copyCandidates = new CopyOnWriteArrayList<String>();

        try (ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor()) {
            // decompiling is always single-threaded

            record DecompileInput(Path decompileSource, String providerId, boolean isRemapFree) {}

            // CPU-consuming
            ConcurrentUtils.runPlatform("remap", PublicProperties.remapThreads(), executors -> mappingsToRemap.stream()
                    .map(provider -> {
                        final String destNamespace = provider.destNamespace();
                        final String providerId = provider.id();

                        if (mapping.getNamespaceId(destNamespace) == MappingTreeView.NULL_NAMESPACE_ID) {
                            // no remapping needed
                            LOGGER.info("No remapping needed for {}", providerId);
                            return CompletableFuture.completedFuture(new DecompileInput(mergedJar, providerId, true));
                        }

                        return CompletableFuture.supplyAsync(() -> {
                            // remap
                            LOGGER.info("...Remapping {}", providerId);
                            try {
                                final long t0 = System.nanoTime();
                                final Path remapped = TempDirs.get().createFile();
                                Files.deleteIfExists(remapped); // to avoid ProviderNotFoundException
                                remap(mergedJar, libraries, remapped, mapping, destNamespace);
                                LOGGER.info("...Remapped {} in {}", providerId, TimeUtils.timeFormat(System.nanoTime() - t0));
                                return new DecompileInput(remapped, providerId, false);
                            } catch (IOException e) {
                                CommonUtils.sneakyThrow(e);
                                throw new IncompatibleClassChangeError(); // unreachable
                            }
                        }, executors);
                    })
                    .map(cf -> cf.thenAcceptAsync(decompileInput -> {
                        // decompile
                        if (decompileInput.isRemapFree()) {
                            if (!firstRemapFreeProviderId.compareAndSet(null, decompileInput.providerId())) {
                                // Reuse its result. Queue into copy candidates
                                copyCandidates.add(decompileInput.providerId());
                                return;
                            }
                        }

                        LOGGER.info("...Decompiling {}", decompileInput.providerId());
                        LOGGER.debug("\tClasses of {} is from {}", decompileInput.providerId(), decompileInput.decompileSource());

                        final Path pathOut = output().resolve(decompileInput.providerId());
                        try {
                            Files.createDirectories(pathOut);
                        } catch (IOException e) {
                            CommonUtils.sneakyThrow(e);
                        }

                        decompilerProvider().decompile(decompileInput.decompileSource(), libraries, pathOut);
                    }, singleThreadExecutor))
            );

            if (firstRemapFreeProviderId.get() != null) {
                Path src = output().resolve(firstRemapFreeProviderId.get());
                for (String candidate : copyCandidates) {
                    Path dst = output().resolve(candidate);
                    LOGGER.info("...Copying decompile result from {} to {}", firstRemapFreeProviderId.get(), candidate);
                    // Copy the whole directory (src -> dst), including all contents of all depths.
                    // Creates dst if not exist
                    FileUtils.copyDirRecursively(src, dst);
                }
            }
        }
    }

    public static void remap(Path input, Collection<Path> libraries, Path output,
                              MappingTreeView mapping, String targetNs) throws IOException {
        final TinyRemapper r = RemapUtil.getTinyRemapper(mapping, MappingProvider.SOURCE_NAMESPACE, targetNs, _ -> {});

        try (OutputConsumerPath outputConsumerPath = new OutputConsumerPath.Builder(output)
                .assumeArchive(true).build()) {
            r.readInputs(input);
            r.readClassPath(libraries.toArray(new Path[0]));
            r.apply(outputConsumerPath);
        } finally {
            r.finish();
            LOGGER.debug("Finished remapping from {} to {}", input, output);
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
                .defaultsTo("mojmaps");
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
            try {
                parser.printHelpOn(System.out);
            } catch (IOException e) {
                CommonUtils.sneakyThrow(e);
            }
            return;
        } else if ("--run-script".equals(args[0])) {
            final int l = args.length - 1;
            String[] newArgs = new String[l];
            System.arraycopy(args, 1, newArgs, 0, l);
            try {
                xland.ioutils.xdecompiler.script.Script.main(newArgs);
            } catch (Throwable t) {
                CommonUtils.sneakyThrow(t);
            }
            return;
        }

        final OptionSet parsed = parser.parse(args);
        if (parsed.has(help)) {
            try {
                parser.printHelpOn(System.out);
            } catch (IOException e) {
                CommonUtils.sneakyThrow(e);
            }
            return;
        }

        final String version = parsed.valueOf(versionId);
        Objects.requireNonNull(version, "Version must be specified");

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
            return;
        }
        mappingProviders = mappingArgs.keySet().stream()
                .collect(Collectors.toMap(Function.identity(), mappingProviders::get));
        if (mappingProviders.isEmpty()) {
            LOGGER.error("No available mapping provider");
            return;
        }

        Path libCache = parsed.valueOf(libCache0);
        Path codeOut = parsed.valueOf(codeOut0);
        Path resOut = parsed.valueOf(resOut0);

        try {
            final long t0 = System.nanoTime();
            new Main(version, decompilerProvider, codeOut, resOut, libCache, mappingProviders, mappingArgs).runProgram();
            LOGGER.info("Done! {}", TimeUtils.timeFormat(System.nanoTime() - t0));
        } catch (Exception e) {
            CommonUtils.sneakyThrow(e);
        }
    }

    static {
        // Actually to load PublicProperties class
        PublicProperties.LOGGER.debug("Initializing public properties");
    }
}
