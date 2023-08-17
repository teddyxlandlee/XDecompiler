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
package xland.ioutils.xdecompiler.script.gitrepo;

import joptsimple.OptionSet;
import joptsimple.ValueConverter;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import mjson.Json;
import org.jetbrains.annotations.Nullable;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;
import xland.ioutils.xdecompiler.script.Script;
import xland.ioutils.xdecompiler.util.CommonUtils;
import xland.ioutils.xdecompiler.util.PublicProperties;
import xland.ioutils.xdecompiler.util.TimeUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

public class GitRepoScript extends Script {
    private final Path scriptOutput;
    private final ConfigFile configFile;
    private final Map<ProcessType, String> overrideCommandsMap;
    private final List<String> knownCurrentVersions;
    private final Duration timeoutSoft, timeoutForce;
    private final @Nullable String stopPoint;

    GitRepoScript(ConfigFile configFile, Map<ProcessType, String> overrideCommandsMap, List<String> knownCurrentVersions, Path scriptOutput, Duration timeoutSoft, Duration timeoutForce, @Nullable String stopPoint) {
        this.configFile = configFile;
        this.overrideCommandsMap = overrideCommandsMap;
        this.knownCurrentVersions = knownCurrentVersions;
        this.scriptOutput = scriptOutput;
        this.timeoutSoft = timeoutSoft;
        this.timeoutForce = timeoutForce;
        this.stopPoint = stopPoint;
    }

    @Override
    protected void runScript() throws Exception {
        System.setProperty("xdecompiler.download.mc.manifest", download(Path.of("version_manifest_v2.json"), PublicProperties::versionManifestUrl));
        System.setProperty("xdecompiler.download.vineflower", download(Path.of("vineflower.jar"), PublicProperties::vineFlowerUrl));

        VersionManifest manifest = VersionManifest.getOrFetch();
        final List<ProcessEntriesProvider> entriesProviders;
        if (configFile.validate(manifest)) {
            entriesProviders = configFile.processes(manifest, knownCurrentVersions, stopPoint);
        } else throw new IllegalArgumentException("Invalid config file");

        String[] initArgs = {
                Long.toString(timeoutSoft.toMillis()),
                Long.toString(timeoutForce.toMillis()),
                PublicProperties.versionManifestUrl(),
                PublicProperties.vineFlowerUrl(),
                configFile.decompiler()
        }, postArgs = {
        };
        List<String> initArgs0 = new ArrayList<>();
        Collections.addAll(initArgs0, initArgs);
        initArgs0.addAll(configFile.mappings());
        initArgs = initArgs0.toArray(new String[0]);

        try (var writer = Files.newBufferedWriter(scriptOutput)) {
            ProcessEntriesProvider.writeCommands(
                    writer,
                    entriesProviders,
                    initArgs,
                    postArgs,
                    overrideCommandsMap
            );
        }
    }

    private static String download(Path path, Supplier<String> urlProvider) throws IOException {
        try (var is = new URL(urlProvider.get()).openStream()) {
            Files.copy(is, path);
        }
        return path.toUri().toURL().toString();
    }

    public static void main(String[] args) {
        final Path initSh = Path.of("init.sh");
        final Path postSh = Path.of("post.sh");
        extract(initSh, "init.sh");
        extract(postSh, "post.sh");

        start(parser -> {
            var overrideCommands0 = parser.accepts("override-commands").withRequiredArg();
            var scriptOutput = parser.accepts("script-output", "Output script path")
                    .withRequiredArg()
                    .withValuesConvertedBy(new PathConverter())
                    .defaultsTo(Path.of("output.sh"));
            var knownCurrentVersions0 = parser.nonOptions("Known current versions");
            var configFile0 = parser.accepts("config")
                    .withRequiredArg()
                    .withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));
            var timeoutSoft0 = parser.accepts("timeout-soft")
                    .withRequiredArg()
                    .withValuesConvertedBy(DurationConverter.INSTANCE)
                    .defaultsTo(Duration.ofHours(5));
            var timeoutForce0 = parser.accepts("timeout-force")
                    .withRequiredArg()
                    .withValuesConvertedBy(DurationConverter.INSTANCE)
                    .defaultsTo(Duration.ofMinutes(5 * 60 + 30));
            var stopPoint0 = parser.accepts("stops-at").withRequiredArg();

            final OptionSet parsed = parser.parse(args);
            if (args.length == 0)
                printHelpAndExit(parser);

            final Map<ProcessType, String> overrideCommandsMap = new HashMap<>();
            parsed.valuesOf(overrideCommands0).forEach(s -> ProcessType.override(s, overrideCommandsMap));

            final ConfigFile configFile = ConfigFile.fromJson(Json.read(Files.readString(
                    Objects.requireNonNull(parsed.valueOf(configFile0), "--config should be specified")
            )));

            return new GitRepoScript(
                    configFile,
                    overrideCommandsMap,
                    parsed.valuesOf(knownCurrentVersions0),
                    parsed.valueOf(scriptOutput),
                    parsed.valueOf(timeoutSoft0),
                    parsed.valueOf(timeoutForce0),
                    parsed.valueOf(stopPoint0));
        });
    }

    private static void extract(Path to, String from) {
        if (Files.exists(to)) return;
        try (var in = GitRepoScript.class.getResourceAsStream("/assets/scripts/gitrepo/" + from)) {
            Objects.requireNonNull(in, from);
            Files.copy(in, to);
        } catch (IOException e) {
            CommonUtils.sneakyThrow(e);
        }
    }

    private static final class DurationConverter implements ValueConverter<Duration> {
        static final DurationConverter INSTANCE = new DurationConverter();

        @Override
        public Duration convert(String value) {
            return TimeUtils.fromString(value);
        }

        @Override
        public Class<? extends Duration> valueType() {
            return Duration.class;
        }

        @Override
        public String valuePattern() {
            return "XhXmXsXXms";
        }
    }
}
