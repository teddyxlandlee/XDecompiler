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

import mjson.Json;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xland.ioutils.xdecompiler.mcmeta.VersionManifest;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

record ConfigFile(boolean releaseOnly, List<String> mappings, String decompiler,
                  RootBranch rootBranch, List<ParentedBranch> subBranches) {
    static ConfigFile fromJson(Json json) {
        final boolean releaseOnly = json.has("release_only") && json.at("release_only").asBoolean();
        final List<String> mappings = json.has("mappings") ?
                json.at("mappings").asJsonList().stream().map(Json::asString).toList() :
                List.of("mojmaps", "yarn", "intermediary");
        final String decompiler = json.has("decompiler") ? json.at("decompiler").asString() : "vineflower";
        final RootBranch rootBranch = RootBranch.fromJson(json.at("main"));
        final List<ParentedBranch> subBranches = json.at("branches").asJsonMap().entrySet().stream()
                .map(e -> ParentedBranch.fromJson(e.getKey(), e.getValue()))
                .toList();
        return new ConfigFile(releaseOnly, mappings, decompiler, rootBranch, subBranches);
    }

    boolean validate(VersionManifest v) {
        Set<String> versionsHistory = new HashSet<>();
        String rootVersion = rootBranch().from();
        versionsHistory.add(rootVersion);

        final List<ParentedBranch> subBranches = subBranches();

        Set<String> parentBranches = new HashSet<>();
        subBranches.stream().map(ParentedBranch::parentBranch).forEach(parentBranches::add);

        Set<String> branchNames = subBranches.stream().map(Branch::branchName).collect(Collectors.toSet());
        branchNames.add("main");

        if (!branchNames.containsAll(parentBranches))
            return false;

        for (ParentedBranch sub : subBranches) {
            if (sub.versions().stream().allMatch(versionsHistory::add)) {
                // check recursive dependency
                Set<String> parentBranchHistory = new HashSet<>();
                for (Branch b = sub; b != null; b = b.parentBranchFrom(this)) {
                    if (!parentBranchHistory.add(b.branchName()))   // ever existed
                        return false;
                }
                continue;
            }
            return false;
        }

        if (versionsHistory.stream().map(v::getVersion)
                .anyMatch(((Predicate<VersionManifest.VersionMeta>) Objects::isNull)
                        .or(v0 -> !releaseOnly() && v0.isSnapshot())))
            return false;

        return versionsHistory.stream()
                .min(v.versionComparator())
                .map(rootVersion::equals)
                .orElse(Boolean.FALSE);
    }

    Branch getBranch(String version) {
        final Optional<? extends Branch> o = subBranches().stream()
                .filter(p -> p.versions().contains(version))
                .findAny();
        return o.isPresent() ? o.get() : this.rootBranch();
    }

    List<ParentedBranch> getChildrenBranches(String version) {
        return subBranches().stream().filter(p -> Objects.equals(p.from(), version)).toList();
    }

    List<ProcessEntriesProvider> processes(VersionManifest manifest) {
        // assert validate(manifest);
        final VersionManifest.VersionMeta rootVersion = manifest.getVersion(rootBranch().from());

        final List<String> list = manifest.versions().stream()
                .filter(v -> v.compareTo(rootVersion) >= 0)
                .filter(v -> this.getBranch(v.id()) == this.rootBranch())
                .filter(v -> !this.releaseOnly() || !v.isSnapshot())
                .sorted()
                .map(VersionManifest.VersionMeta::id)
                .toList();
        final List<ProcessEntriesProvider> ret = new ArrayList<>();
        ret.add(this.rootBranch());

        processIterateVersions(list, ret::add);
        return ret;
    }

    private void processIterateVersions(List<String> versions, Consumer<ProcessEntriesProvider> c) {
        for (String s : versions) {
            c.accept(ProcessEntriesProvider.ofVersion(s));
            final List<ParentedBranch> childrenBranches = getChildrenBranches(s);
            if (childrenBranches.isEmpty()) continue;
            for (ParentedBranch b : childrenBranches) {
                c.accept(b);
                processIterateVersions(b.versions(), c);
            }
            c.accept(getBranch(s));
        }
    }

    List<ProcessEntriesProvider> processes(VersionManifest manifest, List<String> knownCurrentVersions, @Nullable String stopPoint) {
        knownCurrentVersions = knownCurrentVersions.stream().filter(Predicate.not(String::isBlank)).toList();

        final List<ProcessEntriesProvider> list = processes(manifest);
        if (knownCurrentVersions.isEmpty()) return list;

        int sub = -1;
        int end = -1;

        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof VersionEntryProvider entry) {
                if (knownCurrentVersions.contains(entry.version())) {
                    sub = i + 1;
                } else if (Objects.equals(entry.version(), stopPoint)) {
                    end = i + 1;
                }
            }
        }

        if (stopPoint == null)
            end = list.size();

        if (sub < 0 || end < 0)
            throw new IllegalStateException("Known current versions are not provided: " + knownCurrentVersions);

        return list.subList(sub, end);
    }

    interface Branch extends ProcessEntriesProvider {
        String branchName();
        @Nullable String parentBranch();

        default @Nullable Branch parentBranchFrom(ConfigFile conf) {
            final String s = parentBranch();
            if ("main".equals(s)) return conf.rootBranch();
            return s == null ? null : conf.subBranches().stream()
                    .filter(p -> s.equals(p.branchName()))
                    .findAny()
                    .orElseThrow(); // validated
        }

        default Map.Entry<ProcessType, String[]> appendProcess() {
            return Map.entry(ProcessType.CHECKOUT, new String[]{ branchName() });
        }
    }

    record RootBranch(String from) implements Branch {
        @Override
        public String branchName() {
            return "main";
        }

        @Override
        public @Nullable String parentBranch() {
            return null;
        }

        static RootBranch fromJson(Json json) {
            return new RootBranch(json.at("from").asString());
        }
    }

    record ParentedBranch(String branchName, String parent, String from, List<String> versions) implements Branch {
        @Override
        public @NotNull String parentBranch() {
            return parent;
        }

        static ParentedBranch fromJson(String key, Json json) {
            final String parent = json.at("parent").asString();
            final String from = json.at("from").asString();
            final List<String> versions = json.at("versions").asJsonList().stream().map(Json::asString).toList();
            return new ParentedBranch(key, parent, from, versions);
        }
    }
}
