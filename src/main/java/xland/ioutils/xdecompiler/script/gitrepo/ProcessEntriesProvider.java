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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@FunctionalInterface
interface ProcessEntriesProvider {
    Map.Entry<ProcessType, String[]> appendProcess();

    static ProcessEntriesProvider ofVersion(final String version) {
        return new VersionEntryProvider(version);
    }

    static void writeCommands(Appendable appendable,
                              List<ProcessEntriesProvider> processes,
                              String[] initArgs, String[] postArgs,
                              Map<ProcessType, String> defaultCommands) throws IOException {
        final List<Map.Entry<ProcessType, String[]>> list = processes.stream()
                .map(ProcessEntriesProvider::appendProcess)
                .toList(),
                list1 = new ArrayList<>();
        list1.add(Map.entry(ProcessType.INITIALIZE, initArgs));
        list1.addAll(list);
        list1.add(Map.entry(ProcessType.POST, postArgs));
        ProcessType.writeCommands(appendable, list1, defaultCommands);
    }
}
