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
package xland.ioutils.xdecompiler.mappings;

import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.format.tiny.Tiny1FileReader;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.VisitOrder;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class MappingUtil {
    static void readV1RemoteJar(URL url, MappingVisitor visitor) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(url.openStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("mappings/mappings.tiny".equals(entry.getName()))
                    break;
            }
            if (entry == null)
                throw new RuntimeException("Invalid mapping jar: " + url);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(zis))) {
                Tiny1FileReader.read(reader, visitor);
            }
        }
    }

    static MappingVisitor classMemberFilter(MappingVisitor prev, ClassMemberInfoPool classMembers) {
        return new ForwardingMappingVisitor(prev) {
            private String className;

            @Override
            public boolean visitClass(String srcName) throws IOException {
                className = srcName;
                return super.visitClass(srcName);
            }

            @Override
            public boolean visitField(String srcName, String srcDesc) throws IOException {
                if (!classMembers.hasField(className, srcName, srcDesc)) return false;
                return super.visitField(srcName, srcDesc);
            }

            @Override
            public boolean visitMethod(String srcName, String srcDesc) throws IOException {
                if (!classMembers.hasMethod(className, srcName, srcDesc)) return false;
                return super.visitMethod(srcName, srcDesc);
            }
        };
    }

    static final MappingTreeView EMPTY_MAPPING_TREE_VIEW = new EmptyMappingTreeView();

    private static final class EmptyMappingTreeView implements MappingTreeView {
        private EmptyMappingTreeView() {}

        @Override
        public @Nullable String getSrcNamespace() {
            return null;
        }

        @Override
        public List<String> getDstNamespaces() {
            return Collections.emptyList();
        }

        @Override
        public List<? extends MetadataEntryView> getMetadata() {
            return Collections.emptyList();
        }

        @Override
        public List<? extends MetadataEntryView> getMetadata(String key) {
            return Collections.emptyList();
        }

        @Override
        public Collection<? extends ClassMappingView> getClasses() {
            return Collections.emptyList();
        }

        @Override
        public @Nullable ClassMappingView getClass(String srcName) {
            return null;
        }

        @Override
        public void accept(MappingVisitor visitor, VisitOrder order) {
        }
    }
}
